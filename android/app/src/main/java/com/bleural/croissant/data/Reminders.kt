package com.bleural.croissant.data

import android.content.Context
import com.bleural.croissant.ask.Ledger
import com.bleural.croissant.data.JsonStore.Companion.stringOrNull
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * One reminder: a todo with no when. Anything wanted at a date or a time is a calendar
 * event instead (2026-09-16). `createdAt`, the day the list files it under,
 * and `doneAt` are ISO times with offset. `color` is a palette name.
 */
data class Reminder(
    val id: String,
    val title: String,
    val color: String,
    val done: Boolean,
    val doneAt: String?,
    val createdAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("color", color)
        put("done", done)
        if (doneAt != null) put("doneAt", doneAt)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getString("id"),
            title = o.getString("title"),
            color = o.optString("color", Palette.NAMES.first()),
            done = o.optBoolean("done", false),
            doneAt = o.stringOrNull("doneAt"),
            createdAt = o.optString("createdAt", ""),
        )
    }
}

/** The eight item colours, by name; the hex values live in ui/Theme.kt. Assigned round-robin so neighbours differ. */
object Palette {
    val NAMES = listOf("orange", "lavender", "pink", "cyan", "violet", "green", "red", "yellow")
    fun after(name: String?): String {
        val i = NAMES.indexOf(name)
        return if (i < 0) NAMES.first() else NAMES[(i + 1) % NAMES.size]
    }
}

/** A plain-text error the model can relay. */
class ToolError(message: String) : Exception(message)

/**
 * `reminders.json`. At most [MAX] items including done ones; done items are dropped
 * [KEEP_DONE_DAYS] after they were ticked.
 */
class RemindersStore(
    file: File,
    clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : JsonStore<Reminder>(file, clock) {
    companion object {
        const val MAX = 50
        const val KEEP_DONE_DAYS = 7L
        const val TITLE_MAX = 60
    }

    init { load() }

    override fun encode(item: Reminder) = item.toJson()
    override fun decode(o: JSONObject) = Reminder.fromJson(o)
    override fun keep(item: Reminder, now: OffsetDateTime): Boolean {
        if (!item.done) return true
        val at = item.doneAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        return at == null || at.isAfter(now.minusDays(KEEP_DONE_DAYS))
    }

    @Synchronized
    fun add(title: String?): Reminder {
        val t = cleanTitle(title)
        if (list().size >= MAX) throw ToolError("the reminder list is full ($MAX); delete or complete some first")
        val r = Reminder(
            id = newId("r"), title = t,
            color = Palette.after(list().lastOrNull()?.color),
            done = false, doneAt = null, createdAt = Dates.stamp(clock()),
        )
        commit(list() + r)
        return r
    }

    /** Null means "leave as is". */
    @Synchronized
    fun update(id: String?, title: String? = null, done: Boolean? = null): Reminder {
        val old = find(id)
        var r = old
        if (title != null) r = r.copy(title = cleanTitle(title))
        if (done != null && done != old.done) r = r.copy(done = done, doneAt = if (done) Dates.stamp(clock()) else null)
        commit(list().map { if (it.id == old.id) r else it })
        return r
    }

    @Synchronized
    fun delete(id: String?) {
        val r = find(id)
        commit(list().filter { it.id != r.id })
    }

    /** The reminder page's Done button. */
    fun toggle(id: String) { runCatching { update(id, done = !find(id).done) } }

    private fun find(id: String?): Reminder =
        list().firstOrNull { it.id == id } ?: throw ToolError("no reminder ${id ?: ""}".trim())

    private fun cleanTitle(title: String?): String {
        val t = title?.trim()?.replace(Regex("\\s+"), " ") ?: ""
        if (t.isEmpty()) throw ToolError("title is required")
        return t.take(TITLE_MAX)
    }
}

/** Date arguments as the tools define them (assets/ask/catalog.json). */
object Dates {
    /** ISO with offset, whole seconds: `2026-09-14T18:02:11+01:00`. */
    fun stamp(t: OffsetDateTime): String = t.format(STAMP)
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    /**
     * `YYYY-MM-DD` stays a day; an ISO time with offset is kept; an ISO time without
     * offset gets the device's current offset. Anything else is an error for the model.
     */
    fun normalise(s: String?, now: OffsetDateTime, field: String = "start"): String? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return null
        runCatching { LocalDate.parse(v) }.onSuccess { return v }
        runCatching { OffsetDateTime.parse(v) }.onSuccess { return stamp(it) }
        runCatching { LocalDateTime.parse(v) }.onSuccess { return stamp(it.atOffset(now.offset)) }
        throw ToolError("$field must be YYYY-MM-DD or an ISO 8601 time with offset, got \"$v\"")
    }

    fun isDay(s: String): Boolean = s.length == 10

    /** The calendar day of a normalised value, in the device's zone for a time. */
    fun day(s: String, now: OffsetDateTime): LocalDate =
        if (isDay(s)) LocalDate.parse(s) else OffsetDateTime.parse(s).atZoneSameInstant(now.offset).toLocalDate()
}

