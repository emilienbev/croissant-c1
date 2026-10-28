package com.bleural.croissant.atelier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher's side of the engine process (IsolatedEngine.kt) and the process's own
 * (EngineServer), without the binder: the two joined back to back run real scripts, and an
 * engine scripted here says what a broken or hostile one would.
 */
class IsolatedEngineTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val dirs = mutableListOf<File>()
    private val prelude = File("src/main/assets/atelier/prelude.js").readText()

    @After fun tearDown() {
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private class CountingBridge : Bridge {
        val asked = AtomicInteger()
        val fetched = AtomicInteger()
        override suspend fun ask(app: InstalledApp, text: String): String = "answer to $text".also { asked.incrementAndGet() }
        override suspend fun fetch(app: InstalledApp, url: String) = Fetched(200, """{"n": 42}""").also { fetched.incrementAndGet() }
        override fun cue(name: String) {}
    }

    private fun app(script: String = "", uses: Set<Use> = emptySet()): InstalledApp {
        val dir = Files.createTempDirectory("atelier").toFile().also { dirs += it }
        File(dir, AppManifest.SCRIPT).writeText(script)
        return InstalledApp(AppManifest("t", 0xFFFFFF, null, "1", 1, uses), dir, AppSource.Local, 0)
    }

    private fun <T : AppRuntime.View> AppRuntime.await(test: (AppRuntime.View) -> Boolean): T = runBlocking {
        @Suppress("UNCHECKED_CAST")
        withTimeout(10_000) { view.first(test) } as T
    }

    private fun AppRuntime.shown(test: (AppPage) -> Boolean = { true }): AppPage =
        await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && test(it.page) }.page

    private fun AppRuntime.failed(): AppRuntime.Failure = await<AppRuntime.View.Failed> { it is AppRuntime.View.Failed }.failure

    private fun until(test: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!test()) delay(10) } }

    // ---- back to back: the launcher's side and the process's, real scripts ---------------------------

    /** The engine process's server, called straight: what the binder would carry, in order. */
    private val backToBack = Engines { host -> IsolatedEngine(host) { e -> EngineServer(e) } }

    private val COUNTER = """
        state({ count: 0 })
        render(s => [big(s.count, "taps"), pill("tap", s => ({ count: s.count + 1 }))])
    """.trimIndent()

    @Test fun anAppRunsThroughTheEngineProcess() {
        val rt = AppRuntime(app(COUNTER), prelude, CountingBridge(), scope, backToBack).also { it.start() }
        rt.call(rt.shown().gen, 0)
        rt.shown { it.pieces.first() == Piece.Big("1", "taps") }
        assertEquals(1, JSONObject(rt.app.savedState()!!).getInt("count"))
        rt.close()
        val again = AppRuntime(rt.app, prelude, CountingBridge(), scope, backToBack).also { it.start() }
        assertEquals(Piece.Big("1", "taps"), again.shown().pieces.first())
    }

    @Test fun fetchGoesOutAndComesBackByItsCallNumber() {
        val bridge = CountingBridge()
        val rt = AppRuntime(app("""
            state({ n: 0 })
            render(s => [big(s.n, "n"), pill("get", async s => {
              const all = await Promise.all([1, 2, 3].map(i => fetch("https://example.com/" + i).then(r => r.json())));
              return { n: all.reduce((a, j) => a + j.n, 0) };
            })])
        """.trimIndent(), setOf(Use.Network)), prelude, bridge, scope, backToBack).also { it.start() }
        rt.call(rt.shown().gen, 0)
        rt.shown { it.pieces.first() == Piece.Big("126", "n") }
        assertEquals(3, bridge.fetched.get())
    }

    @Test fun whatTheAppDidNotDeclareIsRefusedOnTheLaunchersSide() {
        val bridge = CountingBridge()
        val rt = AppRuntime(app("""render(s => [pill("ask", async s => ({ a: await ask("hi") }))])"""), prelude, bridge, scope, backToBack)
            .also { it.start() }
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertTrue(shown.failed!!.message, shown.failed!!.message.contains("\"model\""))
        assertEquals(0, bridge.asked.get())
    }

    @Test fun aRenderThatNeverEndsIsInterruptedInTheProcess() {
        val rt = AppRuntime(app("""
            state({ spin: false })
            render(s => { while (s.spin) {} return [pill("spin", s => ({ spin: true }))] })
        """.trimIndent()), prelude, CountingBridge(), scope, backToBack).also { it.start() }
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        // Interrupted, the engine went on: the state was put back and the page drew again.
        assertEquals(listOf(Piece.Pill("spin", 0)), shown.page.pieces)
        assertTrue(rt.app.savedState() == null)
    }

    @Test fun askCallsAToolThroughTheEngineProcess() {
        val rt = AppRuntime(app("""
            state({ items: [] })
            render(s => s.items.map(i => words(i)))
            onTool("add_item", (s, args) => { s.items.push(args.name); return `added ${'$'}{args.name}` })
        """.trimIndent()), prelude, CountingBridge(), scope, backToBack).also { it.start() }
        rt.shown()
        val reply = runBlocking { withTimeout(10_000) { rt.callTool("add_item", """{"name": "eggs"}""") } }
        assertEquals("\"added eggs\"", reply)
        assertEquals(listOf(Piece.Words("eggs")), rt.shown { it.pieces.isNotEmpty() }.pieces)
        assertEquals("eggs", JSONObject(rt.app.savedState()!!).getJSONArray("items").getString(0))
    }

    @Test fun anErrorSaysWhereInTheScriptThroughTheProcess() {
        val rt = AppRuntime(app("state({})\nrender(s => [\n  words(s.items.map(i => i)),\n])"), prelude, CountingBridge(), scope, backToBack)
            .also { it.start() }
        val f = rt.failed()
        assertEquals(AppRuntime.During.Render, f.during)
        assertEquals(3, f.line)
    }

    // ---- a scripted engine: what a broken or hostile one sends -----------------------------------

    private val PAGE = """{"gen": 1, "words": false, "pieces": [{"t": "pill", "text": "go", "tap": 0}]}"""

    /** An engine process that answers as [answers] says, by kind; null leaves the call waiting. */
    private class Scripted(val answers: MutableMap<String, String?>) : EngineWire {
        lateinit var to: IsolatedEngine
        val sent = CopyOnWriteArrayList<String>()
        val reqs = ConcurrentHashMap<String, Int>()

        private fun op(kind: String, req: Int) {
            sent += kind
            reqs[kind] = req
            answers[kind]?.let { to.done(req, it) }
        }

        override fun start(code: Engine.Code, req: Int) = op("start", req)
        override fun draw(req: Int) = op("draw", req)
        override fun saved(req: Int) = op("saved", req)
        override fun restore(state: String, req: Int) = op("restore", req)
        override fun call(gen: Int, handler: Int, value: String, req: Int) = op("call", req)
        override fun hear(words: String, req: Int) = op("hear", req)
        override fun tool(name: String, args: String, req: Int) = op("tool", req)
        override fun interrupt(req: Int) { sent += "interrupt $req" }
        override fun answer(call: Int, value: String) { sent += "answer $call" }
        override fun refuse(call: Int, why: String) { sent += "refuse $call $why" }
        override fun stop() { sent += "stop" }
    }

    private fun scripted(vararg over: Pair<String, String?>): Scripted = Scripted(mutableMapOf<String, String?>(
        "start" to "", "draw" to PAGE, "saved" to "{}", "restore" to "", "call" to "true", "hear" to "true", "tool" to "\"ok\"",
    ).apply { putAll(over) })

    private val connected = AtomicInteger()

    private fun engines(make: () -> Scripted): Engines = Engines { host ->
        IsolatedEngine(host) { e -> connected.incrementAndGet(); make().also { it.to = e } }
    }

    private fun running(wire: Scripted, uses: Set<Use> = emptySet(), bridge: Bridge = CountingBridge()) =
        AppRuntime(app(uses = uses), prelude, bridge, scope, engines { wire }).also { it.start() }

    @Test fun anAnswerToNoCallIsDropped() {
        val wire = scripted("call" to null)
        val rt = running(wire)
        val page = rt.shown()
        rt.call(page.gen, 0)
        until { wire.reqs["call"] != null }
        val req = wire.reqs.getValue("call")
        wire.to.done(req + 100, "true")
        wire.to.failed(req - 1, "not this one", null, -1)
        assertTrue(rt.busy.value)
        wire.to.done(req, "true")
        until { wire.sent.count { it == "draw" } == 2 }
        until { !rt.busy.value }
        assertTrue(rt.view.value is AppRuntime.View.Shown && (rt.view.value as AppRuntime.View.Shown).failed == null)
    }

    @Test fun aHostCallIsCheckedAgainstUsesOnTheLaunchersSide() {
        val bridge = CountingBridge()
        val wire = scripted("call" to null)
        val rt = running(wire, emptySet(), bridge)
        rt.call(rt.shown().gen, 0)
        until { wire.reqs["call"] != null }
        wire.to.ask(7, "hi")
        wire.to.fetch(8, "https://example.com")
        until { wire.sent.any { it.startsWith("refuse 7") } && wire.sent.any { it.startsWith("refuse 8") } }
        assertTrue(wire.sent.first { it.startsWith("refuse 7") }.contains("\"model\""))
        assertTrue(wire.sent.first { it.startsWith("refuse 8") }.contains("\"network\""))
        assertEquals(0, bridge.asked.get())
        assertEquals(0, bridge.fetched.get())
    }

    @Test fun aHostCallOutOfTurnIsRefused() {
        val bridge = CountingBridge()
        val wire = scripted()
        val rt = running(wire, setOf(Use.Model), bridge)
        rt.shown()
        until { !rt.busy.value }
        wire.to.ask(3, "between turns")
        until { wire.sent.any { it.startsWith("refuse 3") } }
        assertEquals(0, bridge.asked.get())
    }

    @Test fun aDeclaredHostCallIsAnsweredByItsNumber() {
        val bridge = CountingBridge()
        val wire = scripted("call" to null)
        val rt = running(wire, setOf(Use.Model), bridge)
        rt.call(rt.shown().gen, 0)
        until { wire.reqs["call"] != null }
        wire.to.ask(41, "eggs")
        until { "answer 41" in wire.sent }
        assertEquals(1, bridge.asked.get())
    }

    @Test fun anOversizePageFailsTheApp() {
        val big = """{"gen": 1, "words": false, "pieces": [{"t": "words", "text": "${"x".repeat(AppRuntime.MAX_PAGE)}"}]}"""
        val rt = running(scripted("draw" to big))
        val f = rt.failed()
        assertEquals(AppRuntime.During.Render, f.during)
        assertTrue(f.message, f.message.contains("kB"))
    }

    @Test fun anOversizeStateIsNotSavedAndIsPutBack() {
        val wire = scripted("call" to null)
        val rt = running(wire)
        rt.call(rt.shown().gen, 0)
        until { wire.reqs["call"] != null }
        wire.answers["saved"] = "\"${"x".repeat(AppRuntime.MAX_STATE)}\""
        wire.to.done(wire.reqs.getValue("call"), "true")
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertTrue(shown.failed!!.message, shown.failed!!.message.contains("kB"))
        assertTrue("restore" in wire.sent)
        assertTrue(rt.app.savedState() == null)
    }

    @Test fun aMalformedAnswerFailsTheCall() {
        val rt = running(scripted("call" to "yes"))
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertEquals(IsolatedEngine.MALFORMED, shown.failed!!.message)
    }

    @Test fun aTimeoutTellsTheEngineToStop() {
        val wire = scripted()
        val rt = running(wire)
        rt.shown()
        wire.answers["draw"] = null
        rt.call(rt.shown().gen, 0)
        until { wire.sent.count { it == "draw" } >= 2 }
        val req = wire.reqs.getValue("draw")
        until { "interrupt $req" in wire.sent }
    }

    @Test fun theProcessDyingFailsTheAppAndReloadStartsItAgain() {
        val wire = scripted("call" to null)
        var wires = 0
        val rt = AppRuntime(app(), prelude, CountingBridge(), scope, engines { if (wires++ == 0) wire else scripted() }).also { it.start() }
        rt.call(rt.shown().gen, 0)
        until { wire.reqs["call"] != null }
        wire.to.gone()
        val f = rt.failed()
        assertEquals("it stopped running", f.message)
        assertEquals(AppRuntime.During.Handler("go"), f.during)
        // A call on it now goes nowhere, and an answer from the dead engine changes nothing.
        wire.to.done(wire.reqs.getValue("call"), "true")
        assertTrue(rt.view.value is AppRuntime.View.Failed)
        rt.reload()
        rt.shown()
        assertEquals(2, connected.get())
    }

    @Test fun anIdleProcessDyingFailsTheOpenApp() {
        val wire = scripted()
        val rt = running(wire)
        rt.shown()
        until { !rt.busy.value }
        wire.to.gone()
        assertEquals("it stopped running", rt.failed().message)
        until { "stop" in wire.sent }
    }

    @Test fun closingStopsTheEngineWhereItIs() {
        val wire = scripted("call" to null)
        val rt = running(wire)
        rt.call(rt.shown().gen, 0)
        until { wire.reqs["call"] != null }
        rt.close()
        until { "stop" in wire.sent }
        val interrupted = wire.sent.indexOf("interrupt ${wire.reqs.getValue("call")}")
        assertTrue(wire.sent.toString(), interrupted in 0 until wire.sent.indexOf("stop"))
    }
}
