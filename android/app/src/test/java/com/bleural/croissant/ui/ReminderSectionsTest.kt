package com.bleural.croissant.ui

import com.bleural.croissant.data.Reminder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class ReminderSectionsTest {
    // Tuesday 15 September 2026, 13:09 BST.
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-15T13:09:00+01:00")
    private fun rem(id: String, title: String, created: String, done: Boolean = false, doneAt: String? = null) =
        Reminder(id, title, "red", done, doneAt, created)

    private fun lines(items: List<Reminder>) = reminderSections(items, now).flatMap { s ->
        listOf("# ${s.label}") + s.rows.map { it.reminder.title }
    }

    @Test fun underTheDayAddedNewestFirst() {
        val items = listOf(
            rem("a", "Call mum", "2026-09-15T09:00:00+01:00"),
            rem("b", "Bins", "2026-09-14T22:30:00+01:00"),
            rem("c", "Parcel", "2026-09-15T11:00:00+01:00"),
            rem("d", "Old", "2026-09-01T10:00:00+01:00"),
            rem("e", "Batteries", "2026-09-14T08:00:00+01:00"),
            rem("f", "Water the plants", "2026-09-10T10:00:00+01:00", done = true, doneAt = "2026-09-14T09:00:00+01:00"),
            rem("g", "Done later", "2026-09-12T10:00:00+01:00", done = true, doneAt = "2026-09-15T09:00:00+01:00"),
            rem("h", "Late night, UTC", "2026-09-14T23:30:00+00:00"),   // 00:30 today on the device
        )
        assertEquals(listOf(
            "# Today", "Parcel", "Call mum", "Late night, UTC",
            "# Yesterday", "Bins", "Batteries",
            "# Tue 1 Sept", "Old",
            "# Done", "Done later", "Water the plants",
        ), lines(items))
    }

    @Test fun emptySectionsAreLeftOut() {
        assertEquals(listOf("# Today", "Only"), lines(listOf(rem("a", "Only", "2026-09-15T12:00:00+01:00"))))
        assertEquals(emptyList<String>(), lines(emptyList()))
    }

    @Test fun unparseableCreatedFilesUnderEarlier() {
        assertEquals(listOf("# Earlier", "Odd"), lines(listOf(rem("a", "Odd", ""))))
    }
}
