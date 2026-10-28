package com.bleural.croissant.ui

import com.bleural.croissant.data.Event
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.Timer
import java.time.OffsetDateTime

/*
 * What is live today, picked for a Home that shows it without opening each app and for the
 * Today page. Selection only: how it looks is a
 * design's business. All-day events are the whole day and say nothing here, as in
 * [todaySpans]; reminders have no time, so they are a list, never a moment.
 */

/** A timed event starting today, its start and end at the device's offset. */
data class TimedEvent(val title: String, val start: OffsetDateTime, val end: OffsetDateTime)

/**
 * Home's flags: the open reminders' titles, newest first (a count on Home, two rows on
 * Today), the soonest running timer, and the next timed event today not yet ended (one
 * under way counts, as on the day's scale).
 */
data class Glance(val todo: List<String>, val timer: Timer?, val event: TimedEvent?)

fun glance(events: List<Event>, reminders: List<Reminder>, timers: List<Timer>, now: OffsetDateTime): Glance {
    val nowMs = now.toInstant().toEpochMilli()
    return Glance(
        todo = reminders.filterNot { it.done }.sortedByDescending { it.createdAt }.map { it.title },
        timer = timers.filter { it.millisLeft(nowMs) > 0 }.minByOrNull { it.millisLeft(nowMs) },
        event = timedToday(events, now).firstOrNull(),
    )
}

/**
 * One thing on Today's scale: [minutes] from now to its moment (a timer's ring, an event's
 * start) and, for an event, [endMinutes] to its end. [timer] is set for a timer, [start]
 * for an event.
 */
data class TodayItem(
    val minutes: Float,
    val endMinutes: Float?,
    val title: String,
    val timer: Timer? = null,
    val start: OffsetDateTime? = null,
)

/**
 * The rest of today, soonest first: running timers that ring before midnight and timed
 * events today still to start. One already under way is not ahead of now, so it has no
 * moment on the scale.
 */
fun todayItems(events: List<Event>, timers: List<Timer>, now: OffsetDateTime): List<TodayItem> {
    val nowMs = now.toInstant().toEpochMilli()
    val left = minutesBetween(now, now.toLocalDate().plusDays(1).atStartOfDay().atOffset(now.offset))
    val items = ArrayList<TodayItem>()
    for (t in timers) {
        val m = t.millisLeft(nowMs) / 60_000f
        if (m > 0f && m <= left) items += TodayItem(m, null, t.label ?: "Timer", timer = t)
    }
    for (e in timedToday(events, now)) {
        if (!e.start.isAfter(now)) continue
        items += TodayItem(minutesBetween(now, e.start), minutesBetween(now, e.end), e.title, start = e.start)
    }
    return items.sortedBy { it.minutes }
}

/** One of today's timed events on a rail of the day: hours of the day (0 to 24, clipped to today), and whether it is over. */
data class DaySpan(val from: Float, val to: Float, val over: Boolean)

/**
 * Every timed event on today, over or not, by start: what a rail of the whole day marks
 * ([todaySpans] leaves out the ones that have ended).
 */
fun daySpans(events: List<Event>, now: OffsetDateTime): List<DaySpan> {
    val today = now.toLocalDate()
    fun hours(t: OffsetDateTime) = when {
        t.toLocalDate().isBefore(today) -> 0f
        t.toLocalDate().isAfter(today) -> 24f
        else -> t.hour + t.minute / 60f
    }
    return events.mapNotNull { e ->
        if (e.allDay) return@mapNotNull null
        val s = runCatching { OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset) }.getOrNull() ?: return@mapNotNull null
        val t = runCatching { OffsetDateTime.parse(e.end).withOffsetSameInstant(now.offset) }.getOrNull() ?: s
        if (s.toLocalDate().isAfter(today) || t.toLocalDate().isBefore(today)) null
        else DaySpan(hours(s), maxOf(hours(t), hours(s)), !t.isAfter(now))
    }.sortedBy { it.from }
}

/** Minutes from [a] to [b], fractional. */
fun minutesBetween(a: OffsetDateTime, b: OffsetDateTime): Float =
    (b.toInstant().toEpochMilli() - a.toInstant().toEpochMilli()) / 60_000f

/** Timed events starting today that have not ended, by start. */
private fun timedToday(events: List<Event>, now: OffsetDateTime): List<TimedEvent> {
    val today = now.toLocalDate()
    return events.mapNotNull { e ->
        if (e.allDay) return@mapNotNull null
        val s = runCatching { OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset) }.getOrNull() ?: return@mapNotNull null
        val t = runCatching { OffsetDateTime.parse(e.end).withOffsetSameInstant(now.offset) }.getOrNull() ?: s
        if (s.toLocalDate() == today && t.isAfter(now)) TimedEvent(e.title, s, t) else null
    }.sortedBy { it.start }
}
