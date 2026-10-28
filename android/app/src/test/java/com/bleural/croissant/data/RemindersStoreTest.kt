package com.bleural.croissant.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

class RemindersStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now: OffsetDateTime = OffsetDateTime.parse("2026-09-14T18:00:00+01:00")
    private fun file() = File(tmp.root, "reminders.json")
    private fun store() = RemindersStore(file()) { now }

    @Test fun addWritesTheFileAndReloads() {
        val s = store()
        val r = s.add("Buy avocados")
        assertEquals("Buy avocados", r.title)
        assertEquals("orange", r.color)
        assertEquals("2026-09-14T18:00:00+01:00", r.createdAt)
        assertFalse(File(tmp.root, "reminders.json.tmp").exists())
        assertEquals(1, JSONArray(file().readText()).length())
        assertEquals(listOf(r), store().list())
    }

    @Test fun coloursRoundRobinFromTheLastItem() {
        val s = store()
        val colours = (1..9).map { s.add("r$it").color }
        assertEquals(Palette.NAMES + "orange", colours)
        s.delete(s.list().last().id)          // last is now "yellow" again
        assertEquals("orange", s.add("again").color)
    }

    @Test fun capIsFifty() {
        val s = store()
        repeat(50) { s.add("r$it") }
        try { s.add("one more"); fail() } catch (e: ToolError) { assertTrue(e.message!!.contains("full")) }
    }

    @Test fun titleIsRequiredAndTrimmed() {
        val s = store()
        try { s.add("   "); fail() } catch (e: ToolError) { assertEquals("title is required", e.message) }
        assertEquals("a b", s.add("  a \n b ").title)
        assertEquals(60, s.add("x".repeat(80)).title.length)
    }

    @Test fun updateAndDone() {
        val s = store()
        val r = s.add("a")
        val u = s.update(r.id, title = "b", done = true)
        assertEquals("b", u.title)
        assertTrue(u.done); assertEquals("2026-09-14T18:00:00+01:00", u.doneAt)
        val back = s.update(r.id, done = false)
        assertFalse(back.done); assertNull(back.doneAt)
        assertEquals("b", back.title)
        try { s.update("r_nope", title = "x"); fail() } catch (e: ToolError) { assertEquals("no reminder r_nope", e.message) }
        try { s.delete(null); fail() } catch (e: ToolError) { assertEquals("no reminder", e.message) }
    }

    @Test fun doneItemsPurgeAfterSevenDays() {
        val s = store()
        val old = s.add("old"); s.update(old.id, done = true)
        now = now.plusDays(3)
        val recent = s.add("recent"); s.update(recent.id, done = true)
        s.add("open")
        now = now.plusDays(5)                   // old done 8 days ago, recent 5
        val reloaded = store()
        assertEquals(listOf("recent", "open"), reloaded.list().map { it.title })
        assertEquals(2, JSONArray(file().readText()).length())
    }

    @Test fun corruptFileStartsEmpty() {
        file().writeText("{not json")
        assertEquals(emptyList<Reminder>(), store().list())
    }
}
