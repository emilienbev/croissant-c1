package com.bleural.croissant.ask

import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.HoldPrompt
import com.bleural.croissant.net.BenchCaseResult
import com.bleural.croissant.net.BenchRun
import com.bleural.croissant.net.BenchState
import com.bleural.croissant.net.BenchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * The model test on the C1 (until 2026-10-16 the server's bench.py): does the chat
 * model call the right tool for a sentence, with the prompt and tools a real hold has? Each
 * case runs through [Loop] as a fresh hold, no conversation and no photo, on the hold's own
 * chat (a [Metered] one, so the test is counted as the server counted it).
 *
 * The device's tools run on a throwaway copy of what they touch (Core's scratch tools); the
 * three the server ran ([FAKED]) are answered here at once, nothing made, fetched or read:
 * what is measured is the model's choice of tool, not what the web said or what was drawn.
 * Its states are net/Bench.kt's, which the test's pages read. [nanos] times each case.
 */
class ModelTest(private val scope: CoroutineScope, private val nanos: () -> Long = System::nanoTime) {

    private val _state = MutableStateFlow<BenchState>(BenchState.Idle)
    val state: StateFlow<BenchState> = _state

    private var job: Job? = null

    /** [test] on the scope, any test under way stopped first. */
    fun start(chat: ChatApi, entry: Entry, prompt: HoldPrompt, run: BenchRun, cases: List<BenchCase>,
              answer: (String, JSONObject) -> Any, costs: Costs, log: (String) -> Unit = {}, done: (BenchSummary) -> Unit = {}) {
        stop()
        job = scope.launch { test(chat, entry, prompt, run, cases, answer, costs, log, done) }
    }

    /** A test that cannot start, [why] said (no key for the model, no model): failed before its first case. */
    fun refuse(run: BenchRun, total: Int, why: String) {
        stop()
        _state.value = BenchState.Failed(emptyList(), total, why, run)
    }

    /** Stop a test under way (its page left); what ran so far is kept. */
    fun stop() {
        job?.cancel()
        job = null
        val st = _state.value
        if (st is BenchState.Running) _state.value = BenchState.Failed(st.done, st.total, "stopped", st.run)
    }

    /**
     * Each of [cases] in order on [chat] ([entry] its model), with [prompt]'s whole system
     * prompt and its tools; [answer] runs a device tool (blocking, run on IO) and returns its
     * result. A result per case as it ends, then the score, the times, the tokens and what
     * they cost by [costs]; [done] gets the score once the test has ended with one, never
     * when it is stopped. A case the provider fails is failed and the test goes on.
     */
    suspend fun test(chat: ChatApi, entry: Entry, prompt: HoldPrompt, run: BenchRun, cases: List<BenchCase>,
                     answer: (String, JSONObject) -> Any, costs: Costs, log: (String) -> Unit = {}, done: (BenchSummary) -> Unit = {}) {
        if (cases.isEmpty()) {
            _state.value = BenchState.Failed(emptyList(), 0, "no cases to run", run)
            return
        }
        _state.value = BenchState.Running(emptyList(), cases.size, run)
        log("model test on ${entry.id}: ${cases.size} cases, prompt ${prompt.tag}; ${prompt.tools.size} tools")
        val loop = Loop(chat, log)
        val device: suspend (String, JSONObject) -> Any? = { name, args -> withContext(Dispatchers.IO) { answer(name, args) } }
        val times = mutableListOf<Double>()
        var used = Counts()
        for ((i, c) in cases.withIndex()) {
            val ran = case(loop, prompt, i, c, device, log)
            times += ran.ms
            for (r in ran.rounds) used += Counts(input = r.promptTokens.toDouble(), cached = r.cachedTokens.toDouble(), out = r.outTokens.toDouble())
            _state.update { st -> if (st is BenchState.Running) st.copy(done = st.done + ran.result) else st }
        }
        val st = _state.value as? BenchState.Running ?: return
        val summary = summary(st.done, times, used, costs.cost(entry, used), costs.costsNothing(entry), label(entry))
        _state.value = BenchState.Done(st.done, summary, run)
        log("model test on ${entry.id}: ${summary.passed}/${summary.total}")
        done(summary)
    }

