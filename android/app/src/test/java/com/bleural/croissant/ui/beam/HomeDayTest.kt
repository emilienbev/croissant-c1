package com.bleural.croissant.ui.beam

import com.bleural.croissant.data.Event
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.Timer
import com.bleural.croissant.ui.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Home's day: what it picks from the day, and where its groups sit. */
class HomeDayTest {
    // Wednesday 23 September 2026, 13:22 BST, a busy day.
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-23T13:22:00+01:00")
    private fun at(hm: String) = "2026-09-23T$hm:00+01:00"
    private fun ev(title: String, start: String, end: String) = Event(title, title, start, end, false, null, null, "blue")
    private fun todo(title: String, created: String, done: Boolean = false) = Reminder(title, title, "red", done, null, created)
    private fun timer(label: String?, started: String, ends: String) = Timer(label ?: "t", label, ends, "yellow", started)

    private val busy = dayOf(
        listOf(ev("Dentist", at("09:15"), at("10:00")), ev("Gym", at("17:30"), at("18:30")), ev("Call with Anna", at("14:00"), at("14:45")), ev("Dinner", at("20:00"), at("21:00"))),
        listOf(todo("Milk", at("08:00")), todo("Call the bank", at("09:00")), todo("Plants", at("10:00")), todo("Old", at("07:00"), done = true)),
        listOf(
            timer("oven", at("12:22"), at("14:24")),
            timer("pasta", at("13:12"), at("13:26")),
            timer("laundry", at("13:00"), at("13:34")),
            timer("tea", at("13:20"), at("13:25")),
            timer(null, at("13:00"), at("13:20")),                                        // gone off
        ),
        now, twentyFour = true,
    )

    @Test fun ringsTheSoonestThreeTimers() {
        assertEquals(listOf("tea", "pasta", "laundry"), busy.timers.map { it.name })
        assertEquals(4, busy.timerCount)
        assertEquals("4:00", busy.pasta().left)
        assertEquals(4f / 14f, busy.pasta().fraction, 0.01f)                             // 4 of its 14 minutes left
    }

    @Test fun eventsStillAheadAndTheNewestToDos() {
        assertEquals(listOf("14:00", "17:30"), busy.events.map { it.time })
        assertEquals(3, busy.eventCount)                                                   // the dentist is over
        assertEquals(listOf("Plants", "Call the bank"), busy.todo)
        assertEquals(3, busy.todoCount)
    }

    @Test fun groupsSitWhereTheDrawingHasThem() {
        val g = dayGroups(busy)
        assertEquals(listOf(Feature.Timers, Feature.Calendar, Feature.Reminders), g.map { it.feature })
        assertEquals(listOf(176f, 392f, 526f), g.map { it.head })
        g.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top, 0.001f) }    // bands meet, so every tap lands somewhere
        assertTrue(g.last().bottom <= 640f)
    }

    @Test fun aQuietEveningIsItsOneEvent() {
        val quiet = dayOf(listOf(ev("Dinner with Léa", "2026-09-23T21:00:00+01:00", "2026-09-23T22:30:00+01:00")), emptyList(), emptyList(),
            OffsetDateTime.parse("2026-09-23T19:38:00+01:00"), twentyFour = true)
        val g = dayGroups(quiet)
        assertEquals(listOf(Feature.Calendar), g.map { it.feature })
        assertEquals(176f, g.single().head)
    }

    private fun Day.pasta() = timers.first { it.name == "pasta" }
}
