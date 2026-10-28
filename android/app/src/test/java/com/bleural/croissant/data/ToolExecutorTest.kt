package com.bleural.croissant.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

class ToolExecutorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-16T20:00:00+01:00")
    private val reminders by lazy { RemindersStore(File(tmp.root, "reminders.json")) { now } }
    private val events by lazy { EventsStore(File(tmp.root, "events.json")) { now } }
    private val timers by lazy { TimersStore(File(tmp.root, "timers.json")) { now } }
    private val tools by lazy { ToolExecutor(reminders, events, timers) { now } }

    private fun run(name: String, vararg args: Pair<String, Any?>): JSONObject =
        tools.run(name, JSONObject().apply { for ((k, v) in args) put(k, v) }) as JSONObject

    @Test fun theDateDecides() {
        val r = run("agenda_add", "title" to "Buy avocados")
        assertTrue(r.getString("id").startsWith("r_"))
        val day = run("agenda_add", "title" to "Bins out", "at" to "2026-09-18")
        assertTrue(day.getString("id").startsWith("e_")); assertTrue(day.getBoolean("allDay"))
        val timed = run("agenda_add", "title" to "Call mum", "at" to "2026-09-17T18:00")
        assertEquals("2026-09-17T18:00:00+01:00", timed.getString("start"))
        assertEquals("2026-09-17T19:00:00+01:00", timed.getString("end"))
        assertEquals(1, reminders.list().size); assertEquals(2, events.list().size)
    }

    @Test fun aWhenTurnsAReminderIntoAnEvent() {
        val r = run("agenda_add", "title" to "Renew passport")
        val e = run("agenda_update", "id" to r.getString("id"), "at" to "2026-10-01")
        assertTrue(e.getString("id").startsWith("e_")); assertEquals("Renew passport", e.getString("title"))
        assertEquals(0, reminders.list().size); assertEquals(1, events.list().size)
    }

    @Test fun updateAndDeleteGoByTheId() {
        val r = run("agenda_add", "title" to "a")
        val e = run("agenda_add", "title" to "b", "at" to "2026-09-20T10:00")
        assertTrue(run("agenda_update", "id" to r.getString("id"), "done" to true).getBoolean("done"))
        assertEquals("2026-09-21T10:00:00+01:00", run("agenda_update", "id" to e.getString("id"), "at" to "2026-09-21T10:00").getString("start"))
        try { run("agenda_update", "id" to e.getString("id"), "done" to true); fail() } catch (x: ToolError) { assertTrue(x.message!!.contains("not ticked")) }
        run("agenda_delete", "id" to e.getString("id")); run("agenda_delete", "id" to r.getString("id"))
        assertEquals(0, reminders.list().size); assertEquals(0, events.list().size)
        try { run("agenda_delete", "id" to "e_nope"); fail() } catch (x: ToolError) { assertEquals("no event e_nope", x.message) }
    }

    @Test fun listCoversBothWithADefaultRange() {
        run("agenda_add", "title" to "a")
        run("agenda_add", "title" to "soon", "at" to "2026-09-20")
        run("agenda_add", "title" to "far", "at" to "2026-11-20")
        val l = run("agenda_list")
        assertEquals(1, l.getJSONArray("reminders").length())
        assertEquals(listOf("soon"), (0 until l.getJSONArray("events").length()).map { l.getJSONArray("events").getJSONObject(it).getString("title") })
        assertEquals(2, run("agenda_list", "from" to "2026-09-01", "to" to "2026-12-31").getJSONArray("events").length())
    }
}
