package com.bleural.croissant.data

import com.bleural.croissant.data.JsonStore.Companion.stringOrNull
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime

/**
 * One countdown. `endsAt` is an ISO time with offset; the label is its name, from voice or
 * typed on the page, null when it has none.
 * `startedAt` (2026-09-15, Rail step 7) is when it was set, so the screens can draw the
 * fraction remaining as a bar; a timer from an older file has none and its bar is full.
 */
data class Timer(
    val id: String,
    val label: String?,
    val endsAt: String,
    val color: String,
    val startedAt: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        if (label != null) put("label", label)
        put("endsAt", endsAt)
        put("color", color)
        if (startedAt != null) put("startedAt", startedAt)
    }

    /** The tool result: the item plus how long is left, never negative. */
    fun toJson(now: OffsetDateTime): JSONObject = toJson().put("secondsLeft", secondsLeft(now))

    fun end(): OffsetDateTime = OffsetDateTime.parse(endsAt)

    fun secondsLeft(now: OffsetDateTime): Long = Duration.between(now, end()).seconds.coerceAtLeast(0)

    companion object {
        fun fromJson(o: JSONObject) = Timer(
            id = o.getString("id"),
            label = o.stringOrNull("label"),
            endsAt = o.getString("endsAt"),
            color = o.optString("color", Palette.NAMES.first()),
            startedAt = o.stringOrNull("startedAt"),
        )
    }
}

/**
 * `timers.json`. A timer stays in the file after it goes off, until it is dismissed on
 * the screen; one that went off more than [STALE_MINUTES] ago without being dismissed
 * (the device was off) is dropped rather than rung late.
 */
class TimersStore(
    file: File,
    clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : JsonStore<Timer>(file, clock) {
    companion object {
        const val STALE_MINUTES = 10L
        const val LABEL_MAX = 60
        /** 99 h 59 m 59 s, what the add screen's columns can show. */
        const val MAX_SECONDS = 100 * 3600 - 1
    }

    init { load() }

    override fun encode(item: Timer) = item.toJson()
    override fun decode(o: JSONObject) = Timer.fromJson(o)
    override fun keep(item: Timer, now: OffsetDateTime): Boolean {
        val end = runCatching { item.end() }.getOrNull() ?: return false
        return end.isAfter(now.minusMinutes(STALE_MINUTES))
    }

    /** Soonest first. */
    fun running(): List<Timer> = list().sortedBy { it.endsAt }

    @Synchronized
    fun add(seconds: Int?, label: String? = null): Timer {
        if (seconds == null || seconds <= 0) throw ToolError("seconds must be a positive integer")
        if (seconds > MAX_SECONDS) throw ToolError("a timer can run for at most 99 hours")
        val now = clock()
        val t = Timer(
            id = newId("t"), label = cleanLabel(label),
            endsAt = Dates.stamp(now.plusSeconds(seconds.toLong())),
            color = Palette.after(list().lastOrNull()?.color),
            startedAt = Dates.stamp(now),
        )
        commit(list() + t)
        return t
    }

    /** A timer's name, typed on its page; blank leaves it unnamed. */
    @Synchronized
    fun rename(id: String?, label: String?): Timer {
        val t = find(id).copy(label = cleanLabel(label))
        commit(list().map { if (it.id == t.id) t else it })
        return t
    }

    /** Cancel a running timer or dismiss one that has gone off: either way it leaves the file. */
    @Synchronized
    fun cancel(id: String?) {
        val t = find(id)
        commit(list().filter { it.id != t.id })
    }

    private fun find(id: String?): Timer =
        list().firstOrNull { it.id == id } ?: throw ToolError("no timer ${id ?: ""}".trim())

    private fun cleanLabel(label: String?): String? =
        label?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }?.take(LABEL_MAX)
}

/**
 * What the alarm side needs from the list at one instant: the timers whose end has
 * passed (to ring) and the next end still ahead (to schedule one exact alarm for).
 * Pure, so the JVM tests cover it; the AlarmManager half is [com.bleural.croissant.TimerAlarms].
 */
object TimerPlan {
    /** An alarm may land a hair early or a fast tick a hair late; a quarter second either way is "now". */
    const val SLACK_MS = 250L

    data class Plan(val due: List<Timer>, val nextAt: Long?)

    fun at(timers: List<Timer>, nowMillis: Long): Plan {
        val ends = timers.mapNotNull { t -> runCatching { t.end().toInstant().toEpochMilli() }.getOrNull()?.let { t to it } }
        val due = ends.filter { it.second <= nowMillis + SLACK_MS }.map { it.first }
        val nextAt = ends.filter { it.second > nowMillis + SLACK_MS }.minOfOrNull { it.second }
        return Plan(due, nextAt)
    }
}
