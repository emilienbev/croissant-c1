package com.bleural.croissant.atelier

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * What an app reaches beyond its page, the device's side of the prelude's `ask`, `fetch` and
 * `cue`. [AppRuntime] checks the app's `uses` before any of these is called.
 */
interface Bridge {
    /** The chat model's answer to [text], plain words, asked by the C1 itself. */
    suspend fun ask(app: InstalledApp, text: String): String
    /** A GET of [url] on the internet, never the tailnet or the LAN. */
    suspend fun fetch(app: InstalledApp, url: String): Fetched
    /** One of the device's sounds by name; an unknown name plays nothing. */
    fun cue(name: String)
}

data class Fetched(val status: Int, val body: String)

/**
 * One app running: its `croissant.js` in an [Engine] of its own (in the launcher, a QuickJS in
 * the isolated engine process: [IsolatedEngines]), from its page opening to its page going.
 * [view] is what the page shows; [call] and [hear] are a touch and a hold's words.
 * Every call runs on its own, in order: the state a handler sees is the one the last left.
 * All of it runs on one thread of the app's own, never the main thread. Everything the engine
 * hands back is checked here: its host calls against `uses`, its page and state against
 * [MAX_PAGE] and [MAX_STATE], and every call under a timeout.
 *
 * Apps never run while closed: [close] frees the engine, and the state is already on disk,
 * written after every handler.
 */
