package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.NDJSON
import com.bleural.croissant.ask.FakeHttp.Companion.SSE
import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.data.ToolSpec
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three clients against recorded streams (test/resources/ask/): what each sends, what it makes of what comes back. */
class ChatTest {
    private val host = "100.100.100.7"
    private val geminiKey = "AIza00000000000000000000000000000000MNOP"
    private val openAiKey = "sk-proj-fake-0000000000000000000000000000"
    private val timer = ToolSpec("timers_add", "Start a timer", JSONObject("""{"type":"object","properties":{"seconds":{"type":"integer"}},"required":["seconds"]}"""))
    private val jpeg = byteArrayOf(-1, -40, -1, -32)

    private fun provider(id: String) = Providers.resolve(id, ProviderSpec(), host)

    /** JSON with its keys sorted, to compare two objects whatever order they were read in. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        else -> JSONObject.quote(v.toString())
    }

    // ---- the line readers and the JSON the server wrote ---------------------------------

    @Test fun serverSentEventsJoinDataLinesAndSkipTheRest() {
        val got = mutableListOf<String>()
        Buffer().writeUtf8(": comment\r\nevent: x\r\ndata: one\r\ndata:two\r\n\r\ndata: {\"a\":1}\n\n\ndata: last").sse { got += it; true }
        assertEquals(listOf("one\ntwo", "{\"a\":1}", "last"), got)
        val stopped = mutableListOf<String>()
        Buffer().writeUtf8("data: a\n\ndata: [DONE]\n\ndata: b\n\n").sse { stopped += it; it != "[DONE]" }
        assertEquals(listOf("a", "[DONE]"), stopped)
    }

    @Test fun whatFollowsTheLastEventIsReadToTheEnd() {
        val rest = Buffer().writeUtf8("data: [DONE]\n\n0\r\n\r\n")
        rest.sse { false }
        rest.drain()
        assertTrue(rest.exhausted())
    }

    @Test fun ndjsonSkipsBlankLines() {
        val got = mutableListOf<Int>()
        Buffer().writeUtf8("{\"n\":1}\n\n{\"n\":2}\n").ndjson { got += it.getInt("n") }
        assertEquals(listOf(1, 2), got)
    }

    @Test fun pythonsJsonDumps() {
        val o = JSONObject().put("title", "dentist \"Dr. Lë\"\n\t\\ / \u0001").put("n", 2).put("x", 2.0).put("f", 1.5).put("tiny", 0.00001)
            .put("none", JSONObject.NULL).put("ok", true).put("list", JSONArray().put(1).put("a"))
        val text = pyJson(o)
        // Each pair as Python writes it; the order is the object's own.
        for (pair in listOf("\"title\": \"dentist \\\"Dr. Lë\\\"\\n\\t\\\\ / \\u0001\"", "\"n\": 2", "\"x\": 2", "\"f\": 1.5",
            "\"tiny\": 1e-05", "\"none\": null", "\"ok\": true", "\"list\": [1, \"a\"]")) assertTrue(text, pair in text)
        assertEquals("{\"a\": {\"b\": []}}", pyJson(JSONObject("{\"a\":{\"b\":[]}}")))
        assertEquals("0.0001", pyJson(0.0001))
        assertEquals("-3.25", pyJson(-3.25))
        assertEquals("\"plain\"", pyJson("plain"))
    }

    // ---- a hold's press ---------------------------------------------------------------------

    @Test fun aPressWarmsTheProviderWithNoKey() {
        val http = FakeHttp().apply { on("GET", "/api/version", body = """{"version":"0.13.0"}"""); on("GET", "/v1", 404) }
        Chat.warm(provider("ollama"), http.client)
        Chat.warm(provider("openai"), http.client)
        val until = System.currentTimeMillis() + 5_000
        while (http.asked.size < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(setOf("/api/version", "/v1"), http.asked.map { it.path }.toSet())
        assertTrue(http.asked.all { it.request.header("Authorization") == null && it.request.method == "GET" })
    }

    // ---- Ollama ---------------------------------------------------------------------------

    @Test fun ollamaSendsWhatWasMeasured() {
        val chat = OllamaChat(provider("ollama"), "qwen3.5:9b-mlx", FakeHttp().client)
        val call = Call("timers_add", JSONObject().put("seconds", 300))
        val b = chat.body(listOf(Msg("system", "S"), Msg("user", "Q", images = listOf(jpeg)),
            Msg("assistant", "", calls = listOf(call)), Msg("tool", "{\"id\": \"t_1\"}", call = call)), listOf(timer))
        assertEquals(false, b.get("think"))
        assertEquals(0.0, b.getJSONObject("options").getDouble("temperature"), 0.0)
        assertTrue(b.getBoolean("stream"))
        assertEquals("function", b.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("timers_add", b.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        val m = b.getJSONArray("messages")
        assertEquals("/9j/4A==", m.getJSONObject(1).getJSONArray("images").getString(0))
        assertEquals(300, m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getJSONObject("arguments").getInt("seconds"))
        val tool = m.getJSONObject(3)
        assertEquals(listOf("tool", "timers_add", "{\"id\": \"t_1\"}"), listOf(tool.getString("role"), tool.getString("tool_name"), tool.getString("content")))
        // No tools is an empty list, as the server's client sent it.
        assertEquals(0, chat.body(listOf(Msg("user", "Q")), null).getJSONArray("tools").length())
    }

    @Test fun ollamaTextAndItsOwnTimings() = runBlocking {
        val http = FakeHttp().apply { on("POST", "/api/chat", body = fixture("ollama_text.ndjson"), type = NDJSON) }
        val chat = Chat.of(provider("ollama"), "qwen3.5:9b-mlx", null, http.client)
        val got = mutableListOf<String>()
        val reply = chat.stream(listOf(Msg("user", "Q")), null) { got += it }
        assertEquals(listOf("Paris", " is the **capital**", " of France."), got)
        assertTrue(reply.calls.isEmpty())
        assertEquals(Usage(3239, 0.229, 9, 0.16), reply.usage)
        assertEquals("http://$host:11434/api/chat", http.asked.single().request.url.toString())
    }

    @Test fun ollamaToolCallsComeWhole() = runBlocking {
        val http = FakeHttp().apply { on("POST", "/api/chat", body = fixture("ollama_tools.ndjson"), type = NDJSON) }
        val reply = Chat.of(provider("ollama"), "gemma4:26b-nvfp4", null, http.client).stream(listOf(Msg("user", "tea")), listOf(timer))
        val c = reply.calls.single()
        assertEquals("timers_add", c.name)
        assertEquals(300, c.args.getInt("seconds"))
        assertEquals("tea", c.args.getString("label"))
        assertEquals(2946, reply.usage!!.promptTokens)
    }

    @Test fun ollamaAnErrorInTheStreamIsSaid() {
        val http = FakeHttp().apply { on("POST", "/api/chat", body = fixture("ollama_error.ndjson"), type = NDJSON) }
        val e = assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("ollama"), "m", null, http.client).stream(listOf(Msg("user", "Q")), null) } }
        assertTrue(e.message!!.contains("unexpected EOF"))
        val missing = FakeHttp().apply { on("POST", "/api/chat", 404, """{"error":"model \"nope\" not found, try pulling it first"}""") }
        val e2 = assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("ollama"), "nope", null, missing.client).complete(listOf(Msg("user", "Q"))) } }
        assertEquals(404, e2.code)
        assertEquals("404: model \"nope\" not found, try pulling it first", e2.message)
    }

    // ---- OpenAI-compatible -----------------------------------------------------------------

    @Test fun openAiTextUsageAndCache() = runBlocking {
        val http = FakeHttp().apply { on("POST", "/v1/chat/completions", body = fixture("openai_text.sse"), type = SSE) }
        val chat = Chat.of(provider("openai"), "gpt-4o-mini", openAiKey, http.client)
        val got = mutableListOf<String>()
        val reply = chat.stream(listOf(Msg("system", "S"), Msg("user", "Q")), null) { got += it }
        assertEquals(listOf("Paris", " is the capital of France."), got)
        assertEquals(3412, reply.usage!!.promptTokens)
        assertEquals(8, reply.usage!!.outTokens)
        assertEquals(3200, reply.usage!!.cachedTokens)
        val asked = http.asked.single()
        assertEquals("Bearer $openAiKey", asked.request.header("Authorization"))
        assertFalse(openAiKey in asked.request.url.toString() || openAiKey in asked.body)
        val b = asked.json()
        assertEquals(0.0, b.getDouble("temperature"), 0.0)
        assertTrue(b.getJSONObject("stream_options").getBoolean("include_usage"))
        assertFalse(b.has("tools"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test fun openAiToolCallsArriveInPieces() = runBlocking {
        val http = FakeHttp().apply { on("POST", "/chat/completions", body = fixture("openai_tools.sse"), type = SSE) }
        val got = mutableListOf<String>()
        val reply = Chat.of(provider("openai"), "gpt-4o", openAiKey, http.client).stream(listOf(Msg("user", "Q")), listOf(timer)) { got += it }
        // Beside every piece of a call, "content": null: never "null" on the screen.
        assertTrue(got.isEmpty())
        assertEquals(listOf("agenda_add", "timers_list"), reply.calls.map { it.name })
        assertEquals(listOf("call_Qx1", "call_Qx2"), reply.calls.map { it.id })
        assertEquals("dentist", reply.calls[0].args.getString("title"))
        assertEquals("2026-10-23T15:00", reply.calls[0].args.getString("at"))
        assertEquals(0, reply.calls[1].args.length())
        assertEquals("timers_add", http.asked.single().json().getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test fun openAiMessagesAsTheServerSentThem() {
        val chat = OpenAiChat(provider("openai"), "gpt-4o", openAiKey, FakeHttp().client)
        val call = Call("agenda_add", JSONObject().put("title", "dentist"), "call_Qx1")
        val m = chat.body(listOf(Msg("user", "Q", images = listOf(jpeg)), Msg("assistant", "", calls = listOf(call)),
            Msg("tool", "{\"id\": \"e_1\"}", call = call)), null).getJSONArray("messages")
        val parts = m.getJSONObject(0).getJSONArray("content")
        assertEquals("Q", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,/9j/4A==", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue(m.getJSONObject(1).isNull("content"))
        assertEquals("{\"title\": \"dentist\"}", m.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("call_Qx1", m.getJSONObject(2).getString("tool_call_id"))
    }

    @Test fun aRefusedParameterIsDroppedOnceForGood() = runBlocking {
        val http = FakeHttp().apply {
            on("POST", "/chat/completions", 400, fixture("openai_refused.json"))
            on("POST", "/chat/completions", body = fixture("openai_text.sse"), type = SSE)
        }
        val said = mutableListOf<String>()
        val chat = Chat.of(provider("openai"), "o3", openAiKey, http.client) { said += it }
        assertEquals("Paris is the capital of France.", chat.complete(listOf(Msg("user", "Q"))).first)
        chat.complete(listOf(Msg("user", "Q again")))
        assertEquals(listOf(true, false, false), http.asked.map { it.json().has("temperature") })
        assertTrue(http.asked.all { it.json().has("stream_options") })
        assertEquals(listOf("OpenAI (o3) refuses temperature; sending it without from now on"), said)
    }

    @Test fun anotherRefusalIsSaidNotRetried() {
        val http = FakeHttp().apply { on("POST", "/chat/completions", 400, fixture("openai_bad_request.json")) }
        val e = assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("openai"), "gpt-4o", openAiKey, http.client).complete(listOf(Msg("user", "Q"))) } }
        assertEquals(400, e.code)
        assertTrue(e.message!!.startsWith("400: Invalid schema for function 'agenda_add'"))
        assertEquals(1, http.asked.size)
    }

    @Test fun aFailureBeforeTheAnswerIsTriedOnceMore() = runBlocking {
        val http = FakeHttp().apply {
            on("POST", "/chat/completions", 503, """{"error":{"message":"The server is overloaded","type":"server_error"}}""")
            on("POST", "/chat/completions", body = fixture("openai_text.sse"), type = SSE)
        }
        assertEquals("Paris is the capital of France.", Chat.of(provider("groq"), "llama-3.3-70b-versatile", "gsk_fake", http.client).complete(listOf(Msg("user", "Q"))).first)
        assertEquals(2, http.asked.size)
        val down = FakeHttp().apply { on("POST", "/chat/completions", 503, """{"error":{"message":"down"}}""") }
        assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("groq"), "m", "gsk_fake", down.client).complete(listOf(Msg("user", "Q"))) } }
        assertEquals(2, down.asked.size)
    }

    @Test fun anErrorEchoingTheKeyLosesIt() {
        val http = FakeHttp().apply { on("POST", "/chat/completions", 401, fixture("openai_bad_key.json").replace("{KEY}", openAiKey)) }
        val e = assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("openai"), "gpt-4o", openAiKey, http.client).complete(listOf(Msg("user", "Q"))) } }
        assertFalse(openAiKey in e.message.orEmpty() || openAiKey in e.body || openAiKey in e.toString())
        assertEquals("401: Incorrect API key provided: [key]. You can find your API key at https://platform.openai.com/account/api-keys.", e.message)
    }

    @Test fun anUnreadableLineIsSaidCutAndWithoutTheKey() {
        val http = FakeHttp().apply { on("POST", "/chat/completions", body = "data: {\"choices\": [{\"delta\": {\"content\": \"$openAiKey\"\n\n", type = SSE) }
        val e = assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("openai"), "gpt-4o", openAiKey, http.client).complete(listOf(Msg("user", "Q"))) } }
        assertTrue(e.message!!.startsWith("an unreadable answer"))
        assertFalse(openAiKey.take(12) in e.message!!.replace("[key]", ""))
    }

    // ---- Gemini ------------------------------------------------------------------------------

    @Test fun geminiTextLeavesThoughtsOutAndKeepsEveryPart() = runBlocking {
        val http = FakeHttp().apply { on("POST", ":streamGenerateContent", body = fixture("gemini_text.sse"), type = SSE) }
        val chat = Chat.of(provider("gemini"), "gemini-3-flash-preview", geminiKey, http.client)
        val got = mutableListOf<String>()
        val reply = chat.stream(listOf(Msg("system", "S1"), Msg("system", "S2"), Msg("user", "Q")), null) { got += it }
        assertEquals(listOf("Paris is", " the capital of France."), got)
        assertEquals(3, reply.raw!!.length())
        assertEquals(812, reply.usage!!.promptTokens)
        assertEquals(9 + 279, reply.usage!!.outTokens)
        assertEquals(512, reply.usage!!.cachedTokens)
        val asked = http.asked.single()
        assertEquals(geminiKey, asked.request.header("x-goog-api-key"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:streamGenerateContent?alt=sse", asked.request.url.toString())
        val b = asked.json()
        assertEquals("S1\n\nS2", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        // The model's own temperature: no generation config at all.
        assertFalse(b.has("generationConfig"))
        assertFalse(b.has("tools"))
    }

    @Test fun geminiTwoRoundsSendTheModelTurnBackUntouched() = runBlocking {
        val http = FakeHttp().apply {
            on("POST", ":streamGenerateContent", body = fixture("gemini_tools.sse"), type = SSE)
            on("POST", ":streamGenerateContent", body = fixture("gemini_tools_2.sse"), type = SSE)
        }
        val loop = Loop(Chat.of(provider("gemini"), "gemini-3-flash-preview", geminiKey, http.client))
        val run = mutableListOf<String>()
        val answer = loop.answer("tea for five minutes", "S", emptyList(), listOf(timer, ToolSpec("timers_list", "List timers", JSONObject())), execute = { c ->
            run += c.name
            if (c.name == "timers_add") JSONObject().put("id", "t_1") else JSONArray()
        })
        assertEquals("Tea timer set for five minutes.", answer)
        assertEquals(listOf("timers_add", "timers_list"), run)
        val first = http.asked[0].json()
        val decl = first.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("timers_add", decl.getString("name"))
        assertEquals("integer", decl.getJSONObject("parametersJsonSchema").getJSONObject("properties").getJSONObject("seconds").getString("type"))
        val contents = http.asked[1].json().getJSONArray("contents")
        assertEquals(3, contents.length())
        // The model's turn, its parts as they came, signature and all.
        val model = contents.getJSONObject(1)
        assertEquals("model", model.getString("role"))
        val recorded = JSONObject(fixture("gemini_tools.sse").trim().removePrefix("data: ")).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        assertEquals(canon(recorded), canon(model.getJSONArray("parts")))
        assertTrue("thoughtSignature" in model.getJSONArray("parts").getJSONObject(0).toString())
        // Both answers of the round in one user turn: an object as it is, anything else as its result.
        val answers = contents.getJSONObject(2).getJSONArray("parts")
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        assertEquals("t_1", answers.getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("id"))
        assertEquals(0, answers.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").getJSONArray("result").length())
    }

    @Test fun geminiAnOddFinishIsLogged() = runBlocking {
        val http = FakeHttp().apply { on("POST", ":streamGenerateContent", body = fixture("gemini_blocked.sse"), type = SSE) }
        val said = mutableListOf<String>()
        val (text, _) = Chat.of(provider("gemini"), "gemini-2.5-flash", geminiKey, http.client) { said += it }.complete(listOf(Msg("user", "Q")))
        assertEquals("I can", text)
        assertEquals(listOf("gemini:gemini-2.5-flash finished with SAFETY"), said)
    }

    @Test fun geminiNoSystemTextNoInstruction() {
        val chat = GeminiChat(provider("gemini"), "m", geminiKey, FakeHttp().client)
        assertFalse(chat.body(listOf(Msg("system", ""), Msg("user", "Q")), null).has("systemInstruction"))
        assertFalse(chat.body(listOf(Msg("user", "Q")), null).has("systemInstruction"))
    }

    @Test fun geminiAKeyAHeaderCannotCarryIsSaidWithoutIt() {
        val http = FakeHttp()
        val e = assertThrows(AskError::class.java) {
            runBlocking { Chat.of(provider("gemini"), "gemini-2.5-flash", "$geminiKey\u200B", http.client).complete(listOf(Msg("user", "Q"))) }
        }
        assertFalse(geminiKey in e.message.orEmpty() || geminiKey in e.toString())
        assertTrue(http.asked.isEmpty())
        val e2 = assertThrows(AskError::class.java) {
            runBlocking { Chat.of(provider("openai"), "gpt-4o", "\uFEFF$openAiKey", http.client).complete(listOf(Msg("user", "Q"))) }
        }
        assertFalse(openAiKey in e2.message.orEmpty())
    }

    @Test fun geminiWithNoKeyIsRefusedBeforeAnyCall() {
        val http = FakeHttp()
        assertThrows(AskError::class.java) { runBlocking { Chat.of(provider("gemini"), "gemini-2.5-flash", null, http.client).complete(listOf(Msg("user", "Q"))) } }
        assertTrue(http.asked.isEmpty())
    }

    @Test fun geminiPhotoAndReasoning() {
        val chat = GeminiChat(provider("gemini"), "gemini-2.5-flash", geminiKey, FakeHttp().client, reasoning = "none")
        val b = chat.body(listOf(Msg("user", "", images = listOf(jpeg))), null)
        val part = b.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("image/jpeg", part.getJSONObject("inlineData").getString("mimeType"))
        assertEquals(0, b.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertNull(GeminiChat(provider("gemini"), "m", geminiKey, FakeHttp().client).body(listOf(Msg("user", "")), null).optJSONArray("contents")?.optJSONObject(0))
    }
}
