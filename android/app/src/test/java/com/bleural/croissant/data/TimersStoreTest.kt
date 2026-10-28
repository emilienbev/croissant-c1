package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
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

class TimersStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now: OffsetDateTime = OffsetDateTime.parse("2026-09-14T18:00:00+01:00")
    private fun file() = File(tmp.root, "timers.json")
    private fun store() = TimersStore(file()) { now }

    @Test fun addWritesTheFileAndReloads() {
        val s = store()
        val t = s.add(120, "tea")
        assertEquals("tea", t.label)
        assertEquals("2026-09-14T18:02:00+01:00", t.endsAt)
        assertEquals("orange", t.color)
        assertEquals(120L, t.secondsLeft(now))
        assertFalse(File(tmp.root, "timers.json.tmp").exists())
        assertEquals(1, JSONArray(file().readText()).length())
        assertEquals(listOf(t), store().list())
    }

    @Test fun secondsMustBePositiveAndBounded() {
        val s = store()
        for (bad in listOf<Int?>(null, 0, -5)) {
            try { s.add(bad); fail() } catch (e: ToolError) { assertEquals("seconds must be a positive integer", e.message) }
        }
        try { s.add(100 * 3600); fail() } catch (e: ToolError) { assertTrue(e.message!!.contains("99 hours")) }
        assertEquals(TimersStore.MAX_SECONDS.toLong(), s.add(TimersStore.MAX_SECONDS).secondsLeft(now))
    }

    @Test fun labelIsOptionalAndCleaned() {
        val s = store()
        assertNull(s.add(5).label)
        assertNull(s.add(5, "  ").label)
        assertEquals("green tea", s.add(5, " green \n tea ").label)
        assertEquals(60, s.add(5, "x".repeat(80)).label!!.length)
        assertFalse(JSONArray(file().readText()).getJSONObject(0).has("label"))
    }

    @Test fun runningIsSoonestFirstAndSecondsLeftNeverNegative() {
        val s = store()
        val long = s.add(600)
        val short = s.add(60)
        assertEquals(listOf(short.id, long.id), s.running().map { it.id })
        now = now.plusSeconds(90)
        assertEquals(0L, short.secondsLeft(now))
        assertEquals(510L, long.secondsLeft(now))
        assertEquals(listOf(short.id, long.id), store().running().map { it.id })   // gone off, not dismissed: kept
    }

    @Test fun cancelRemovesAndUnknownIdIsAnError() {
        val s = store()
        val t = s.add(30)
        s.cancel(t.id)
        assertTrue(s.list().isEmpty())
        try { s.cancel(t.id); fail() } catch (e: ToolError) { assertEquals("no timer ${t.id}", e.message) }
        try { s.cancel(null); fail() } catch (e: ToolError) { assertEquals("no timer", e.message) }
    }

    @Test fun renameKeepsTheTimeAndBlankLeavesItUnnamed() {
        val s = store()
        val t = s.add(120)
        s.add(60, "eggs")
        val named = s.rename(t.id, " green \n tea ")
        assertEquals("green tea", named.label)
        assertEquals(t.copy(label = "green tea"), named)
        assertEquals(listOf("green tea", "eggs"), store().list().map { it.label })
        assertNull(s.rename(t.id, "  ").label)
        assertFalse(JSONArray(file().readText()).getJSONObject(0).has("label"))
        try { s.rename("t_nope", "x"); fail() } catch (e: ToolError) { assertEquals("no timer t_nope", e.message) }
    }

    @Test fun coloursRoundRobinFromTheLastItem() {
        val s = store()
        assertEquals(Palette.NAMES + "orange", (1..9).map { s.add(10).color })
    }

    @Test fun staleTimersDropOnLoad() {
        val s = store()
        s.add(60)                      // ends 18:01
        s.add(3600)                    // ends 19:00
        now = now.plusMinutes(12)      // 18:12: the first went off 11 minutes ago
        val reloaded = store()
        assertEquals(1, reloaded.list().size)
        assertEquals(1, JSONArray(file().readText()).length())
    }

    @Test fun corruptFileStartsEmpty() {
        file().writeText("[{\"id\": 1")
        assertEquals(emptyList<Timer>(), store().list())
    }

    // ---- through the executor: what the model's arguments look like on the wire ----

    private fun executor() = ToolExecutor(
        RemindersStore(File(tmp.root, "reminders.json")) { now },
        EventsStore(File(tmp.root, "events.json")) { now },
        store(),
    ) { now }

    @Test fun executorParsesSecondsLeniently() {
        val x = executor()
        val a = x.run("timers_add", JSONObject("""{"seconds": 120, "label": "tea"}""")) as JSONObject
        assertEquals(120, a.getInt("secondsLeft")); assertEquals("tea", a.getString("label"))
        val b = x.run("timers_add", JSONObject("""{"seconds": "90"}""")) as JSONObject
        assertEquals(90, b.getInt("secondsLeft")); assertFalse(b.has("label"))
        val c = x.run("timers_add", JSONObject("""{"seconds": 45.0}""")) as JSONObject
        assertEquals(45, c.getInt("secondsLeft"))
        try { x.run("timers_add", JSONObject("""{"seconds": "two minutes"}""")); fail() } catch (e: ToolError) { assertTrue(e.message!!.startsWith("seconds must be a whole number")) }
        try { x.run("timers_add", JSONObject("""{"seconds": 1.5}""")); fail() } catch (e: ToolError) { assertTrue(e.message!!.startsWith("seconds must be a whole number")) }
        try { x.run("timers_add", JSONObject("{}")); fail() } catch (e: ToolError) { assertEquals("seconds must be a positive integer", e.message) }
    }

    @Test fun executorListsAndCancels() {
        val x = executor()
        val a = x.run("timers_add", JSONObject("""{"seconds": 300}""")) as JSONObject
        x.run("timers_add", JSONObject("""{"seconds": 30}"""))
        now = now.plusSeconds(10)
        val list = x.run("timers_list", JSONObject()) as JSONArray
        assertEquals(2, list.length())
        assertEquals(20, list.getJSONObject(0).getInt("secondsLeft"))
        assertEquals(290, list.getJSONObject(1).getInt("secondsLeft"))
        val ok = x.run("timers_cancel", JSONObject().put("id", a.getString("id"))) as JSONObject
        assertTrue(ok.getBoolean("ok"))
        assertEquals(1, (x.run("timers_list", JSONObject()) as JSONArray).length())
        try { x.run("timers_cancel", JSONObject().put("id", "t_nope")); fail() } catch (e: ToolError) { assertEquals("no timer t_nope", e.message) }
    }
}