/** The app-wide stores, in device-protected storage like prefs (the app runs before user storage is unlocked). */
object Stores {
    @Volatile private var reminders: RemindersStore? = null
    @Volatile private var events: EventsStore? = null
    @Volatile private var timers: TimersStore? = null
    @Volatile private var photos: PhotosStore? = null
    @Volatile private var generated: GeneratedStore? = null
    @Volatile private var recordings: RecordingsStore? = null
    @Volatile private var battery: BatteryLog? = null
    @Volatile private var translations: TranslationsStore? = null
    @Volatile private var conversations: ConversationsStore? = null
    @Volatile private var ledger: Ledger? = null

    private fun file(ctx: Context, name: String) = File(ctx.createDeviceProtectedStorageContext().filesDir, name)

    fun reminders(ctx: Context): RemindersStore = reminders ?: synchronized(this) {
        reminders ?: RemindersStore(file(ctx, "reminders.json")).also { reminders = it }
    }

    fun events(ctx: Context): EventsStore = events ?: synchronized(this) {
        events ?: EventsStore(file(ctx, "events.json")).also { events = it }
    }

    fun timers(ctx: Context): TimersStore = timers ?: synchronized(this) {
        timers ?: TimersStore(file(ctx, "timers.json")).also { timers = it }
    }

    fun photos(ctx: Context): PhotosStore = photos ?: synchronized(this) {
        photos ?: PhotosStore(file(ctx, "photos")).also { photos = it }
    }

    fun generated(ctx: Context): GeneratedStore = generated ?: synchronized(this) {
        generated ?: GeneratedStore(file(ctx, "generated")).also { generated = it }
    }

    fun recordings(ctx: Context): RecordingsStore = recordings ?: synchronized(this) {
        recordings ?: RecordingsStore(file(ctx, "recordings")).also { recordings = it }
    }

    fun battery(ctx: Context): BatteryLog = battery ?: synchronized(this) {
        battery ?: BatteryLog(file(ctx, "battery.jsonl")).also { battery = it }
    }

    fun translations(ctx: Context): TranslationsStore = translations ?: synchronized(this) {
        translations ?: TranslationsStore(file(ctx, "translations.json")).also { translations = it }
    }

    fun conversations(ctx: Context): ConversationsStore = conversations ?: synchronized(this) {
        conversations ?: ConversationsStore(file(ctx, "conversations.json")).also { conversations = it }
    }

    /** The C1's count of its model calls (ask/Ledger.kt): one, for Core's calls and the service's pictures. */
    fun ledger(ctx: Context): Ledger = ledger ?: synchronized(this) {
        ledger ?: Ledger(file(ctx, "usage.jsonl")).also { ledger = it }
    }
}
