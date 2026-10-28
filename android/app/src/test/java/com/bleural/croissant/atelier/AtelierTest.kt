package com.bleural.croissant.atelier

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Atelier's Core (atelier/): croissant.json, an app's id, the pieces, a repo as typed, and apps running in QuickJS. */
class AtelierTest {

    // ---- croissant.json -----------------------------------------------------------------

    @Test fun aManifestReadsWithItsDefaults() {
        val m = AppManifest.parse("""{"croissant": 1, "name": "water", "hue": "#1FA2FF", "glyph": "droplet", "uses": ["words"]}""").getOrThrow()
        assertEquals("water", m.name)
        assertEquals(0x1FA2FF, m.hue)
        assertEquals("droplet", m.glyph)
        assertEquals(1, m.croissant)
        assertEquals(setOf(Use.Words), m.uses)
        assertEquals("0", m.version)
    }

    @Test fun anIdInTheManifestIsIgnored() {
        val m = AppManifest.parse("""{"croissant": 1, "id": "Not An Id!", "name": "water", "hue": "#1FA2FF"}""").getOrThrow()
        assertEquals(AppManifest("water", 0x1FA2FF, null, "0", 1, emptySet()), m)
    }

    @Test fun aBadManifestSaysWhy() {
        fun why(json: String) = AppManifest.parse(json).exceptionOrNull()?.message
        assertEquals("croissant.json is not JSON", why("{"))
        assertEquals("croissant.json: no \"croissant\" version", why("""{"name": "w", "hue": "#000000"}"""))
        assertEquals("croissant.json: no \"croissant\" version", why("""{"kit": 1, "name": "w", "hue": "#000000"}"""))
        assertEquals("croissant.json: \"croissant\" is not a number", why("""{"croissant": "one", "name": "w", "hue": "#000000"}"""))
        assertEquals("croissant.json: \"croissant\" is not a number", why("""{"croissant": 0, "name": "w", "hue": "#000000"}"""))
        assertEquals("it needs a newer Croissant (croissant 2)", why("""{"croissant": 2, "name": "w", "hue": "#000000"}"""))
        assertEquals("croissant.json: no name", why("""{"croissant": 1, "hue": "#000000"}"""))
        assertEquals("croissant.json: hue is not #RRGGBB", why("""{"croissant": 1, "name": "w", "hue": "blue"}"""))
        assertEquals("croissant.json: uses \"camera\", which Croissant does not have", why("""{"croissant": 1, "name": "w", "hue": "#000000", "uses": ["camera"]}"""))
    }

    @Test fun aManifestWrittenBackReadsTheSame() {
        val m = AppManifest("bill", 0xFFE01A, "receipt", "1.2.0", 1, setOf(Use.Model, Use.Network),
            listOf(AppTool("split", "Split the bill", JSONObject().put("type", "object").put("properties", JSONObject()).toString(),
                listOf(ToolExample("split 84 three ways"), ToolExample("split the bill four ways", JSONObject().put("people", 4).toString())))))
        assertEquals(m, AppManifest.parse(AtelierStore.encodeManifest(m)).getOrThrow())
    }

    @Test fun anAppsToolsReadWithTheirDefaults() {
        val m = AppManifest.parse("""{"croissant": 1, "name": "shopping", "hue": "#5CF23A", "tools": [
            {"name": "add_item", "description": "Add an item to the list",
             "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
             "examples": ["add eggs to my shopping list"]},
            {"name": "clear", "description": "Clear the ticked items"}]}""").getOrThrow()
        assertEquals(listOf("add_item", "clear"), m.tools.map { it.name })
        assertEquals(listOf(ToolExample("add eggs to my shopping list")), m.tools[0].examples)
        assertEquals("object", JSONObject(m.tools[1].parameters).getString("type"))
        fun why(tools: String) = AppManifest.parse("""{"croissant": 1, "name": "s", "hue": "#000000", "tools": $tools}""").exceptionOrNull()?.message
        assertEquals("croissant.json: a tool's name is not lowercase letters, digits and _, up to 23", why("""[{"name": "Add", "description": "x"}]"""))
        assertEquals("croissant.json: tool add has no description", why("""[{"name": "add"}]"""))
        assertEquals("croissant.json: two tools have the same name", why("""[{"name": "a", "description": "x"}, {"name": "a", "description": "y"}]"""))
    }

