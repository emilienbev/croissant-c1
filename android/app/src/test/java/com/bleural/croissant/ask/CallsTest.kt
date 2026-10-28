package com.bleural.croissant.ask

import com.bleural.croissant.data.AskCatalog
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The calls besides a hold's answer, under the catalog's fixed prompts, as the server's llm.py, tapes.py and session.py made them. */
class CallsTest {
    private val prompts = AskCatalog(File("src/main/assets/ask/catalog.json").readText()).calls

    /** Answers every call with [answer] (or fails with [fail], or [lost] the connection), keeping what each was sent. */
    private class Model(private val answer: List<String>, private val fail: String? = null, private val lost: String? = null) : ChatApi {
        override val model = "gemma4:26b-nvfp4"
        val sent = mutableListOf<Pair<List<Msg>, List<ToolSpec>?>>()
        override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
            sent += messages.toList() to tools
            fail?.let { throw AskError(503, it) }
            lost?.let { throw IOException(it) }
            answer.forEach(text)
            return Reply(emptyList(), usage = Usage(900, 0.1, 20, 0.4))
        }
    }

    private companion object {
        const val ENTRY = "ollama:gemma4:26b-nvfp4"
    }

    @Test fun theTransformsDescriptionUnderItsRules() = runBlocking {
        val model = Model(listOf(" \"An oil painting of two red controllers, **thick** brushstrokes\"\n"))
        assertEquals("An oil painting of two red controllers, thick brushstrokes", Calls(model, ENTRY, prompts).describe("make it a painting", byteArrayOf(1, 2)))
        val (messages, tools) = model.sent.single()
        assertNull(tools)
        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertEquals(prompts.transform, messages[0].text)
        assertEquals("make it a painting", messages[1].text)
        assertEquals(1, messages[1].images.size)
    }

    @Test fun theTranslatorStreamsUnderItsRulesForThePair() = runBlocking<Unit> {
        val model = Model(listOf("**", "Où est", " la gare ?"))
        val got = mutableListOf<String>()
        Calls(model, ENTRY, prompts).translate("Where is the station?", Language.English, Language.French) { got += it }
        assertEquals(listOf("Où est", " la gare ?"), got)
        assertEquals(prompts.translateRules(Language.English, Language.French), model.sent.single().first[0].text)
        assertTrue("said aloud in English. Reply with its translation into French and" in model.sent.single().first[0].text)
    }

    private val tape = JSONObject().put("number", 21).put("title", "Budget meeting").put("startedAt", "2026-10-16T09:05:00+01:00")
        .put("durationMs", 3_300_000).put("segments", JSONArray()
            .put(JSONObject().put("start", 0.0).put("text", " Hello "))
            .put(JSONObject().put("start", 12.5).put("text", "the budget"))
            .put(JSONObject().put("start", 31.0).put("text", "is 40k"))
            .put(JSONObject().put("start", 3725.0).put("text", "bye"))
            .put(JSONObject().put("start", 3730.0).put("text", "  ")))

    @Test fun aTapeAsTheReaderIsGivenIt() {
        assertEquals("Recording: Tape 21, \"Budget meeting\", recorded Friday 16 October 2026 at 09:05, 55 minutes long.\n\n" +
            "Transcript:\n[0:00] Hello the budget\n[0:31] is 40k\n[1:02:05] bye", Calls.document(tape))
        // Its own number for a title, no date it can read, under half a minute: said no more than once.
        assertEquals("Recording: Tape 3.", Calls.heading(JSONObject().put("number", 3).put("title", "Tape 3").put("startedAt", "soon").put("durationMs", 20_000)))
        assertEquals("Recording: Tape 4, recorded Monday 12 October 2026 at 00:00, 2 minutes long.",
            Calls.heading(JSONObject().put("number", 4).put("startedAt", "2026-10-12").put("durationMs", 90_000)))
        assertEquals(listOf("0:42", "12:05", "1:02:09"), listOf(42.9, 725.0, 3729.0).map(Calls::stamp))
    }

    @Test fun aTapeQuestionIsReadInACallOfItsOwnAndOnlyTheAnswerGoesBack() = runBlocking {
        val model = Model(listOf("They agreed the budget is 40k, at 0:31."))
        val said = mutableListOf<String>()
        val calls = Calls(model, ENTRY, prompts) { said += it }
        val read = mutableListOf<String>()
        val answer = calls.tapeAsk(JSONObject().put("tape", " 21 ").put("question", "what about the budget?")) { read += it.toString(); tape }
        assertEquals("Budget meeting: They agreed the budget is 40k, at 0:31.", answer)
        assertEquals(listOf("{\"tape\":\"21\"}"), read)
        val (messages, tools) = model.sent.single()
        assertNull(tools)
        assertEquals(prompts.read, messages[0].text)
        assertEquals(Calls.document(tape) + "\n\nQuestion: what about the budget?", messages[1].text)
        assertTrue(said.single(), said.single().startsWith("tape 21 on ollama:gemma4:26b-nvfp4: "))
        // What tape_read refused goes back as it is; so does a question with no tape.
        val refused = calls.tapeAsk(JSONObject().put("tape", "99").put("question", "q")) { throw IllegalStateException("no tape 99; there are tapes 1 and 21") }
        assertEquals("no tape 99; there are tapes 1 and 21", (refused as JSONObject).getString("error"))
        assertEquals("tape and question are required", (calls.tapeAsk(JSONObject().put("question", "q")) { tape } as JSONObject).getString("error"))
        assertEquals(1, model.sent.size)
    }

    @Test fun anAppsAskIsOnePlainAnswerUnderTheAppRulesCappedAndCounted() = runBlocking {
        val model = Model(listOf("*Paris*"))
        val ledger = Ledger(null)
        val calls = Calls(Metered(model, ENTRY, ledger), ENTRY, prompts)
        assertEquals("Paris", calls.appAsk("Capital of France?", "quiz"))
        assertEquals(prompts.app, model.sent.single().first[0].text)
        assertEquals(listOf("ollama:gemma4:26b-nvfp4"), ledger.since(0.0).map { it.model })
        assertEquals("text is required", assertThrows(IllegalArgumentException::class.java) { runBlocking { calls.appAsk("  ") } }.message)
        assertEquals("the question is over 16000 characters",
            assertThrows(IllegalArgumentException::class.java) { runBlocking { calls.appAsk("a".repeat(16_001)) } }.message)
        // Counted as Python counts: 16,000 emoji are 32,000 chars to Kotlin.
        calls.appAsk("🥐".repeat(16_000))
        val failing = Calls(Model(emptyList(), fail = "503: overloaded"), ENTRY, prompts)
        assertEquals("LLM error: 503: overloaded", assertThrows(IOException::class.java) { runBlocking { failing.appAsk("hi") } }.message)
    }

    @Test fun anAppNeverReadsWhereTheModelWasAsked() {
        val lost = Calls(Model(emptyList(), lost = "Failed to connect to /192.0.2.7:11434"), ENTRY, prompts)
        assertEquals("LLM error: the model did not answer", assertThrows(IOException::class.java) { runBlocking { lost.appAsk("hi") } }.message)
    }
}