    /** One case's result, its time in ms unrounded (the summary's median is taken from these) and its rounds' counts. */
    class Ran(val result: BenchCaseResult, val ms: Double, val rounds: List<Usage>)

    /** [c], the [i]th case, as a fresh hold, judged as eval_tools judges a run. */
    suspend fun case(loop: Loop, prompt: HoldPrompt, i: Int, c: BenchCase, device: suspend (String, JSONObject) -> Any?,
                     log: (String) -> Unit = {}): Ran {
        val calls = mutableListOf<Call>()
        val rounds = mutableListOf<Usage>()
        val said = StringBuilder()
        var error: String? = null
        val t0 = nanos()
        try {
            loop.answer(c.say, prompt.system, emptyList(), prompt.tools, execute = { call ->
                calls += call
                if (call.name in FAKED) fake(call.name, call.args) else device(call.name, call.args)
            }, stats = rounds) { said.append(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The provider down, a key refused: the case fails, the test goes on.
            log("model test case $i failed: ${e.message}")
            error = e.message ?: e.javaClass.simpleName
        }
        val ms = (nanos() - t0) / 1e6
        val called = calls.map { it.name }
        val dated = c.noAt && calls.any { it.name == "agenda_add" && pyTruthy(it.args.opt("at")) }
        val want = c.args?.let { runCatching { JSONObject(it) }.getOrNull() }
        val argsOk = if (want != null && want.length() > 0 && c.expect != "" && c.expect != "*") argsMatch(c.expect, want, calls) else null
        val ok = error == null && judge(c.expect, called, dated) && argsOk != false
        val answer = said.toString().trim().let { if (it.codePointCount(0, it.length) <= 400) it else it.substring(0, it.offsetByCodePoints(0, 400)) }
        return Ran(BenchCaseResult(i, c.say, c.expect, called, ok, dated, answer, Math.rint(ms).toLong(), error, argsOk, c.group), ms, rounds)
    }

    companion object {
        /** The server's own Ask tools, answered at once in a test; `tape_ask` too, though the C1 runs it from 4a. */
        val FAKED = setOf("image_generate", "web_search", "tape_ask")

        val PICTURE_TOOLS = setOf("image_generate")

        // What a `web_search` call is answered with. A real search would make a run slow and
        // different every time. The shape is the search's digest, numbered lines and all,
        // because a digest the model cannot read as one would change the thing being measured.
        const val FAKE_DIGEST = "Web results for \"{query}\":\n" +
            "1. example.com, 2 hours ago: The short answer is twelve, and nothing has changed since this morning.\n" +
            "2. example.org: The same figure again, twelve, with a line of detail after it."

        // And a `tape_ask` call, shaped as the tape's answer comes: its title, then the answer.
        const val FAKE_TAPE = "Tape 21: They agreed the budget is 40k, at 12:30."

        /** A server tool's answer in a test: at once, and nothing real happens. */
        fun fake(name: String, args: JSONObject): Any = when (name) {
            in PICTURE_TOOLS -> JSONObject().put("id", "g_bench1").put("status", "started")
            "web_search" -> FAKE_DIGEST.replace("{query}", args.opt("query").takeIf(::pyTruthy)?.toString()?.trim().orEmpty())
            "tape_ask" -> FAKE_TAPE
            else -> JSONObject().put("error", "no such tool $name")
        }

        /**
         * One run of one sentence, the way it was supposed to go: [expect] the one tool it
         * should call and nothing else, "" no tool at all, "*" some tool but never a picture;
         * [dated] it gave a dateless reminder an `at`, which is wrong whatever else it did.
         */
        fun judge(expect: String, called: List<String>, dated: Boolean = false): Boolean = when {
            dated -> false
            expect == "" -> called.isEmpty()
            expect == "*" -> called.isNotEmpty() && called.none { it in PICTURE_TOOLS }
            else -> called.isNotEmpty() && called.all { it == expect }
        }

        /** Some call of [expect] passed every argument [want] names, as named; others it may add. */
        fun argsMatch(expect: String, want: JSONObject, calls: List<Call>): Boolean = calls.any { c ->
            c.name == expect && want.keys().asSequence().all { k -> c.args.has(k) && same(want.opt(k), c.args.opt(k)) }
        }

        /** One argument as the case named it: text compared trimmed and ignoring case, numbers by value (bench.py's `_same`). */
        fun same(want: Any?, got: Any?): Boolean {
            if (want is Boolean || got is Boolean) return pyStr(want).lowercase() == pyStr(got).lowercase()
            if (want is Number && got !is JSONObject && got !is JSONArray) return pyFloat(got)?.let { it == want.toDouble() } ?: false
            if (want is String) return (got is String || got is Number) && pyStr(got).trim().lowercase() == want.trim().lowercase()
            return pyEq(want, got)
        }

        /** The model as the summary names it: which, at which provider (the server's `Entry.label`). */
        fun label(e: Entry): String = "${e.id} (${e.provider.title}, ${e.model})"

        /** The score, the median and 90th percentile times, the tokens: as `bench_done` said them. */
        fun summary(done: List<BenchCaseResult>, times: List<Double>, used: Counts, cost: Double?, free: Boolean, model: String): BenchSummary {
            val t = times.sorted()
            val n = t.size
            val median = if (n % 2 == 1) t[n / 2] else (t[n / 2 - 1] + t[n / 2]) / 2
            return BenchSummary(done.count { it.ok }, done.size, Math.rint(median).toLong(), Math.rint(t[(n * 0.9).toInt()]).toLong(),
                used.input.toLong(), used.out.toLong(), cost, free, model)
        }

        // Python's str(), float() and == on what JSON gives, for [same].

        private fun pyStr(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "None"
            is Boolean -> if (v) "True" else "False"
            is Double, is Float, is BigDecimal -> pyRepr((v as Number).toDouble())
            else -> v.toString()
        }

        /** A float as Python's repr writes it: the shortest digits, an exponent below 1e-4 or from 1e16. */
        private fun pyRepr(d: Double): String {
            if (d.isNaN()) return "nan"
            if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
            if (d == 0.0) return if (1 / d < 0) "-0.0" else "0.0"
            val b = BigDecimal(d.toString()).stripTrailingZeros()
            val exp = b.precision() - b.scale() - 1
            if (exp in -4..15) return b.toPlainString().let { if ('.' in it) it else "$it.0" }
            val digits = b.unscaledValue().abs().toString()
            val mantissa = digits.take(1) + if (digits.length > 1) "." + digits.drop(1) else ""
            return (if (d < 0) "-" else "") + mantissa + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        }

        private val FLOAT = Regex("[+-]?(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?")
        private val SPECIAL = Regex("([+-]?)(inf|infinity|nan)", RegexOption.IGNORE_CASE)

        /** Python's `float(v)`, null where it raises. */
        private fun pyFloat(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> {
                val s = v.trim()
                when {
                    FLOAT.matches(s) -> s.replace("_", "").toDouble()
                    else -> SPECIAL.matchEntire(s)?.let { m ->
                        if (m.groupValues[2].equals("nan", true)) Double.NaN
                        else if (m.groupValues[1] == "-") Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                    }
                }
            }
            else -> null
        }

        /** Python's == on JSON values: a bool is 0 or 1, numbers by value, objects and arrays deep. */
        private fun pyEq(a: Any?, b: Any?): Boolean {
            val x = if (a == null) JSONObject.NULL else a
            val y = if (b == null) JSONObject.NULL else b
            fun num(v: Any): Double? = when (v) { is Boolean -> if (v) 1.0 else 0.0; is Number -> v.toDouble(); else -> null }
            return when {
                x == JSONObject.NULL || y == JSONObject.NULL -> x == y
                num(x) != null && num(y) != null -> num(x) == num(y)
                x is String && y is String -> x == y
                x is JSONObject && y is JSONObject -> x.keys().asSequence().toSet() == y.keys().asSequence().toSet() &&
                    x.keys().asSequence().all { pyEq(x.opt(it), y.opt(it)) }
                x is JSONArray && y is JSONArray -> x.length() == y.length() && (0 until x.length()).all { pyEq(x.opt(it), y.opt(it)) }
                else -> false
            }
        }
    }
}