    // ---- the pieces ---------------------------------------------------------------------

    @Test fun aPageReadsEveryPiece() {
        val page = AppPage.parse("""{"gen": 3, "words": true, "pieces": [
            {"t": "words", "text": "hello"},
            {"t": "big", "value": "5", "label": "glasses today"},
            {"t": "row", "text": "goal", "value": "8", "tap": null},
            {"t": "toggle", "text": "eggs", "on": true, "change": 0},
            {"t": "field", "value": "84", "hint": null, "keys": "number", "change": 1, "done": null},
            {"t": "pill", "text": "add a glass", "tap": 2}]}""")
        assertEquals(3, page.gen)
        assertTrue(page.takesWords)
        assertEquals(listOf(
            Piece.Words("hello"),
            Piece.Big("5", "glasses today"),
            Piece.Row("goal", "8", null),
            Piece.Toggle("eggs", true, 0),
            Piece.Field("84", null, Keys.Number, 1, null),
            Piece.Pill("add a glass", 2),
        ), page.pieces)
    }

    // ---- a repo as typed or scanned ----------------------------------------------------------

    @Test fun aRepoIsTypedOrLinked() {
        assertEquals(RepoRef("anna", "shopping"), RepoRef.parse("anna/shopping"))
        assertEquals(RepoRef("anna", "shopping", "v2"), RepoRef.parse("anna/shopping@v2"))
        assertEquals(RepoRef("anna", "shopping"), RepoRef.parse("https://github.com/anna/shopping"))
        assertEquals(RepoRef("anna", "shopping"), RepoRef.parse("github.com/anna/shopping.git"))
        assertEquals(RepoRef("anna", "shopping", "main"), RepoRef.parse("https://github.com/anna/shopping/tree/main"))
        for (s in listOf("", "shopping", "a/b/c", "anna/shopping@", "anna/shopping@a/b", "https://gitlab.com/a/b")) {
            assertNull(s, RepoRef.parse(s))
        }
    }

    // ---- what the GitHub reader takes from a commit's zip -------------------------------------

