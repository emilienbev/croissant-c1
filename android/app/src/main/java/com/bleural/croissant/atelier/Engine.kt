package com.bleural.croissant.atelier

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * One app's script running: the prelude's `__start`, `__draw`, `__call` and the rest, each
 * a call that ends with its answer. [AppRuntime] makes them one at a time, under its timeouts,
 * and cancelling one stops the script where it is.
 *
 * In the launcher the engine is [IsolatedEngine], in a process of its own (AtelierService.kt);
 * [QuickJsEngine] is the script itself, run there, or in the JVM tests in-process.
 */
interface Engine {
    /** Load [code] and start the app on its saved state. */
    suspend fun start(code: Code)
    /** `__draw()`: the page as JSON. */
    suspend fun draw(): String
    /** `__saved()`: the state as JSON. */
    suspend fun saved(): String
    /** `__start(state)`: back to [state], as JSON. */
    suspend fun restore(state: String)
    /** `__call(gen, handler, value)`, [value] a JavaScript literal ([AppRuntime.literal]); true when the state may have changed. */
    suspend fun call(gen: Int, handler: Int, value: String): Boolean
    /** `__hear(words)`. */
    suspend fun hear(words: String): Boolean
    /** `__tool(name, args)`, [args] a JSON object; the reply as JSON. */
    suspend fun tool(name: String, args: String): String
    /** Free it; any call still going is stopped. */
    fun close()

    /** What the script reaches beyond itself, the prelude's `__ask`, `__fetch`, `__cue` and `__log`: the launcher's, which checks each. */
    interface Host {
        suspend fun ask(text: String): String
        /** `{"status": ..., "body": ...}`. */
        suspend fun fetch(url: String): String
        fun cue(name: String)
        fun log(level: String, line: String)
        /** The engine went (its process died): nothing it was doing will answer. */
        fun gone()
    }

    /** What an app starts on. [words]: it uses `words`, so `onWords` is not refused. */
    data class Code(val prelude: String, val script: String, val saved: String?, val words: Boolean)
}

/** Where engines come from: [IsolatedEngines] in the launcher, `QuickJsEngine` in the JVM tests. */
fun interface Engines {
    fun engine(host: Engine.Host): Engine
}

/** An error in the script: its first line, and where ([file] the script's name, [line] in it), as QuickJS said it. */
class JsError(message: String, val file: String?, val line: Int?) : Exception(message)

/** The engine's process is gone: the app stopped. */
class EngineGone : Exception("it stopped running")

/**
 * The script in QuickJS, on a thread of its own: QuickJS is not thread-safe, and a script that
 * never ends holds that thread only. Cancelling a call interrupts the script.
 */
class QuickJsEngine(private val host: Engine.Host) : Engine {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "atelier-js") }
    private val thread = executor.asCoroutineDispatcher()
    private var js: QuickJs? = null

    override suspend fun start(code: Engine.Code) = on {
        val q = QuickJs.create(thread).also { js = it }
        q.memoryLimit = AppRuntime.MEMORY
        q.asyncFunction("__ask") { args -> host.ask(args.getOrNull(0) as? String ?: "") }
        q.asyncFunction("__fetch") { args -> host.fetch(args.getOrNull(0) as? String ?: "") }
        q.function("__words") { if (!code.words) throw BadApp(AppRuntime.undeclared(Use.Words)) }
        q.function("__cue") { args -> host.cue(args.getOrNull(0) as? String ?: "") }
        q.function("__log") { args -> host.log(args.getOrNull(0) as? String ?: "log", args.getOrNull(1)?.toString() ?: "") }
        // Each ends on undefined, whatever its last statement is: nothing to map back.
        q.evaluate<Any?>("${code.prelude}\n;undefined", "prelude.js")
        q.evaluate<Any?>("${code.script}\n;undefined", AppManifest.SCRIPT)
        q.evaluate<Any?>("__start(${AppRuntime.literal(code.saved)})", DEVICE)
        Unit
    }

    override suspend fun draw(): String = on { it("__draw()") as String }
    override suspend fun saved(): String = on { it("__saved()") as String }
    override suspend fun restore(state: String) = on { it("__start(${JSONObject.quote(state)})"); Unit }
    override suspend fun call(gen: Int, handler: Int, value: String): Boolean = on { it("await __call($gen, $handler, $value)") == true }
    override suspend fun hear(words: String): Boolean = on { it("await __hear(${JSONObject.quote(words)})") == true }
    override suspend fun tool(name: String, args: String): String =
        on { it("await __tool(${JSONObject.quote(name)}, ${JSONObject(args)})") as String }

    override fun close() {
        executor.execute { js?.let { q -> js = null; runCatching { q.close() } } }
        executor.shutdown()
    }

    /** [block] on the engine's thread, any error in the script a [JsError]. */
    private suspend fun <T> on(block: suspend (suspend (String) -> Any?) -> T): T = withContext(thread) {
        try {
            block { src -> (js ?: throw BadApp("it is not running")).evaluate<Any?>(src, DEVICE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuickJsException) {
            throw JsError(e.message?.lineSequence()?.firstOrNull()?.ifBlank { null } ?: "an error", e.fileName, e.lineNumber)
        } catch (e: ClassCastException) {
            throw JsError("the prelude answered what it cannot", null, null)
        } catch (e: Throwable) {
            throw JsError(e.message ?: e.javaClass.simpleName, null, null)
        }
    }

    private companion object {
        const val DEVICE = "device.js"
    }
}
