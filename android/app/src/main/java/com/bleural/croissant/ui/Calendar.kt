package com.bleural.croissant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bleural.croissant.data.Event
import com.bleural.croissant.data.endDay
import com.bleural.croissant.data.startDay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The agenda is a flat list: a day header, then that day's events. */
sealed interface AgendaItem {
    data class Day(val date: LocalDate, val label: String) : AgendaItem
    data class Row(val event: Event, val date: LocalDate, val time: String) : AgendaItem
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(DAY)
}

fun clock(t: OffsetDateTime, twentyFour: Boolean): String =
    t.format(DateTimeFormatter.ofPattern(if (twentyFour) "H:mm" else "h:mm a", Locale.UK))

/**
 * Only the days that have something, from today on: an event that ended before today
 * is gone. A multi-day event appears on each of its days; all-day rows come first in
 * a day, then timed ones by start.
 */
fun agenda(events: List<Event>, now: OffsetDateTime, twentyFour: Boolean = true): List<AgendaItem> {
    val today = now.toLocalDate()
    val rows = mutableListOf<AgendaItem.Row>()
    for (e in events) {
        val first = runCatching { e.startDay(now) }.getOrNull() ?: continue
        val last = runCatching { e.endDay(now) }.getOrNull() ?: first
        if (last.isBefore(today)) continue
        var d = maxOf(first, today)
        while (!d.isAfter(last)) {
            val time = when {
                e.allDay -> "All day"
                d == first && d == last -> clock(OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset), twentyFour)
                d == first -> clock(OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset), twentyFour)
                d == last -> "Until " + clock(OffsetDateTime.parse(e.end).withOffsetSameInstant(now.offset), twentyFour)
                else -> "All day"
            }
            rows += AgendaItem.Row(e, d, time)
            d = d.plusDays(1)
        }
    }
    val sorted = rows.sortedWith(compareBy({ it.date }, { !it.event.allDay }, { it.event.start }))
    val out = mutableListOf<AgendaItem>()
    var current: LocalDate? = null
    for (r in sorted) {
        if (r.date != current) { current = r.date; out += AgendaItem.Day(r.date, dayLabel(r.date, today)) }
        out += r
    }
    return out
}

/** When an event happens, in words: "Tue 16 Sept, 14:00 to 15:00", "Sat 20 Sept to Mon 22 Sept", "Tomorrow, all day". */
fun whenText(e: Event, now: OffsetDateTime, twentyFour: Boolean = true): String {
    val today = now.toLocalDate()
    val a = e.startDay(now); val b = e.endDay(now)
    if (e.allDay) return if (a == b) "${dayLabel(a, today)}, all day" else "${dayLabel(a, today)} to ${dayLabel(b, today)}"
    val s = OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset)
    val t = OffsetDateTime.parse(e.end).withOffsetSameInstant(now.offset)
    return if (a == b) "${dayLabel(a, today)}, ${clock(s, twentyFour)} to ${clock(t, twentyFour)}"
    else "${dayLabel(a, today)} ${clock(s, twentyFour)} to ${dayLabel(b, today)} ${clock(t, twentyFour)}"
}