    private fun zip(vararg files: Pair<String, String>): java.io.InputStream {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("water-log-abc/"))
            for ((path, text) in files) {
                z.putNextEntry(java.util.zip.ZipEntry("water-log-abc/$path"))
                z.write(text.toByteArray())
            }
        }
        return java.io.ByteArrayInputStream(out.toByteArray())
    }

    private val JSON = """{"croissant": 1, "name": "water", "hue": "#1FA2FF"}"""

    @Test fun theReaderTakesTheTwoFilesAtTheRoot() {
        val files = GitHubSource.files(zip("croissant.json" to JSON, "croissant.js" to "render(s => [])",
            "README.md" to "hi", "sub/croissant.json" to "{}", "app.json" to "{}"))
        assertEquals(setOf("croissant.json", "croissant.js"), files.keys)
        val sha = "a".repeat(40)
        val staged = GitHubSource.stage(RepoRef("alice", "Water.Log"), sha, files)
        assertEquals("water-log", staged.id)
        assertEquals("render(s => [])", staged.script)
        assertEquals(AppSource.GitHub("alice/Water.Log", sha), staged.source)
    }

    @Test fun aRepoWithoutTheFilesIsNotAnApp() {
        fun why(vararg files: Pair<String, String>) =
            runCatching { GitHubSource.stage(RepoRef("a", "b"), "a".repeat(40), GitHubSource.files(zip(*files))) }.exceptionOrNull()
        val none = why("app.json" to JSON, "app.js" to "render(s => [])")
        assertTrue(none is AddFailure.NoApp)
        assertEquals("no croissant.json in it: not a Croissant app", none?.message)
        assertEquals("no croissant.js in it", why("croissant.json" to JSON)?.message)
        assertEquals("croissant.json: no \"croissant\" version", why("croissant.json" to """{"name": "w", "hue": "#000000"}""", "croissant.js" to "")?.message)
    }

    // ---- the tailnet and the LAN, out of reach -------------------------------------------------

    @Test fun privateAddressesAreRefused() {
        fun private(a: String) = DeviceBridge.isPrivate(java.net.InetAddress.getByName(a))
        for (a in listOf("127.0.0.1", "10.0.0.2", "192.168.1.20", "172.16.4.1", "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "fd7a:115c:a1e0::1", "0.0.0.0",
                "0.1.2.3", "198.18.0.1", "198.19.255.254", "240.0.0.1", "255.255.255.255", "fe80::1", "ff02::1", "::",
                "64:ff9b::10.0.0.1", "64:ff9b::127.0.0.1", "64:ff9b:1::1", "2002:c000:201::1", "2002:0a00:0001::1")) {
            assertTrue(a, private(a))
        }
        for (a in listOf("1.1.1.1", "100.128.0.1", "140.82.121.4", "2606:4700:4700::1111", "198.20.0.1", "223.255.255.254", "64:ff9b::1.1.1.1", "2003::1")) {
            assertFalse(a, private(a))
        }
    }

    @Test fun theDevicesOwnNetworksAreRefused() {
        fun at(a: String) = InetAddress.getByName(a)
        val nets = listOf(DeviceBridge.net(at("2001:db8:1:2::20"), 64), DeviceBridge.net(at("203.0.113.7"), 24), DeviceBridge.net(at("198.51.100.9"), 0))
        fun refused(a: String) = DeviceBridge.refused(at(a), nets)
        for (a in listOf("2001:db8:1:2::1", "2001:db8:1:2:ffff::1", "203.0.113.1", "203.0.113.7", "198.51.100.9", "64:ff9b::203.0.113.5")) assertTrue(a, refused(a))
        // A prefix wider than a LAN's is the address alone.
        for (a in listOf("2001:db8:1:3::1", "203.0.114.1", "198.51.100.10", "8.8.8.8")) assertFalse(a, refused(a))
        assertTrue(DeviceBridge.localNets().any { at("127.0.0.1") in it })
    }

    @Test fun aRefusedAddressIsNeverConnectedAndReadsAsUnreachable() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300
            val port = server.localPort
            val socket = DeviceBridge.PublicSockets.createSocket()
            assertTrue(runCatching { socket.connect(InetSocketAddress("127.0.0.1", port), 1000) }.exceptionOrNull() is IOException)
            assertFalse(socket.isConnected)
            val bridge = DeviceBridge({ _, _ -> "" }, "test", {})
            val a = app("render(s => [])", setOf(Use.Network))
            fun why(url: String) = runCatching { runBlocking { withTimeout(40_000) { bridge.fetch(a, url) } } }.exceptionOrNull()?.message
            assertEquals("fetch: 127.0.0.1 cannot be reached", why("http://127.0.0.1:$port/"))
            assertEquals("fetch: 127.0.0.1 cannot be reached", why("https://127.0.0.1:$port/"))
            assertTrue(why("http://[::ffff:127.0.0.1]:$port/")!!.endsWith(" cannot be reached"))
            assertEquals("fetch: localhost cannot be reached", why("http://localhost:$port/"))
            // A name that is nowhere says the same.
            assertEquals("fetch: nothing.invalid cannot be reached", why("http://nothing.invalid/"))
            assertTrue(runCatching { server.accept() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    // ---- apps running ----------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob())
    private val dirs = mutableListOf<File>()
    private val prelude = File("src/main/assets/atelier/prelude.js").readText()
    /** The script in this process: the engine process's own engine, without the process. */
    private val inProcess = Engines(::QuickJsEngine)

    @After fun tearDown() {
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private class FakeBridge : Bridge {
        val asked = mutableListOf<String>()
        override suspend fun ask(app: InstalledApp, text: String): String = "answer to $text".also { asked += text }
        override suspend fun fetch(app: InstalledApp, url: String) = Fetched(200, """{"n": 42}""")
        override fun cue(name: String) {}
    }

    private fun app(script: String, uses: Set<Use> = emptySet()): InstalledApp {
        val dir = Files.createTempDirectory("atelier").toFile().also { dirs += it }
        File(dir, AppManifest.SCRIPT).writeText(script)
        return InstalledApp(AppManifest("t", 0xFFFFFF, null, "1", 1, uses), dir, AppSource.Local, 0)
    }

    private fun running(script: String, uses: Set<Use> = emptySet(), bridge: Bridge = FakeBridge()) =
        AppRuntime(app(script, uses), prelude, bridge, scope, inProcess).also { it.start() }

    private fun <T : AppRuntime.View> AppRuntime.await(test: (AppRuntime.View) -> Boolean): T = runBlocking {
        @Suppress("UNCHECKED_CAST")
        withTimeout(10_000) { view.first(test) } as T
    }

    private fun AppRuntime.shown(test: (AppPage) -> Boolean = { true }): AppPage =
        await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && test(it.page) }.page

    private val COUNTER = """
        state({ count: 0 })
        render(s => [
          big(s.count, "taps"),
          pill("tap", s => ({ count: s.count + 1 })),
          s.count > 1 && words("more than one"),
        ])
    """.trimIndent()

    @Test fun anAppDrawsItsPageFromItsState() {
        val rt = running(COUNTER)
        val page = rt.shown()
        assertEquals(listOf(Piece.Big("0", "taps"), Piece.Pill("tap", 0)), page.pieces)
        assertFalse(page.takesWords)
    }

    @Test fun aTapRunsItsHandlerAndTheStateIsKept() {
        val rt = running(COUNTER)
        var page = rt.shown()
        rt.call(page.gen, 0)
        page = rt.shown { it.pieces.first() == Piece.Big("1", "taps") }
        rt.call(page.gen, 0)
        page = rt.shown { it.pieces.first() == Piece.Big("2", "taps") }
        assertEquals(Piece.Words("more than one"), page.pieces.last())
        assertEquals(2, JSONObject(rt.app.savedState()!!).getInt("count"))
    }

    @Test fun theSavedStateIsWhereTheAppStartsNextTime() {
        val first = running(COUNTER)
        first.call(first.shown().gen, 0)
        first.shown { it.pieces.first() == Piece.Big("1", "taps") }
        first.close()
        val again = AppRuntime(first.app, prelude, FakeBridge(), scope, inProcess).also { it.start() }
        assertEquals(Piece.Big("1", "taps"), again.shown().pieces.first())
    }

    @Test fun aTapOnAPageThatChangedIsDropped() {
        val rt = running(COUNTER)
        val stale = rt.shown()
        rt.call(stale.gen, 0)
        rt.shown { it.gen > stale.gen }
        rt.call(stale.gen, 0)
        rt.call(stale.gen + 1, 0)
        assertEquals(Piece.Big("2", "taps"), rt.shown { it.pieces.first() == Piece.Big("2", "taps") }.pieces.first())
    }

    @Test fun anAppTakesWordsAndAsksTheModel() {
        val bridge = FakeBridge()
        val rt = running("""
            state({ heard: [] })
            render(s => s.heard.map(w => words(w)))
            onWords(async (s, w) => ({ heard: [...s.heard, await ask(w)] }))
        """.trimIndent(), setOf(Use.Words, Use.Model), bridge)
        assertTrue(rt.shown().takesWords)
        assertTrue(rt.hear("eggs"))
        val page = rt.shown { it.pieces.isNotEmpty() }
        assertEquals(listOf(Piece.Words("answer to eggs")), page.pieces)
        assertEquals(listOf("eggs"), bridge.asked)
    }

    @Test fun fetchGivesJson() {
        val rt = running("""
            state({ n: 0 })
            render(s => [big(s.n, "n"), pill("get", async s => ({ n: (await (await fetch("https://example.com")).json()).n }))])
        """.trimIndent(), setOf(Use.Network))
        rt.call(rt.shown().gen, 0)
        assertEquals(Piece.Big("42", "n"), rt.shown { it.pieces.first() == Piece.Big("42", "n") }.pieces.first())
    }

    @Test fun wordsAreOnlyForAnAppThatUsesThem() {
        val rt = running("""
            render(s => [words("hi")])
            onWords((s, w) => ({ heard: w }))
        """.trimIndent())
        val failed = rt.await<AppRuntime.View.Failed> { it is AppRuntime.View.Failed }.failure
        assertEquals(AppRuntime.During.Start, failed.during)
        assertEquals("it does not say it uses \"words\" in croissant.json", failed.message)
        assertFalse(rt.hear("eggs"))
    }

    /** Ten of each at once: four of each run, the rest wait, and all of them come back. */
    @Test fun fetchAndAskRunFourAtATime() {
        val bridge = object : Bridge {
            val gate = CompletableDeferred<Unit>()
            val fetching = AtomicInteger()
            val asking = AtomicInteger()
            val most = AtomicInteger()
            override suspend fun ask(app: InstalledApp, text: String): String {
                asking.incrementAndGet(); most.accumulateAndGet(maxOf(asking.get(), fetching.get()), ::maxOf)
                gate.await(); asking.decrementAndGet(); return text
            }
            override suspend fun fetch(app: InstalledApp, url: String): Fetched {
                fetching.incrementAndGet(); most.accumulateAndGet(maxOf(asking.get(), fetching.get()), ::maxOf)
                gate.await(); fetching.decrementAndGet(); return Fetched(200, "1")
            }
            override fun cue(name: String) {}
        }
        val rt = running("""
            state({ n: 0 })
            render(s => [big(s.n, "n"), pill("go", async s => {
              const ten = [...Array(10).keys()];
              const got = await Promise.all([...ten.map(i => fetch("https://example.com/" + i)), ...ten.map(i => ask("q" + i))]);
              return { n: got.length };
            })])
        """.trimIndent(), setOf(Use.Network, Use.Model), bridge)
        rt.call(rt.shown().gen, 0)
        runBlocking { withTimeout(10_000) { while (bridge.fetching.get() < 4 || bridge.asking.get() < 4) kotlinx.coroutines.delay(20) } }
        Thread.sleep(300)
        assertEquals(4, bridge.most.get())
        assertEquals(4, bridge.fetching.get())
        assertEquals(4, bridge.asking.get())
        bridge.gate.complete(Unit)
        rt.shown { it.pieces.first() == Piece.Big("20", "n") }
    }

    @Test fun aSavedStateIsReadNeverRun() {
        val a = app("""
            state({ n: 0 })
            render(s => [big(globalThis.ran ? "ran" : s.n, "n")])
        """.trimIndent())
        // JSON up front, which the file's check takes, and code after it.
        File(a.dir, InstalledApp.STATE).writeText("""{"n": 5}, globalThis.ran = true""")
        assertTrue(a.savedState() != null)
        val rt = AppRuntime(a, prelude, FakeBridge(), scope, inProcess).also { it.start() }
        assertEquals(Piece.Big("0", "n"), rt.shown().pieces.first())
    }

    @Test fun whatAnAppDidNotDeclareIsRefused() {
        val rt = running("""
            render(s => [pill("ask", async s => ({ a: await ask("hi") }))])
        """.trimIndent())
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertEquals(AppRuntime.During.Handler("ask"), shown.failed!!.during)
        assertTrue(shown.failed!!.message, shown.failed!!.message.contains("\"model\""))
    }

    @Test fun aRenderErrorSaysWhereInTheScript() {
        val rt = running("state({})\nrender(s => [\n  words(s.items.map(i => i)),\n])")
        val failed = rt.await<AppRuntime.View.Failed> { it is AppRuntime.View.Failed }.failure
        assertEquals(AppRuntime.During.Render, failed.during)
        assertEquals(3, failed.line)
    }

    @Test fun aHandlerErrorKeepsThePage() {
        val rt = running("""render(s => [pill("break", s => { throw new Error("broken") })])""")
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertEquals(listOf(Piece.Pill("break", 0)), shown.page.pieces)
        assertTrue(shown.failed!!.message, shown.failed!!.message.contains("broken"))
    }

    @Test fun aHandlerWhoseStateCannotBeDrawnIsUndone() {
        val rt = running("""
            state({ broken: false, n: 1 })
            render(s => s.broken ? [words(s.nothing.here)] : [big(s.n, "n"), pill("break", s => ({ broken: true, n: 2 }))])
        """.trimIndent())
        rt.call(rt.shown().gen, 0)
        val shown = rt.await<AppRuntime.View.Shown> { it is AppRuntime.View.Shown && it.failed != null }
        assertEquals(AppRuntime.During.Handler("break"), shown.failed!!.during)
        assertEquals(Piece.Big("1", "n"), shown.page.pieces.first())
        assertNull(rt.app.savedState())
    }

    @Test fun askCallsAToolWithThePageUpAndGetsItsReply() {
        val rt = running("""
            state({ items: [] })
            render(s => s.items.map(i => words(i)))
            onTool("add_item", (s, args) => { s.items.push(args.name); return `added ${'$'}{args.name}` })
        """.trimIndent())
        rt.shown()
        val reply = runBlocking { withTimeout(10_000) { rt.callTool("add_item", """{"name": "eggs"}""") } }
        assertEquals("\"added eggs\"", reply)
        assertEquals(listOf(Piece.Words("eggs")), rt.shown { it.pieces.isNotEmpty() }.pieces)
        assertEquals("eggs", JSONObject(rt.app.savedState()!!).getJSONArray("items").getString(0))
    }

    @Test fun aToolTheAppDoesNotHandleSaysSo() {
        val rt = running("render(s => [words(\"hi\")])")
        rt.shown()
        val e = runCatching { runBlocking { withTimeout(10_000) { rt.callTool("nope", "{}") } } }.exceptionOrNull()
        assertTrue(e?.message, e is BadApp && e.message!!.contains("onTool(\"nope\")"))
    }

    @Test fun anExampleCanNameTheArgumentsItExpects() {
        val m = AppManifest.parse("""{"croissant": 1, "name": "shopping", "hue": "#5CF23A", "tools": [
            {"name": "add_item", "description": "Add an item", "examples": [
              "put milk on the list", {"say": "add eggs to my shopping list", "args": {"name": "eggs"}}]}]}""").getOrThrow()
        assertEquals(listOf(ToolExample("put milk on the list"), ToolExample("add eggs to my shopping list", """{"name":"eggs"}""")),
            m.tools[0].examples)
        fun why(ex: String) = AppManifest.parse("""{"croissant": 1, "name": "s", "hue": "#000000", "tools": [
            {"name": "a", "description": "x", "examples": [$ex]}]}""").exceptionOrNull()?.message
        assertEquals("croissant.json: an example of tool a has no say", why("""{"args": {"x": 1}}"""))
        assertEquals("croissant.json: an example of tool a has args that are not an object", why("""{"say": "hi", "args": "x"}"""))
    }

    @Test fun fetchRefusesAnythingButAPlainGet() {
        val rt = running("""
            state({ said: "" })
            render(s => [pill("post", async s => {
              try { await fetch("https://example.com", { method: "POST", body: "x" }); return { said: "sent" } }
              catch (e) { return { said: e.message } }
            }), s.said && words(s.said)])
        """.trimIndent(), setOf(Use.Network))
        rt.call(rt.shown().gen, 0)
        assertEquals(Piece.Words("fetch sends a plain GET: no method, headers or body"),
            rt.shown { it.pieces.size > 1 }.pieces.last())
    }

    @Test fun consoleLogIsThere() {
        val rt = running("""
            console.log("starting", { n: 1 })
            render(s => { console.warn("drawing"); return [words("ok")] })
        """.trimIndent())
        assertEquals(listOf(Piece.Words("ok")), rt.shown().pieces)
    }

    @Test fun aScriptThatDoesNotLoadFailsToStart() {
        val rt = running("render(s => [")
        val failed = rt.await<AppRuntime.View.Failed> { it is AppRuntime.View.Failed }.failure
        assertEquals(AppRuntime.During.Start, failed.during)
    }

    @Test fun aRenderThatNeverEndsIsStopped() {
        val rt = running("render(s => { while (true) {} })")
        val failed = rt.await<AppRuntime.View.Failed> { it is AppRuntime.View.Failed }.failure
        assertEquals(AppRuntime.During.Render, failed.during)
    }

    // ---- an app's id ---------------------------------------------------------------------

    @Test fun anIdIsTheNameMadeSafe() {
        assertEquals("water-log", AppId.of("water-log"))
        assertEquals("water-log", AppId.of("Water_Log"))
        assertEquals("croissant-water", AppId.of("croissant.water"))
        assertEquals("a-b", AppId.of("--a...b--"))
        assertEquals("caf", AppId.of("Café"))
        assertEquals("2048", AppId.of("2048"))
        assertEquals("app", AppId.of("..."))
        assertEquals("app", AppId.of(""))
        assertEquals("a".repeat(40), AppId.of("a".repeat(50)))
        assertEquals("a".repeat(39), AppId.of("a".repeat(39) + "-b"))
        for (n in listOf("water-log", "Water_Log", "--a...b--", "x".repeat(60), "")) assertTrue(n, AppId.valid(AppId.of(n)))
        assertFalse(AppId.valid("Water"))
        assertFalse(AppId.valid("-water"))
        assertFalse(AppId.valid("a".repeat(41)))
    }

    @Test fun aTakenIdGetsTheNextNumber() {
        assertEquals("water", AppId.free("water") { false })
        assertEquals("water-2", AppId.free("water") { it == "water" })
        assertEquals("water-3", AppId.free("water") { it in setOf("water", "water-2") })
        val long = "a".repeat(40)
        assertEquals("a".repeat(38) + "-2", AppId.free(long) { it == long })
        assertEquals("a".repeat(37) + "-10", AppId.free(long) { it.length == 40 && !it.endsWith("-10") })
    }

    // ---- the store ---------------------------------------------------------------------------

    private fun store(): AtelierStore = AtelierStore(Files.createTempDirectory("atelier").toFile().also { dirs += it })

    private fun gh(repo: String, c: Char = 'a') = AppSource.GitHub(repo, c.toString().repeat(40))

    /** As the add page has it: fetched under the repo's name, then placed by the store. */
    private fun AtelierStore.found(m: AppManifest, source: AppSource.GitHub, script: String = "render(s => [])") =
        place(StagedApp(AppId.of(source.repo.substringAfter('/')), m, script, source))

    @Test fun anAppFromGitHubIsNamedForItsRepo() {
        val store = store()
        val m = AppManifest("water", 0x1FA2FF, "droplet", "1", 1, emptySet())
        assertEquals("water-log", store.found(m, gh("alice/Water.Log")).id)
        assertEquals("water-log", store.install(store.found(m, gh("alice/Water.Log"))).id)
        assertEquals(File(store.appsDir, "water-log"), store.get("water-log")!!.dir)
    }

    @Test fun anInstallKeepsTheStateOfTheAppItUpdates() {
        val store = store()
        val m = AppManifest("water", 0x1FA2FF, "droplet", "1", 1, emptySet())
        val first = store.install(store.found(m, gh("anna/water")), now = 5)
        first.saveState("""{"count": 3}""")
        val second = store.install(store.found(m.copy(version = "2"), gh("Anna/Water", 'b'), "render(s => [words(1)])"), now = 9)
        assertEquals("water", second.id)
        assertEquals("2", second.manifest.version)
        assertEquals(5L, second.installed)
        assertEquals("""{"count": 3}""", second.savedState())
        assertEquals(gh("Anna/Water", 'b'), second.source)
        assertEquals(listOf("water"), store.apps.value.map { it.id })
        store.delete("water")
        assertTrue(store.apps.value.isEmpty())
    }

    @Test fun anotherRepoOfTheSameNameIsAnotherApp() {
        val store = store()
        val m = AppManifest("water", 0x1FA2FF, null, "1", 1, emptySet())
        store.install(store.found(m, gh("anna/water")), now = 1).saveState("""{"anna": true}""")
        val bob = store.install(store.found(m, gh("bob/water")), now = 2)
        assertEquals("water-2", bob.id)
        assertNull(bob.savedState())
        assertEquals("""{"anna": true}""", store.get("water")!!.savedState())
        assertEquals(gh("anna/water"), store.get("water")!!.source)
        // Each keeps its own through an update, whichever was added first.
        assertEquals("water-2", store.install(store.found(m, gh("bob/water", 'c'))).id)
        assertEquals("water", store.install(store.found(m, gh("anna/water", 'c'))).id)
        // One pushed from the CLI holds its name too.
        store.install(StagedApp("tally", m, "render(s => [])", AppSource.Local))
        assertEquals("tally-2", store.found(m, gh("carol/tally")).id)
        assertEquals(listOf("water", "water-2", "tally"), store.apps.value.map { it.id })
    }

    @Test fun anUpdateKeepsItsIdWhateverItIs() {
        val store = store()
        val m = AppManifest("water", 0x1FA2FF, null, "1", 1, emptySet())
        store.install(store.found(m, gh("anna/water")))
        assertEquals("water-2", store.install(store.found(m, gh("bob/water"))).id)
        store.delete("water")
        // anna's gone, so bob's repo is the only water: still water-2, its folder and state kept.
        assertEquals("water-2", store.found(m, gh("bob/water", 'd')).id)
        assertEquals("water", store.found(m, gh("dave/water")).id)
    }

    @Test fun aDisabledAppStaysDisabledThroughAnUpdate() {
        val store = store()
        val m = AppManifest("water", 0x1FA2FF, null, "1", 1, emptySet())
        store.install(StagedApp("water", m, "render(s => [])", AppSource.Local))
        assertTrue(store.get("water")!!.enabled)
        store.setEnabled("water", false)
        assertFalse(store.get("water")!!.enabled)
        store.install(StagedApp("water", m.copy(version = "2"), "render(s => [])", AppSource.Local))
        assertFalse(store.get("water")!!.enabled)
        store.setEnabled("water", true)
        assertTrue(store.get("water")!!.enabled)
    }

    @Test fun aPushedFolderIsAnAppByItsName() {
        val root = Files.createTempDirectory("atelier").toFile().also { dirs += it }
        for (name in listOf("tally", "Not-An-Id", "old")) File(root, "apps/$name").apply { mkdirs() }.also { d ->
            File(d, if (name == "old") "app.json" else AppManifest.FILE).writeText("""{"croissant": 1, "id": "water", "name": "tally", "hue": "#1FA2FF"}""")
            File(d, if (name == "old") "app.js" else AppManifest.SCRIPT).writeText("render(s => [])")
        }
        val store = AtelierStore(root)
        store.scan()
        assertEquals(listOf("tally"), store.apps.value.map { it.id })
        assertEquals(AppSource.Local, store.get("tally")!!.source)
    }

    @Test fun aStoredManifestHasTheVersionAndNoId() {
        val o = JSONObject(AtelierStore.encodeManifest(AppManifest("water", 0x1FA2FF, null, "1", 1, emptySet())))
        assertEquals(1, o.getInt("croissant"))
        assertFalse(o.has("id"))
        assertFalse(o.has("kit"))
    }
}