class AppRuntime(
    val app: InstalledApp,
    private val prelude: String,
    private val bridge: Bridge,
    private val scope: CoroutineScope,
    private val engines: Engines,
) {
    sealed interface View {
        data object Loading : View
        /** The page; [failed] when the last touch or hold ran into an error and the page stayed. */
        data class Shown(val page: AppPage, val failed: Failure? = null) : View
        /** The script could not start or draw its page, or its engine stopped: nothing to show but why. */
        data class Failed(val failure: Failure) : View
    }

    /** An error in the app, as its page says it: [message], what it was doing, and where. */
    data class Failure(val message: String, val during: During, val line: Int?)

    sealed interface During {
        data object Start : During
        data object Render : During
        /** A piece's handler: [label] is the piece's text. */
        data class Handler(val label: String) : During
        data object Words : During
    }

    private val _view = MutableStateFlow<View>(View.Loading)
    val view: StateFlow<View> = _view

    /** A handler is running (an `ask` or a `fetch` can take seconds): touches wait their turn. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val manifest get() = app.manifest
    private val thread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "atelier-${app.id}") }.asCoroutineDispatcher()
    private val turn = Mutex()
    /** Everything this app has going, cancelled when it closes. */
    private val work = SupervisorJob(scope.coroutineContext[Job])
    private var engine: Engine? = null
    private var host: Host? = null
    @Volatile private var closed = false
    /** What the engine is doing, for the page if it stops under it. */
    @Volatile private var doing: During? = null
    private var starting: Job? = null
    /** So that a `Promise.all` of many cannot take every thread or the memory: the rest wait their turn. */
    private val fetching = Semaphore(AT_ONCE)
    private val asking = Semaphore(AT_ONCE)

    /** Load the script and draw the first page. Again, after a [Failure]: [reload]. */
    fun start() {
        starting = scope.launch(thread + work) { turn.withLock { load() } }
    }

    /** Throw the engine away and start the script again from its saved state. */
    fun reload() {
        scope.launch(thread + work) {
            starting?.join()
            turn.withLock {
                closeEngine()
                _view.value = View.Loading
                load()
            }
        }
    }

    /**
     * A touch on the page drawn as [gen]: [handler] is the piece's, [value] what it carries
     * (a toggle's new state, a field's text, or nothing). A page that has changed since drops it.
     */
    fun call(gen: Int, handler: Int, value: Any? = null) {
        val label = labelOf(handler)
        run(During.Handler(label)) { e -> e.call(gen, handler, literal(value)) }
    }

    /** A hold's [words], for an app that uses `words` and has an `onWords`; false when it has not. */
    fun hear(words: String): Boolean {
        val shown = _view.value as? View.Shown ?: return false
        if (Use.Words !in manifest.uses || !shown.page.takesWords) return false
        run(During.Words) { e -> e.hear(words) }
        return true
    }

    /**
     * Ask's call of [tool] (one of croissant.json's `tools`), with its arguments as a JSON
     * object: the app's `onTool` runs with the page up, the page is drawn again, and what
     * it returned comes back as JSON for the model. Its state is saved as a touch's is, and
     * a state that cannot be drawn is undone. Throws [BadApp] with why when it fails.
     */
    suspend fun callTool(tool: String, args: String): String {
        val json = try {
            JSONObject(args).toString()
        } catch (e: Exception) {
            throw BadApp("its arguments are not a JSON object")
        }
        if (json.length > MAX_STATE) throw BadApp("its arguments are over ${MAX_STATE / 1024} kB")
        starting?.join()
        return withContext(thread) {
            turn.withLock {
                val e = engine ?: throw BadApp("it is not running")
                if (_view.value is View.Failed) throw BadApp("it ran into an error and is not running")
                _busy.value = true
                doing = During.Handler(tool)
                try {
                    val before = withTimeout(RENDER_MS) { e.saved() }
                    val reply = withTimeout(HANDLER_MS) { e.tool(tool, json) }
                    _view.value = View.Shown(drawAndSave(e, before))
                    reply
                } catch (x: CancellationException) {
                    if (closed) throw x
                    fail(During.Handler(tool), BadApp("it took longer than ${HANDLER_MS / 1000} seconds"))
                    throw BadApp("it took too long")
                } catch (x: Throwable) {
                    if (x is BadApp && _view.value is View.Failed) throw x
                    val f = failure(During.Handler(tool), x)
                    fail(During.Handler(tool), x)
                    throw BadApp(f.message)
                } finally {
                    _busy.value = false
                    doing = null
                }
            }
        }
    }

    /** Stop the app: what it was doing is cancelled, and the engine told to stop where it is. */
    fun close() {
        closed = true
        work.cancel()
        scope.launch(thread) {
            turn.withLock { closeEngine() }
            thread.close()
        }
    }

    private fun run(during: During, block: suspend (Engine) -> Boolean) {
        scope.launch(thread + work) {
            turn.withLock {
                val e = engine ?: return@withLock
                _busy.value = true
                doing = during
                try {
                    // The state as it last drew: a handler whose state cannot be drawn is
                    // undone, so a bad state is never saved for the app to open on again.
                    val before = withTimeout(RENDER_MS) { e.saved() }
                    val changed = withTimeout(HANDLER_MS) { block(e) }
                    if (changed) _view.value = View.Shown(drawAndSave(e, before))
                } catch (x: CancellationException) {
                    if (closed) throw x
                    fail(during, BadApp("it took longer than ${HANDLER_MS / 1000} seconds"))
                } catch (x: Throwable) {
                    fail(during, x)
                } finally {
                    _busy.value = false
                    doing = null
                }
            }
        }
    }

    /** The page after a handler, its state saved; one that cannot be drawn or saved puts the state back as [before]. */
    private suspend fun drawAndSave(e: Engine, before: String): AppPage = try {
        render(e).also { save(e) }
    } catch (x: Throwable) {
        if (x is CancellationException && closed) throw x
        if (x !is EngineGone) withTimeout(RENDER_MS) { e.restore(before) }
        throw x
    }

    private suspend fun load() {
        val h = Host()
        host = h
        val e = engines.engine(h).also { engine = it }
        doing = During.Start
        try {
            val code = Engine.Code(prelude, app.script(), app.savedState(), Use.Words in manifest.uses)
            withTimeout(START_MS) { e.start(code) }
        } catch (x: Throwable) {
            if (x is CancellationException && closed) throw x
            _view.value = View.Failed(failure(During.Start, if (x is CancellationException) BadApp("it took too long to start") else x).also(::log))
            return
        } finally {
            doing = null
        }
        try {
            _view.value = View.Shown(render(e))
        } catch (x: Throwable) {
            if (x is CancellationException && closed) throw x
            _view.value = View.Failed(failure(During.Render, x).also(::log))
        }
    }

    /** Every failure in the device's log too, where `croissant atelier run` shows it. */
    private fun log(f: Failure) {
        val doing = when (val d = f.during) {
            During.Start -> "starting"
            During.Render -> "render"
            During.Words -> "onWords"
            is During.Handler -> "\"${d.label}\""
        }
        Log.e(TAG, "${app.id}: $doing: ${f.message}" + (f.line?.let { " (${AppManifest.SCRIPT} line $it)" } ?: ""))
    }

    /** An app that does not use `words` takes none, whatever its page says. */
    private suspend fun render(e: Engine): AppPage {
        val json = withTimeout(RENDER_MS) { e.draw() }
        if (json.length > MAX_PAGE) throw BadApp("its page is over ${MAX_PAGE / 1024} kB")
        return AppPage.parse(json).let { if (Use.Words in manifest.uses) it else it.copy(takesWords = false) }
    }

    private suspend fun save(e: Engine) {
        val state = withTimeout(RENDER_MS) { e.saved() }
        if (state.length > MAX_STATE) throw BadApp("its state is over ${MAX_STATE / 1024} kB")
        app.saveState(state)
    }

    /** A handler's error keeps the page up, with a word on it; a render's, or the engine stopping, leaves only the error. */
    private suspend fun fail(during: During, x: Throwable) {
        val f = failure(during, x).also(::log)
        val e = engine
        val page = (_view.value as? View.Shown)?.page
        _view.value = if (page == null || e == null || x is EngineGone) View.Failed(f) else try {
            View.Shown(render(e), f)
        } catch (again: Throwable) {
            View.Failed(failure(During.Render, again))
        }
    }

    /** The engine's process died: the page says so at once, and the engine goes once nothing waits on it. */
    private fun lost(h: Host) {
        if (closed || host !== h) return
        val f = Failure(EngineGone().message!!, doing ?: During.Render, null)
        _view.value = View.Failed(f)
        scope.launch(thread + work) {
            turn.withLock {
                if (host !== h) return@withLock
                log(f)
                closeEngine()
                _view.value = View.Failed(f)
            }
        }
    }

    private fun needs(use: Use) {
        if (use !in manifest.uses) throw BadApp(undeclared(use))
    }

    private fun labelOf(handler: Int): String {
        val page = (_view.value as? View.Shown)?.page ?: return ""
        return page.pieces.firstNotNullOfOrNull { p ->
            when (p) {
                is Piece.Row -> p.text.takeIf { p.tap == handler }
                is Piece.Toggle -> p.text.takeIf { p.change == handler }
                is Piece.Field -> (p.hint ?: p.value).takeIf { p.change == handler || p.done == handler }
                is Piece.Pill -> p.text.takeIf { p.tap == handler }
                else -> null
            }
        }.orEmpty()
    }

    private fun closeEngine() {
        engine?.let { e -> engine = null; host = null; runCatching { e.close() } }
    }

    /**
     * The engine's calls out, checked here whatever the engine is: what croissant.json does not
     * declare is refused, four of each at once, and nothing past the caps reaches the bridge.
     */
    private inner class Host : Engine.Host {
        override suspend fun ask(text: String): String {
            needs(Use.Model)
            if (text.length > MAX_STATE) throw BadApp("ask: the question is over ${MAX_STATE / 1024} kB")
            return asking.withPermit { bridge.ask(app, text) }
        }

        override suspend fun fetch(url: String): String {
            needs(Use.Network)
            if (url.length > MAX_URL) throw BadApp("fetch: the address is over $MAX_URL characters")
            val r = fetching.withPermit { bridge.fetch(app, url) }
            return JSONObject().put("status", r.status).put("body", r.body).toString()
        }

        override fun cue(name: String) {
            if (name.length <= MAX_CUE) bridge.cue(name)
        }

        override fun log(level: String, line: String) {
            val l = "${app.id}: ${line.take(MAX_LOG)}"
            when (level) {
                "error" -> Log.e(TAG, l)
                "warn" -> Log.w(TAG, l)
                else -> Log.i(TAG, l)
            }
        }

        override fun gone() = lost(this)
    }

    companion object {
        /** The log tag `croissant atelier run` follows. */
        const val TAG = "Atelier"
        const val START_MS = 5_000L
        const val RENDER_MS = 2_000L
        /** A handler may wait on the model or the network. */
        const val HANDLER_MS = 60_000L
        const val MEMORY = 32L * 1024 * 1024
        /** The `fetch` calls, and apart the `ask` calls, one app has going at once. */
        const val AT_ONCE = 4
        /** A page as `render` returns it, and the state as it is saved, in characters of JSON. */
        const val MAX_PAGE = 64 * 1024
        const val MAX_STATE = 64 * 1024
        const val MAX_URL = 8 * 1024
        const val MAX_LOG = 2000
        const val MAX_CUE = 64

        fun undeclared(use: Use) = "it does not say it uses \"${use.key}\" in ${AppManifest.FILE}"

        /** [v] as a JavaScript literal: JSON, which JavaScript reads as it is. Saved state goes in as a string, never as code. */
        fun literal(v: Any?): String = when (v) {
            null -> "undefined"
            is Boolean, is Int, is Long, is Double -> v.toString()
            else -> JSONObject.quote(v.toString())
        }

        fun failure(during: During, e: Throwable): Failure = when (e) {
            is JsError -> Failure(e.message ?: "an error", during, e.line.takeIf { e.file == AppManifest.SCRIPT })
            else -> Failure(e.message ?: e.javaClass.simpleName, during, null)
        }
    }
}
