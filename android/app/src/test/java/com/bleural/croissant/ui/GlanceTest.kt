package com.bleural.croissant.ui

import com.bleural.croissant.data.Event
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class GlanceTest {
    // Wednesday 23 September 2026, 13:22 BST, a busy day.
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-23T13:22:00+01:00")
    private fun ev(title: String, start: String, end: String, allDay: Boolean = false) =
        Event(title, title, start, end, allDay, null, null, "blue")
    private fun at(hm: String) = "2026-09-23T$hm:00+01:00"
    private fun todo(title: String, created: String, done: Boolean = false) =
        Reminder(title, title, "red", done, null, created)
    private fun timer(label: String?, ends: String) = Timer(label ?: "t", label, ends, "yellow")

    private val events = listOf(
        ev("Dentist", at("09:15"), at("10:00")),                                  // over
        ev("Gym", at("17:30"), at("18:30")),
        ev("Call with Anna", at("14:00"), at("14:45")),
        ev("Fair", "2026-09-23", "2026-09-23", allDay = true),                   // all day: nothing
        ev("Tomorrow", "2026-09-24T10:00:00+01:00", "2026-09-24T11:00:00+01:00"),
    )

    @Test fun glancePicksTheNextOfEach() {
        val reminders = listOf(
            todo("Milk", "2026-09-21T08:00:00+01:00"),
            todo("Call the bank", "2026-09-23T08:00:00+01:00"),
            todo("Old", "2026-09-20T08:00:00+01:00", done = true),
            todo("Plants", "2026-09-22T08:00:00+01:00"),
        )
        val timers = listOf(
            timer("laundry", "2026-09-23T13:34:05+01:00"),
            timer("pasta", "2026-09-23T13:26:32+01:00"),
            timer("rang", "2026-09-23T13:20:00+01:00"),                              // gone off, not running
        )
        val g = glance(events, reminders, timers, now)
        assertEquals(listOf("Call the bank", "Plants", "Milk"), g.todo)
        assertEquals("pasta", g.timer?.label)
        assertEquals("Call with Anna", g.event?.title)
        assertEquals(14, g.event?.start?.hour)
    }

    @Test fun anEventUnderWayIsTheNext() {
        val g = glance(listOf(ev("Lunch", at("13:00"), at("14:00")), ev("Gym", at("17:30"), at("18:30"))), emptyList(), emptyList(), now)
        assertEquals("Lunch", g.event?.title)
    }

    @Test fun nothingToSay() {
        val g = glance(listOf(ev("Fair", "2026-09-23", "2026-09-23", allDay = true)), listOf(todo("Old", "2026-09-20T08:00:00+01:00", done = true)), emptyList(), now)
        assertEquals(emptyList<String>(), g.todo)
        assertNull(g.timer)
        assertNull(g.event)
    }

    @Test fun todayByMoment() {
        val timers = listOf(
            timer("pasta", "2026-09-23T13:26:32+01:00"),
            timer("oven", "2026-09-23T14:24:40+01:00"),
            timer(null, "2026-09-24T02:00:00+01:00"),                                // rings after midnight
        )
        val items = todayItems(events + ev("Lunch", at("13:00"), at("14:00")), timers, now)
        assertEquals(listOf("pasta", "Call with Anna", "oven", "Gym"), items.map { it.title })
        val call = items[1]
        assertEquals(38f, call.minutes, 0.01f)
        assertEquals(83f, call.endMinutes!!, 0.01f)
        assertEquals(4.53f, items[0].minutes, 0.01f)
        assertNull(items[0].endMinutes)
    }

    @Test fun anUnlabelledTimerIsATimer() {
        val items = todayItems(emptyList(), listOf(timer(null, "2026-09-23T13:30:00+01:00")), now)
        assertEquals("Timer", items.single().title)
    }
}
