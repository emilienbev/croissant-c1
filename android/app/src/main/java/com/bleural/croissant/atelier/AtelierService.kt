package com.bleural.croissant.atelier

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where Atelier's apps run (2026-10-17): every app's QuickJS in this one service, in a process
 * of its own, `:atelier`, isolated (AndroidManifest.xml). The launcher is HOME, the Device
 * Owner, holds the keys and the tailnet and has a root policy; a flaw in QuickJS here reaches
 * none of it. An isolated process has a throwaway uid: no root policy, no app files, no
 * network, no permissions. The script reaches the launcher only through the prelude's host
 * calls, over IAtelier and IAtelierHost, and the launcher checks each (IsolatedEngine.kt).
 * Nothing here but the engines: no Core, no Compose, no logging of its own.
 */
class AtelierService : Service() {
    private val engines = ConcurrentHashMap<Int, EngineServer>()

    private val binder = object : IAtelier.Stub() {
        override fun start(engine: Int, host: IAtelierHost?, code: SharedMemory?, words: Boolean, req: Int) {
            val texts = Blob.unpack(code)
            val replies = BinderReplies(host ?: return)
            val server = EngineServer(replies)
            engines.put(engine, server)?.stop()
            val prelude = texts?.getOrNull(0)
            val script = texts?.getOrNull(1)
            if (texts?.size != 3 || prelude == null || script == null) return replies.failed(req, "its code did not arrive", null, -1)
            server.start(Engine.Code(prelude, script, texts[2], words), req)
        }
        override fun draw(engine: Int, req: Int) { engines[engine]?.draw(req) }
        override fun saved(engine: Int, req: Int) { engines[engine]?.saved(req) }
        override fun restore(engine: Int, state: String?, req: Int) { engines[engine]?.restore(state ?: "null", req) }
        override fun call(engine: Int, gen: Int, handler: Int, value: String?, req: Int) {
            engines[engine]?.call(gen, handler, value ?: "undefined", req)
        }
        override fun hear(engine: Int, words: String?, req: Int) { engines[engine]?.hear(words ?: "", req) }
        override fun tool(engine: Int, name: String?, args: String?, req: Int) { engines[engine]?.tool(name ?: "", args ?: "{}", req) }
        override fun interrupt(engine: Int, req: Int) { engines[engine]?.interrupt(req) }
        override fun answer(engine: Int, call: Int, value: SharedMemory?) {
            val text = Blob.unpack(value)?.singleOrNull()
            val e = engines[engine] ?: return
            if (text != null) e.answer(call, text) else e.refuse(call, "its answer did not arrive")
        }
        override fun refuse(engine: Int, call: Int, why: String?) { engines[engine]?.refuse(call, why ?: "refused") }
        override fun stop(engine: Int) { engines.remove(engine)?.stop() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engines.values.forEach(EngineServer::stop)
        engines.clear()
    }

    /** The launcher's end; it gone, a call to it is dropped (this process is unbound then too). */
    private class BinderReplies(private val host: IAtelierHost) : EngineReplies {
        override fun done(req: Int, value: String?) = quiet { host.done(req, value) }
        override fun failed(req: Int, message: String?, file: String?, line: Int) = quiet { host.failed(req, message, file, line) }
        override fun ask(call: Int, text: String?) = quiet { host.ask(call, text) }
        override fun fetch(call: Int, url: String?) = quiet { host.fetch(call, url) }
        override fun cue(name: String?) = quiet { host.cue(name) }
        override fun log(level: String?, line: String?) = quiet { host.log(level, line) }

        private inline fun quiet(block: () -> Unit) {
            try { block() } catch (e: Exception) { }
        }
    }
}

/**
 * One app's engine, the engine process's side: the launcher's calls run one at a time, in the
 * order they came, each answered with its req; [interrupt] cancels one, which stops the script.
 * The script's `ask` and `fetch` go to the launcher by a call number and wait for its answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineServer(
    private val replies: EngineReplies,
    engines: Engines = Engines(::QuickJsEngine),
) : EngineWire {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val turn = Mutex()
    private val ops = ConcurrentHashMap<Int, Job>()
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val calls = AtomicInteger()

    private val engine = engines.engine(object : Engine.Host {
        override suspend fun ask(text: String): String = hostCall { replies.ask(it, text) }
        override suspend fun fetch(url: String): String = hostCall { replies.fetch(it, url) }
        override fun cue(name: String) = replies.cue(name)
        override fun log(level: String, line: String) = replies.log(level, line)
        override fun gone() {}
    })

    override fun start(code: Engine.Code, req: Int) = op(req) { engine.start(code); "" }
    override fun draw(req: Int) = op(req) { engine.draw() }
    override fun saved(req: Int) = op(req) { engine.saved() }
    override fun restore(state: String, req: Int) = op(req) { engine.restore(state); "" }
    override fun call(gen: Int, handler: Int, value: String, req: Int) = op(req) { engine.call(gen, handler, value).toString() }
    override fun hear(words: String, req: Int) = op(req) { engine.hear(words).toString() }
    override fun tool(name: String, args: String, req: Int) = op(req) { engine.tool(name, args) }
    override fun interrupt(req: Int) { ops.remove(req)?.cancel() }
    override fun answer(call: Int, value: String) { waiting.remove(call)?.complete(value) }
    override fun refuse(call: Int, why: String) { waiting.remove(call)?.completeExceptionally(BadApp(why)) }

    override fun stop() {
        ops.values.forEach(Job::cancel)
        waiting.values.forEach { it.cancel() }
        scope.launch { turn.withLock { engine.close() } }
    }

    private fun op(req: Int, block: suspend () -> String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val r = turn.withLock { block() }
                if (r.length > IsolatedEngine.MAX_REPLY) replies.failed(req, "it answered with over ${IsolatedEngine.MAX_REPLY / 1024} kB", null, -1)
                else replies.done(req, r)
            } catch (e: CancellationException) {
                // Interrupted: the launcher has gone on without it.
            } catch (e: JsError) {
                replies.failed(req, e.message, e.file, e.line ?: -1)
            } catch (e: Throwable) {
                replies.failed(req, e.message ?: e.javaClass.simpleName, null, -1)
            } finally {
                ops.remove(req)
            }
        }
        ops[req] = job
        job.start()
    }

    private suspend fun hostCall(send: (Int) -> Unit): String {
        val call = calls.incrementAndGet()
        val answer = CompletableDeferred<String>()
        waiting[call] = answer
        try {
            send(call)
            return answer.await()
        } finally {
            waiting.remove(call)
        }
    }
}
