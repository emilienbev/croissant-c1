package com.bleural.croissant.ui

import com.bleural.croissant.data.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class AgendaTest {
    // Monday 14 September 2026, 16:45 BST.
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-14T16:45:00+01:00")
    private fun ev(id: String, title: String, start: String, end: String, allDay: Boolean) =
        Event(id, title, start, end, allDay, null, null, "orange")

    private val events = listOf(
        ev("1", "Gone", "2026-09-13", "2026-09-13", true),
        ev("2", "Dentist", "2026-09-16T14:00:00+01:00", "2026-09-16T15:00:00+01:00", false),
        ev("3", "Fair", "2026-09-16", "2026-09-16", true),
        ev("4", "Trip", "2026-09-13", "2026-09-15", true),
        ev("5", "Early", "2026-09-14T09:00:00+01:00", "2026-09-14T09:30:00+01:00", false),
        ev("6", "Night", "2026-09-20T22:00:00+01:00", "2026-09-21T02:00:00+01:00", false),
    )

    @Test fun groupsByDayFromToday() {
        val lines = agenda(events, now).map {
            when (it) { is AgendaItem.Day -> "# ${it.label}"; is AgendaItem.Row -> "${it.event.title} ${it.time}" }
        }
        assertEquals(listOf(
            "# Today", "Trip All day", "Early 9:00",
            "# Tomorrow", "Trip All day",
            "# Wed 16 Sept", "Fair All day", "Dentist 14:00",
            "# Sun 20 Sept", "Night 22:00",
            "# Mon 21 Sept", "Night Until 2:00",
        ), lines)
    }

    @Test fun whenLines() {
        assertEquals("Wed 16 Sept, 14:00 to 15:00", whenText(events[1], now))
        assertEquals("Wed 16 Sept, all day", whenText(events[2], now))
        assertEquals("Sun 13 Sept to Tomorrow", whenText(events[3], now))
        assertEquals("Sun 20 Sept 22:00 to Mon 21 Sept 2:00", whenText(events[5], now))
        assertEquals("Today, 9:00 am to 9:30 am", whenText(events[4], now, twentyFour = false))
    }
}
