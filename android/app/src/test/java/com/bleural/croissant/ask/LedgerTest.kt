package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The C1's count of its model calls, as the server's ledger.py keeps one. */
class LedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_791_000_000_000L   // ms; October 2026
    private val clock = { now }
    private fun file() = File(tmp.root, "usage.jsonl")

    @Test fun aLinePerCallWithTheModelAndItsCountsNeverWhatWasSaid() {
        val f = file()
        Ledger(f, clock).add(ModelSlot.Chat, "ollama:qwen3.5:9b-mlx", Counts(input = 3851.0, out = 12.0))
        val line = JSONObject(f.readLines().single())
        assertEquals(now / 1000, line.getLong("t"))
        assertEquals("chat", line.getString("slot"))
        assertEquals("ollama:qwen3.5:9b-mlx", line.getString("model"))
        assertEquals(3851.0, line.getDouble("in"), 0.0)
        assertEquals(12.0, line.getDouble("out"), 0.0)
        // A count of nothing is left out, as the server writes a line.
        assertEquals(setOf("t", "slot", "model", "in", "out"), line.keys().asSequence().toSet())
    }

    @Test fun readAgainWithTheOldLinesAndTheUnreadableOnesGone() {
        val f = file()
        val t = now / 1000
        f.writeText(
            JSONObject().put("t", t - 70 * Ledger.DAY).put("slot", "chat").put("model", "openai:gpt-4o").put("in", 5).toString() + "\n" +
                "not a line\n\n" +
                JSONObject().put("t", t - Ledger.DAY).put("slot", "stt").put("model", "openai:whisper-1").put("secs", 4.5).toString() + "\n")
        val l = Ledger(f, clock)
        val rows = l.since(0.0)
        assertEquals(listOf("openai:whisper-1"), rows.map { it.model })
        assertEquals(4.5, rows.single().counts.secs, 0.0)
        assertEquals(t - Ledger.DAY, l.first())
        assertEquals(1, f.readLines().size)
        assertNull(Ledger(File(tmp.root, "none.jsonl"), clock).first())
    }

    @Test fun countsRoundedToADecimalAsPythonRounds() {
        val l = Ledger(null, clock)
        l.add(ModelSlot.Stt, "gemini:gemini-2.5-flash", Counts(secs = 2.25, live = 2.35, out = 7.0))
        val c = l.since(0.0).single().counts
        assertEquals(2.2, c.secs, 0.0)
        assertEquals(2.4, c.live, 0.0)
        assertEquals(7.0, c.out, 0.0)
    }

    @Test fun rowsLeaveMemoryAsTheyAge() {
        val l = Ledger(null, clock)
        l.add(ModelSlot.Chat, "a:old", Counts(input = 1.0))
        now += 62 * Ledger.DAY * 1000
        l.add(ModelSlot.Chat, "a:new", Counts(input = 1.0))
        assertEquals(listOf("a:new"), l.since(0.0).map { it.model })
    }

    /** A model that answers once with [usage]. */
    private class Counted(private val usage: Usage?) : ChatApi {
        override val model = "gpt-4o-mini"
        override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
            text("Paris.")
            return Reply(emptyList(), usage = usage)
        }
    }

    @Test fun aMeteredChatWritesEveryCallItsProviderCounted() = runBlocking {
        val l = Ledger(file(), clock)
        val chat = Metered(Counted(Usage(1200, 0.1, 8, 0.2, cachedTokens = 1024)), "openai:gpt-4o-mini", l)
        assertEquals("gpt-4o-mini", chat.model)
        assertEquals("Paris." to Usage(1200, 0.1, 8, 0.2, 1024), chat.complete(listOf(Msg("user", "Capital of France?"))))
        val row = l.since(0.0).single()
        assertEquals(Row(now / 1000, "chat", "openai:gpt-4o-mini", Counts(input = 1200.0, cached = 1024.0, out = 8.0)), row)
        assertTrue(file().isFile)
        // A call that reports no counts writes nothing.
        Metered(Counted(null), "openai:gpt-4o-mini", l).complete(listOf(Msg("user", "again")))
        assertEquals(1, l.since(0.0).size)
        assertFalse(file().readText().contains("France"))
    }
}
