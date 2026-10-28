package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.NDJSON
import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.data.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ask's loop as the server's llm.py ran it, on a model that answers as scripted. */
class LoopTest {

    /** One round as the model plays it: its text in pieces, then its calls. */
    private class Round(val pieces: List<String>, val calls: List<Call> = emptyList(), val usage: Usage? = null)

    /** A model that plays [rounds] in turn, keeping what each call was sent. */
    private class Scripted(vararg rounds: Round) : ChatApi {
        override val model = "scripted"
        private val left = ArrayDeque(rounds.toList())
        val sent = mutableListOf<Pair<List<Msg>, List<ToolSpec>?>>()

        override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
            sent += messages.toList() to tools
            val r = left.removeFirst()
            r.pieces.forEach(text)
            return Reply(r.calls, usage = r.usage)
        }
    }

    private val agendaAdd = ToolSpec("agenda_add", "Add to the agenda", JSONObject())
    private val agendaList = ToolSpec("agenda_list", "List the agenda", JSONObject())
    private fun call(name: String, vararg args: Pair<String, Any>) = Call(name, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }, "c_$name")

    @Test fun aPlainQuestionStreamsLiveInOneRound() = runBlocking {
        val model = Scripted(Round(listOf("**Paris**", " is the capital."), usage = Usage(3239, 0.2, 9, 0.1)))
        val got = mutableListOf<String>()
        val stats = mutableListOf<Usage>()
        val answer = Loop(model).answer("Capital of France?", "S", listOf("Hi" to "Hello."), listOf(agendaAdd),
            execute = { error("no call") }, stats = stats) { got += it }
        assertEquals(listOf("Paris", " is the capital."), got)
        assertEquals("Paris is the capital.", answer)
        assertEquals(1, stats.size)
        val (messages, tools) = model.sent.single()
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
        assertEquals("Capital of France?", messages.last().text)
        assertEquals(listOf(agendaAdd), tools)
    }

    @Test fun laterRoundsAreHeldBackWhileTheyCallAgain() = runBlocking {
        val model = Scripted(
            Round(emptyList(), listOf(call("agenda_list"))),
            Round(listOf("Let me ", "check the agenda"), listOf(call("agenda_add", "title" to "dentist"))),
            Round(listOf("Added ", "the dentist.")),
        )
        val got = mutableListOf<String>()
        val ran = mutableListOf<String>()
        val answer = Loop(model).answer("dentist friday", "S", emptyList(), listOf(agendaList, agendaAdd),
            execute = { c -> ran += c.name; if (c.name == "agenda_list") "Nothing on the agenda." else JSONObject().put("id", "e_1") }) { got += it }
        assertEquals(listOf("Added the dentist."), got)
        assertEquals(answer, got.joinToString(""))
        assertEquals(listOf("agenda_list", "agenda_add"), ran)
        // What the model was sent last: each call, then its result; text as it is, the rest as Python wrote it.
        val last = model.sent.last().first
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool"), last.map { it.role })
        assertEquals("Nothing on the agenda.", last[3].text)
        assertEquals("{\"id\": \"e_1\"}", last[5].text)
        assertEquals("Let me check the agenda", last[4].text)
        assertEquals("c_agenda_add", last[5].call!!.id)
    }

    @Test fun whatTheFirstRoundSaidStaysAndTheEndFollowsAfterASpace() = runBlocking {
        val model = Scripted(Round(listOf("Sure."), listOf(call("agenda_add"))), Round(listOf("Done.")))
        val answer = Loop(model).answer("q", "S", emptyList(), listOf(agendaAdd), execute = { "ok" })
        assertEquals("Sure. Done.", answer)
    }

    @Test fun anUnknownToolOrAFailingOneIsTheModelsToReadAbout() = runBlocking {
        val model = Scripted(Round(emptyList(), listOf(call("web_search"), call("agenda_add"))), Round(listOf("Sorry.")))
        val said = mutableListOf<String>()
        Loop(model) { said += it }.answer("q", "S", emptyList(), listOf(agendaAdd), execute = { throw IllegalStateException("the agenda is full") })
        val tools = model.sent.last().first.filter { it.role == "tool" }
        assertEquals("{\"error\": \"no such tool web_search\"}", tools[0].text)
        assertEquals("{\"error\": \"the agenda is full\"}", tools[1].text)
        assertTrue(said.contains("model called unknown tool web_search"))
    }

    @Test fun afterFourRoundsOfCallsTheModelIsOfferedNone() = runBlocking {
        val looping = Array(Loop.MAX_TOOL_ROUNDS) { Round(emptyList(), listOf(call("agenda_list"))) }
        val model = Scripted(*looping, Round(listOf("Here it is.")))
        assertEquals("Here it is.", Loop(model).answer("q", "S", emptyList(), listOf(agendaList), execute = { "[]" }))
        assertEquals(List(Loop.MAX_TOOL_ROUNDS) { listOf(agendaList) } + listOf(null), model.sent.map { it.second })
    }

    @Test fun noToolsOffersNoneAndAPhotoGoesWithTheQuestion() = runBlocking {
        val model = Scripted(Round(listOf("A cat.")))
        Loop(model).answer("what is it", "S", emptyList(), emptyList(), execute = { null }, image = byteArrayOf(1, 2))
        val (messages, tools) = model.sent.single()
        assertNull(tools)
        assertEquals(1, messages.last().images.size)
    }

    @Test fun scrubAsTheServerDid() {
        assertEquals("Bold and code\nitem\nTwo\nTitle", Loop.scrub("**Bold** and `code`\n- item\n2. Two\n## Title"))
        assertEquals("a_b", Loop.scrub("a_b"))
        assertEquals("ab", Loop.scrub("a__b"))
        // A piece that opens on a bullet loses it, as a line after a newline does; a star goes before that.
        assertEquals("item", Loop.scrub(" - item"))
        assertEquals(" item", Loop.scrub("* item"))
        assertEquals("a - b", Loop.scrub("a - b"))
    }

    @Test fun theSingleCalls() = runBlocking {
        assertEquals("a watercolour of the photo", Loop(Scripted(Round(listOf(" \"a **watercolour** of the photo\"\n")))).describe("make it a watercolour", byteArrayOf(1), "T"))
        assertEquals("Two tapes." to null, Loop(Scripted(Round(listOf(" *Two* tapes. ")))).complete("how many", "R"))
        val got = mutableListOf<String>()
        Loop(Scripted(Round(listOf("**", "Bonjour", " le monde")))).translate("Hello world", "TR") { got += it }
        assertEquals(listOf("Bonjour", " le monde"), got)
    }

    @Test fun ratesAddUpTheRounds() {
        val r = Loop.rates(listOf(Usage(3000, 0.2, 40, 1.0), Usage(851, 0.05, 12, 0.3)))
        assertEquals(3851, r.promptTokens)
        assertEquals(3851 / 0.25, r.prefill, 1e-9)
        assertEquals(52 / 1.3, r.decode, 1e-9)
        assertEquals(0.0, Loop.rates(emptyList()).prefill, 0.0)
    }

    @Test fun aWholeHoldOnOllamaFromItsRecordedStreams() = runBlocking {
        val http = FakeHttp().apply {
            on("POST", "/api/chat", body = fixture("ollama_tools.ndjson"), type = NDJSON)
            on("POST", "/api/chat", body = fixture("ollama_text.ndjson"), type = NDJSON)
        }
        val ollama = Providers.resolve("ollama", ProviderSpec(), "100.100.100.7")
        val timer = ToolSpec("timers_add", "Start a timer", JSONObject())
        val stats = mutableListOf<Usage>()
        val answer = Loop(Chat.of(ollama, "gemma4:26b-nvfp4", null, http.client)).answer("tea", "S", emptyList(), listOf(timer),
            execute = { c -> JSONObject().put("id", "t_1").put("seconds", c.args.getInt("seconds")) }, stats = stats)
        assertEquals("Paris is the capital of France.", answer)
        assertEquals(listOf(2946, 3239), stats.map { it.promptTokens })
        val second = http.asked[1].json().getJSONArray("messages")
        val tool = second.getJSONObject(second.length() - 1)
        assertEquals("timers_add", tool.getString("tool_name"))
        assertTrue(tool.getString("content"), tool.getString("content").contains("\"seconds\": 300"))
    }
}
