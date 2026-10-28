package com.bleural.croissant.data

import com.bleural.croissant.data.JsonStore.Companion.stringOrNull
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * One calendar event. `start` and `end` are both `YYYY-MM-DD` (all-day, end inclusive)
 * or both ISO times with offset. No recurrence: "every Tuesday" is several events.
 */
data class Event(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    val color: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("start", start)
        put("end", end)
        put("allDay", allDay)
        if (location != null) put("location", location)
        if (notes != null) put("notes", notes)
        put("color", color)
    }

    companion object {
        fun fromJson(o: JSONObject) = Event(
            id = o.getString("id"),
            title = o.getString("title"),
            start = o.getString("start"),
            end = o.optString("end", o.getString("start")),
            allDay = o.optBoolean("allDay", Dates.isDay(o.getString("start"))),
            location = o.stringOrNull("location"),
            notes = o.stringOrNull("notes"),
            color = o.optString("color", Palette.NAMES.first()),
        )
    }
}

/**
 * `events.json`. Events are dropped [KEEP_PAST_DAYS] after they end. An all-day event
 * is stored as days; a timed one as times, its end defaulting to an hour after the
 * start. Mixed forms are normalised: a time with `allDay` becomes its day, a day with
 * a timed end becomes all-day through that day.
 */
class EventsStore(
    file: File,
    clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : JsonStore<Event>(file, clock) {
    companion object {
        const val KEEP_PAST_DAYS = 90L
        const val TITLE_MAX = 80
    }

    init { load() }

    override fun encode(item: Event) = item.toJson()
    override fun decode(o: JSONObject) = Event.fromJson(o)
    override fun keep(item: Event, now: OffsetDateTime): Boolean {
        val end = runCatching { Dates.day(item.end, now) }.getOrNull() ?: return true
        return end.isAfter(now.toLocalDate().minusDays(KEEP_PAST_DAYS))
    }

    /** Events overlapping the days [from]..[to] (either form of date), by start. */
    fun between(from: String?, to: String?): List<Event> {
        val now = clock()
        val lo = Dates.normalise(from, now, "from") ?: throw ToolError("from is required")
        val hi = Dates.normalise(to, now, "to") ?: throw ToolError("to is required")
        val a = Dates.day(lo, now); val b = Dates.day(hi, now)
        return list().filter { e -> !Dates.day(e.end, now).isBefore(a) && !Dates.day(e.start, now).isAfter(b) }
            .sortedWith(compareBy({ Dates.day(it.start, now) }, { it.allDay.not() }, { it.start }))
    }

    @Synchronized
    fun add(title: String?, start: String?, end: String? = null, allDay: Boolean? = null,
            location: String? = null, notes: String? = null): Event {
        val t = cleanTitle(title)
        val s = Dates.normalise(start, clock(), "start") ?: throw ToolError("start is required")
        val (st, en, whole) = span(s, Dates.normalise(end, clock(), "end"), allDay)
        val e = Event(
            id = newId("e"), title = t, start = st, end = en, allDay = whole,
            location = location?.trim()?.ifEmpty { null }, notes = notes?.trim()?.ifEmpty { null },
            color = Palette.after(list().lastOrNull()?.color),
        )
        commit(list() + e)
        return e
    }

    /** Null means "leave as is"; a new start without a new end keeps the old duration. */
    @Synchronized
    fun update(id: String?, title: String? = null, start: String? = null, end: String? = null,
               allDay: Boolean? = null, location: String? = null, notes: String? = null): Event {
        val old = find(id)
        var e = old
        if (title != null) e = e.copy(title = cleanTitle(title))
        if (start != null || end != null || allDay != null) {
            val now = clock()
            val s = Dates.normalise(start, now, "start") ?: old.start
            val newEnd = Dates.normalise(end, now, "end") ?: if (start != null && !old.allDay && !Dates.isDay(s)) {
                // Keep the duration when only the start moves.
                val d = java.time.Duration.between(OffsetDateTime.parse(old.start), OffsetDateTime.parse(old.end))
                Dates.stamp(OffsetDateTime.parse(s).plus(d))
            } else if (start != null) null else old.end
            val (st, en, whole) = span(s, newEnd, allDay ?: if (start != null || end != null) null else old.allDay)
            e = e.copy(start = st, end = en, allDay = whole)
        }
        if (location != null) e = e.copy(location = location.trim().ifEmpty { null })
        if (notes != null) e = e.copy(notes = notes.trim().ifEmpty { null })
        commit(list().map { if (it.id == old.id) e else it })
        return e
    }

    @Synchronized
    fun delete(id: String?) {
        val e = find(id)
        commit(list().filter { it.id != e.id })
    }

    private fun find(id: String?): Event =
        list().firstOrNull { it.id == id } ?: throw ToolError("no event ${id ?: ""}".trim())

    private fun cleanTitle(title: String?): String {
        val t = title?.trim()?.replace(Regex("\\s+"), " ") ?: ""
        if (t.isEmpty()) throw ToolError("title is required")
        return t.take(TITLE_MAX)
    }

    /** Resolves start, end and allDay into one consistent form. */
    private fun span(start: String, end: String?, allDay: Boolean?): Triple<String, String, Boolean> {
        val now = clock()
        val whole = allDay ?: (Dates.isDay(start) && (end == null || Dates.isDay(end)))
        if (whole) {
            val a = Dates.day(start, now)
            val b = end?.let { Dates.day(it, now) } ?: a
            if (b.isBefore(a)) throw ToolError("end is before start")
            return Triple(a.toString(), b.toString(), true)
        }
        val a = if (Dates.isDay(start)) throw ToolError("a timed event needs a start time, not just a day") else OffsetDateTime.parse(start)
        val b = when {
            end == null -> a.plusHours(1)
            Dates.isDay(end) -> throw ToolError("end must be a time when start is a time")
            else -> OffsetDateTime.parse(end)
        }
        if (b.isBefore(a)) throw ToolError("end is before start")
        return Triple(Dates.stamp(a), Dates.stamp(b), false)
    }
}

/** Day helpers for screens. */
fun Event.startDay(now: OffsetDateTime): LocalDate = Dates.day(start, now)
fun Event.endDay(now: OffsetDateTime): LocalDate = Dates.day(end, now)
