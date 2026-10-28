package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

class EventsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now: OffsetDateTime = OffsetDateTime.parse("2026-09-14T18:00:00+01:00")
    private fun store() = EventsStore(File(tmp.root, "events.json")) { now }

    @Test fun timedEndDefaultsToAnHour() {
        val e = store().add("Dentist", "2026-09-15T13:00:00+01:00")
        assertEquals("2026-09-15T13:00:00+01:00", e.start)
        assertEquals("2026-09-15T14:00:00+01:00", e.end)
        assertFalse(e.allDay)
        assertEquals("orange", e.color)
    }

    @Test fun dayStartIsAllDay() {
        val s = store()
        val e = s.add("Holiday", "2026-09-20", "2026-09-22")
        assertTrue(e.allDay); assertEquals("2026-09-20", e.start); assertEquals("2026-09-22", e.end)
        val one = s.add("Birthday", "2026-10-01")
        assertTrue(one.allDay); assertEquals("2026-10-01", one.end)
        assertEquals("lavender", one.color)
        val forced = s.add("Trip", "2026-09-25T09:00:00+01:00", allDay = true)
        assertTrue(forced.allDay); assertEquals("2026-09-25", forced.start)
    }

    @Test fun errors() {
        val s = store()
        try { s.add("x", "2026-09-15T13:00:00+01:00", "2026-09-15T12:00:00+01:00"); fail() } catch (e: ToolError) { assertEquals("end is before start", e.message) }
        try { s.add("x", null); fail() } catch (e: ToolError) { assertEquals("start is required", e.message) }
        try { s.add("x", "tomorrow"); fail() } catch (e: ToolError) { assertTrue(e.message!!.startsWith("start must be")) }
        try { s.update("e_nope", title = "y"); fail() } catch (e: ToolError) { assertEquals("no event e_nope", e.message) }
    }

    @Test fun betweenOverlapsDays() {
        val s = store()
        val a = s.add("A", "2026-09-15T13:00:00+01:00")
        val b = s.add("B", "2026-09-20", "2026-09-22")
        s.add("C", "2026-10-05")
        assertEquals(listOf("A"), s.between("2026-09-14", "2026-09-16").map { it.title })
        assertEquals(listOf("A", "B"), s.between("2026-09-15T00:00:00+01:00", "2026-09-21").map { it.title })
        assertEquals(listOf("B"), s.between("2026-09-22", "2026-09-22").map { it.title })
        assertEquals(listOf(a.id, b.id), s.between("2026-09-01", "2026-09-30").map { it.id })
    }

    @Test fun updateKeepsDurationWhenStartMoves() {
        val s = store()
        val e = s.add("Call", "2026-09-15T13:00:00+01:00", "2026-09-15T13:30:00+01:00")
        val moved = s.update(e.id, start = "2026-09-16T10:00:00+01:00")
        assertEquals("2026-09-16T10:00:00+01:00", moved.start)
        assertEquals("2026-09-16T10:30:00+01:00", moved.end)
        val located = s.update(e.id, location = " Home ", notes = "")
        assertEquals("Home", located.location); assertEquals(null, located.notes)
        assertEquals("2026-09-16T10:00:00+01:00", located.start)
    }

    @Test fun oldEventsPurge() {
        val s = store()
        s.add("Old", "2026-05-01")
        s.add("Recent", "2026-08-01")
        s.add("Future", "2026-12-01")
        assertEquals(listOf("Recent", "Future"), store().list().map { it.title })
        s.delete(s.list().first().id)
        assertEquals(listOf("Future"), s.list().map { it.title })
    }
}
