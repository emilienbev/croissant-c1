package com.bleural.croissant.ask

import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.BenchGroup
import com.bleural.croissant.data.HoldPrompt
import com.bleural.croissant.data.ToolSpec
import com.bleural.croissant.net.BenchRun
import com.bleural.croissant.net.BenchState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model test on the C1's loop, judged as the server's bench.py judged it. */
class ModelTestTest {

    /** Draws when asked to draw, adds eggs with an app's tool, dates the dentist, fails on "fail"; answers anything else in words. */
    private class Model(private val hang: Boolean = false) : ChatApi {
        override val model = "gemma4:26b-nvfp4"
        val sent = mutableListOf<Pair<List<Msg>, List<ToolSpec>?>>()

        override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
            if (hang) awaitCancellation()
            sent += messages.toList() to tools
            val usage = Usage(3000, 0.2, 10, 0.1, cachedTokens = 1000)
            val q = messages.last { it.role == "user" }.text
            if (messages.last().role == "tool") {
                text("It is being drawn.")
                return Reply(emptyList(), usage = usage)
            }
            fun call(name: String, vararg args: Pair<String, String>) = Reply(listOf(Call(name, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } })), usage = usage)
            return when {
                "draw" in q -> call("image_generate", "prompt" to "a cat")
                "eggs" in q -> call("shop_add", "name" to "Eggs")
                "dentist" in q -> call("agenda_add", "title" to "dentist", "at" to "2026-10-17T09:00:00+01:00")
                "fail" in q -> throw AskError(503, "503: overloaded")
                else -> {
                    text("**Paris**.")
                    Reply(emptyList(), usage = usage)
                }
            }
        }
    }

    private val ollama = Providers.resolve("ollama", ProviderSpec(), "100.100.100.7")
    private val entry = Providers.entry(ollama, "gemma4:26b-nvfp4")
    private val costs = Costs(Prices(null), Ledger(null), { listOf(ollama) }, { emptyMap() })
    private val p = JSONObject().put("type", "object").put("properties", JSONObject())
    private val prompt = HoldPrompt("default", "You are the voice assistant.\n\nYour tools:\n- agenda_add: add",
        listOf(ToolSpec("agenda_add", "add", p), ToolSpec("image_generate", "draw", p), ToolSpec("shop_add", "add an item", p, "Shop")))
    private val run = BenchRun(entry.id, "default", null)
    private fun case(say: String, expect: String, noAt: Boolean = false, args: String? = null) = BenchCase(say, expect, BenchGroup("questions"), noAt, args)

    /** Each case takes [ms] in turn. */
    private fun clock(vararg ms: Long): () -> Long {
        var t = 0L
        var i = 0
        return { t.also { if (i % 2 == 0) t += ms[i / 2] * 1_000_000; i++ } }
    }

    @Test fun eachCaseJudgedThenTheScore() = runBlocking {
        val model = Model()
        val asked = mutableListOf<String>()
        val test = ModelTest(this, clock(100, 300, 200))
        var scored: Any? = null
        test.test(model, entry, prompt, run, listOf(case("draw a cat", "image_generate"), case("capital of france", ""), case("draw a dog", "")),
            { name, _ -> asked += name; "done" }, costs, done = { scored = it })
        val st = test.state.value as BenchState.Done
        assertEquals(listOf(true, true, false), st.done.map { it.ok })
        assertEquals(listOf("image_generate"), st.done[0].called)
        assertEquals("It is being drawn.", st.done[0].answer)
        assertEquals("Paris.", st.done[1].answer)
        assertEquals(listOf(100L, 300L, 200L), st.done.map { it.ms })
        assertEquals(BenchGroup("questions"), st.done[0].group)
        val s = st.summary
        assertEquals(s, scored)
        assertEquals(2 to 3, s.passed to s.total)
        assertEquals(200L to 300L, s.medianMs to s.p90Ms)
        assertEquals("ollama:gemma4:26b-nvfp4 (Ollama, gemma4:26b-nvfp4)", s.model)
        // Five rounds: two for each drawing, one for the question.
        assertEquals(15000L to 50L, s.tokensIn to s.tokensOut)
        assertTrue(s.free)
        assertEquals(0.0, s.cost!!, 0.0)
        // The hold's prompt and tools as they are; the picture faked, never the device's to run.
        assertTrue(model.sent.all { it.first[0].text == prompt.system && it.first.size >= 2 })
        assertEquals(prompt.tools, model.sent[0].second)
        assertEquals(emptyList<String>(), asked)
        val result = model.sent[1].first.last()
        assertEquals("tool", result.role)
        assertEquals("g_bench1", JSONObject(result.text).getString("id"))
        assertEquals("started", JSONObject(result.text).getString("status"))
    }

    @Test fun aCaseWithArgumentsSaysWhetherTheCallPassedThem() = runBlocking {
        val asked = mutableListOf<Pair<String, String>>()
        val test = ModelTest(this)
        test.test(Model(), entry, prompt, run, listOf(case("add eggs", "shop_add", args = "{\"name\": \"eggs\"}"),
            case("add eggs", "shop_add", args = "{\"name\": \"milk\"}"), case("add eggs", "shop_add")),
            { name, args -> asked += name to args.toString(); "done" }, costs)
        val done = (test.state.value as BenchState.Done).done
        assertEquals(listOf(true, false, true), done.map { it.ok })
        assertEquals(listOf(true, false, null), done.map { it.argsOk })
        // An app's tool is the device's: answered "done", on a copy.
        assertEquals(listOf("shop_add", "shop_add", "shop_add"), asked.map { it.first })
    }

    @Test fun aDatelessReminderGivenADateIsWrongAndAFailedCaseGoesOn() = runBlocking {
        val test = ModelTest(this)
        test.test(Model(), entry, prompt, run, listOf(case("dentist", "agenda_add", noAt = true), case("fail", ""), case("dentist", "agenda_add")),
            { _, _ -> JSONObject().put("id", "e_1") }, costs)
        val done = (test.state.value as BenchState.Done).done
        assertEquals(listOf(true, false, false), done.map { it.dated })
        assertEquals(listOf(false, false, true), done.map { it.ok })
        assertEquals("503: overloaded", done[1].error)
        assertNull(done[0].error)
    }

    @Test fun noCasesIsAnErrorAndAStoppedTestKeepsWhatRan() = runBlocking {
        val test = ModelTest(this)
        test.test(Model(), entry, prompt, run, emptyList(), { _, _ -> "done" }, costs)
        assertEquals(BenchState.Failed(emptyList(), 0, "no cases to run", run), test.state.value)
        test.start(Model(hang = true), entry, prompt, run, listOf(case("q", "")), { _, _ -> "done" }, costs)
        yield()
        assertTrue(test.state.value is BenchState.Running)
        test.stop()
        assertEquals(BenchState.Failed(emptyList(), 1, "stopped", run), test.state.value)
    }

    @Test fun aTestWithNoModelToRunOnFailsBeforeItsFirstCase() = runBlocking {
        val test = ModelTest(this)
        test.start(Model(hang = true), entry, prompt, run, listOf(case("q", "")), { _, _ -> "done" }, costs)
        yield()
        test.refuse(run, 1, "no key for gemini")
        assertEquals(BenchState.Failed(emptyList(), 1, "no key for gemini", run), test.state.value)
    }

    @Test fun theRule() {
        assertTrue(ModelTest.judge("agenda_add", listOf("agenda_add")))
        assertFalse(ModelTest.judge("agenda_add", listOf("agenda_add", "web_search")))
        assertFalse(ModelTest.judge("agenda_add", listOf("agenda_add"), dated = true))
        assertTrue(ModelTest.judge("", emptyList()))
        assertTrue(ModelTest.judge("*", listOf("timers_list", "timers_cancel")))
        assertFalse(ModelTest.judge("*", listOf("image_generate")))
        assertFalse(ModelTest.judge("*", emptyList()))
    }

    @Test fun namedArgumentsMustComeOutAsGivenIgnoringCase() {
        val calls = listOf(Call("shopping_add_item", JSONObject().put("name", "Eggs ").put("count", "2")))
        fun want(k: String, v: Any) = JSONObject().put(k, v)
        assertTrue(ModelTest.argsMatch("shopping_add_item", want("name", "eggs"), calls))
        assertTrue(ModelTest.argsMatch("shopping_add_item", want("count", 2), calls))
        assertFalse(ModelTest.argsMatch("shopping_add_item", want("name", "milk"), calls))
        assertFalse(ModelTest.argsMatch("shopping_add_item", want("size", "big"), calls))
        assertFalse(ModelTest.argsMatch("other_tool", want("name", "eggs"), calls))
        assertTrue(ModelTest.argsMatch("t", want("on", true), listOf(Call("t", JSONObject().put("on", "true")))))
    }

    @Test fun oneArgumentAsPythonComparedIt() {
        assertTrue(ModelTest.same(2.5, "2.5"))
        assertTrue(ModelTest.same(1000, " 1_000 "))
        assertFalse(ModelTest.same(2, "two"))
        assertFalse(ModelTest.same(2, null))
        // Text against a number: the number as Python writes it.
        assertTrue(ModelTest.same("2.0", 2.0))
        assertFalse(ModelTest.same("2", 2.0))
        assertTrue(ModelTest.same("2", 2))
        assertTrue(ModelTest.same("1e+16", 1e16))
        assertTrue(ModelTest.same("12345678.0", 12345678.0))
        assertFalse(ModelTest.same(true, 1))
        assertTrue(ModelTest.same(false, "False"))
        assertFalse(ModelTest.same("x", JSONObject()))
        // Anything else by value: lists and objects deep, a bool equal to its number.
        assertTrue(ModelTest.same(JSONArray().put(1).put(true), JSONArray().put(1.0).put(1)))
        assertTrue(ModelTest.same(JSONObject.NULL, null))
        assertFalse(ModelTest.same(JSONObject().put("a", 1), JSONObject().put("a", 1).put("b", 2)))
    }

    @Test fun theServersToolsFakedAtOnce() {
        assertEquals(setOf("image_generate", "web_search", "tape_ask"), ModelTest.FAKED)
        val picture = ModelTest.fake("image_generate", JSONObject()) as JSONObject
        assertEquals("g_bench1" to "started", picture.getString("id") to picture.getString("status"))
        val digest = ModelTest.fake("web_search", JSONObject().put("query", " f1 result ")) as String
        assertTrue(digest.startsWith("Web results for \"f1 result\":\n1. example.com, 2 hours ago: "))
        assertEquals(3, digest.lines().size)
        assertEquals("Tape 21: They agreed the budget is 40k, at 12:30.", ModelTest.fake("tape_ask", JSONObject()))
        assertEquals("no such tool x", (ModelTest.fake("x", JSONObject()) as JSONObject).getString("error"))
    }
}
