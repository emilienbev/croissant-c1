package com.bleural.croissant.atelier

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The launcher's side of one engine in the engine process: IAtelier's calls for it, by req. */
interface EngineWire {
    fun start(code: Engine.Code, req: Int)
    fun draw(req: Int)
    fun saved(req: Int)
    fun restore(state: String, req: Int)
    fun call(gen: Int, handler: Int, value: String, req: Int)
    fun hear(words: String, req: Int)
    fun tool(name: String, args: String, req: Int)
    fun interrupt(req: Int)
    fun answer(call: Int, value: String)
    fun refuse(call: Int, why: String)
    fun stop()
}

/** The engine's side back: IAtelierHost's calls. Anything may come here, in any order, at any size. */
interface EngineReplies {
    fun done(req: Int, value: String?)
    fun failed(req: Int, message: String?, file: String?, line: Int)
    fun ask(call: Int, text: String?)
    fun fetch(call: Int, url: String?)
    fun cue(name: String?)
    fun log(level: String?, line: String?)
}

/**
 * An app's engine in the engine process, seen from the launcher: each [Engine] call sent with
 * a req and awaited; cancelled (a timeout, the app closed), the engine is told to stop it.
 * The engine is never trusted: an answer to no call is dropped, one too long or of the wrong
 * kind fails the call, and its `ask` and `fetch` go through [host]'s checks, only while a call
 * is going, [MAX_CALLS] at most. [connect] reaches the process and gives the wire there.
 */
class IsolatedEngine(
    private val host: Engine.Host,
    private val connect: suspend (IsolatedEngine) -> EngineWire,
) : Engine, EngineReplies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var wire: EngineWire? = null
    @Volatile private var lost = false
    @Volatile private var closed = false
    private val reqs = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val calls = ConcurrentHashMap<Int, Job>()
    private val logged = AtomicInteger()

    override suspend fun start(code: Engine.Code) {
        val w = try {
            connect(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw EngineGone()
        }
        wire = w
        if (closed) w.stop()
        op { req -> w.start(code, req) }
    }

    override suspend fun draw(): String = op { wire().draw(it) }
    override suspend fun saved(): String = op { wire().saved(it) }
    override suspend fun restore(state: String) { op { wire().restore(state, it) } }
    override suspend fun call(gen: Int, handler: Int, value: String): Boolean = yesNo(op { wire().call(gen, handler, value, it) })
    override suspend fun hear(words: String): Boolean = yesNo(op { wire().hear(words, it) })
    override suspend fun tool(name: String, args: String): String = op { wire().tool(name, args, it) }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { wire?.stop() }
        pending.values.forEach { it.cancel() }
        scope.cancel()
    }

    /**
     * The process died: the app hears it once, then every call fails. In that order, so the
     * page names what the app was doing, before a failed call ends it.
     */
    fun gone() {
        if (lost || closed) return
        lost = true
        host.gone()
        pending.values.forEach { it.completeExceptionally(EngineGone()) }
        scope.cancel()
    }

    private fun wire(): EngineWire = wire ?: throw EngineGone()

    private suspend fun op(send: (Int) -> Unit): String {
        if (lost || closed) throw EngineGone()
        val req = reqs.incrementAndGet()
        val answer = CompletableDeferred<String>()
        pending[req] = answer
        logged.set(0)
        try {
            if (lost) throw EngineGone()
            send(req)
            return answer.await()
        } catch (e: CancellationException) {
            if (!lost && !closed) runCatching { wire?.interrupt(req) }
            calls.values.forEach { it.cancel() }
            throw e
        } finally {
            pending.remove(req)
        }
    }

    private fun yesNo(v: String): Boolean = when (v) {
        "true" -> true
        "false" -> false
        else -> throw BadApp(MALFORMED)
    }

    // ---- from the engine -----------------------------------------------------------

    override fun done(req: Int, value: String?) {
        val d = pending.remove(req) ?: return
        when {
            value == null -> d.completeExceptionally(BadApp(MALFORMED))
            value.length > MAX_REPLY -> d.completeExceptionally(BadApp("it answered with over ${MAX_REPLY / 1024} kB"))
            else -> d.complete(value)
        }
    }

    override fun failed(req: Int, message: String?, file: String?, line: Int) {
        val d = pending.remove(req) ?: return
        val m = message?.take(MAX_MESSAGE)?.lineSequence()?.firstOrNull()?.ifBlank { null } ?: "an error"
        d.completeExceptionally(JsError(m, file?.take(MAX_NAME), line.takeIf { it > 0 }))
    }

    override fun ask(call: Int, text: String?) = serve(call) { host.ask(text ?: "") }
    override fun fetch(call: Int, url: String?) = serve(call) { host.fetch(url ?: "") }

    override fun cue(name: String?) {
        if (pending.isNotEmpty() && name != null) host.cue(name.take(MAX_NAME))
    }

    override fun log(level: String?, line: String?) {
        if (pending.isEmpty() || line == null) return
        val n = logged.incrementAndGet()
        if (n <= MAX_LOGS) host.log(level?.take(MAX_NAME) ?: "log", line)
        else if (n == MAX_LOGS + 1) host.log("warn", "more than $MAX_LOGS lines in one call: the rest are dropped")
    }

    /** A host call, answered by [call] when it ends; refused out of turn, or past [MAX_CALLS]. */
    private fun serve(call: Int, block: suspend () -> String) {
        val w = wire ?: return
        if (lost || closed) return
        if (pending.isEmpty() || calls.size >= MAX_CALLS || calls.containsKey(call)) {
            runCatching { w.refuse(call, if (pending.isEmpty()) "it called the device out of turn" else "too many calls at once") }
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val r = try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                calls.remove(call)
            }
            runCatching { r.fold({ w.answer(call, it) }, { w.refuse(call, it.message ?: "it failed") }) }
        }
        calls[call] = job
        job.start()
    }

    companion object {
        /** A page or a state, the longest answers an engine gives. */
        const val MAX_REPLY = 64 * 1024
        const val MAX_MESSAGE = 1000
        const val MAX_NAME = 100
        const val MAX_CALLS = 64
        const val MAX_LOGS = 1000
        const val MALFORMED = "it answered what it cannot"
    }
}

/**
 * The engine process, bound while an app runs: one process (`:atelier`, isolated) for every
 * engine, each its own QuickJS on its own thread there. Bound when the first app starts,
 * unbound [IDLE_MS] after the last one stops, which ends the process. When it dies, every app
 * in it fails ([IsolatedEngine.gone]) and the next start binds a new one.
 */
class IsolatedEngines(context: Context, private val scope: CoroutineScope) : Engines {
    private val context = context.applicationContext
    private val lock = Any()
    private var link: Link? = null
    private var idle: Job? = null
    private val connections = Executors.newSingleThreadExecutor { Thread(it, "atelier-bind") }

    override fun engine(host: Engine.Host): Engine = IsolatedEngine(host, ::attach)

    private suspend fun attach(engine: IsolatedEngine): EngineWire {
        val l = synchronized(lock) {
            idle?.cancel()
            idle = null
            link ?: Link().also { link = it }
        }
        l.bind()
        val service = try {
            l.service.await()
        } catch (e: CancellationException) {
            synchronized(lock) { idleLater(l) }
            throw e
        }
        return synchronized(lock) {
            if (l.dead) throw EngineGone()
            BinderWire(l, service, l.ids++, engine).also { l.wires += it }
        }
    }

    private fun detach(l: Link, w: BinderWire) = synchronized(lock) {
        l.wires -= w
        idleLater(l)
    }

    /** With [lock] held: [l] unbound in a while if no engine is in it by then. */
    private fun idleLater(l: Link) {
        if (l.wires.isNotEmpty() || link !== l) return
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            synchronized(lock) {
                if (link === l && l.wires.isEmpty()) {
                    link = null
                    l.unbind()
                }
            }
        }
    }

    private inner class Link : ServiceConnection {
        val service = CompletableDeferred<IAtelier>()
        val wires = mutableSetOf<BinderWire>()
        var ids = 1
        var dead = false
        private var bound = false

        fun bind() {
            val ok = synchronized(lock) {
                if (bound || dead) return
                bound = true
                runCatching {
                    context.bindService(Intent(context, AtelierService::class.java), Context.BIND_AUTO_CREATE, connections, this)
                }.getOrDefault(false)
            }
            if (!ok) died()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return died()
            try {
                binder.linkToDeath({ died() }, 0)
            } catch (e: RemoteException) {
                return died()
            }
            service.complete(IAtelier.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()
        override fun onBindingDied(name: ComponentName?) = died()
        override fun onNullBinding(name: ComponentName?) = died()

        /** Unbound at once, so the system does not start it again by itself: the next app binds anew. */
        fun died() {
            val engines = synchronized(lock) {
                if (dead) return
                dead = true
                if (link === this) link = null
                wires.map { it.engine }.also { wires.clear() }
            }
            unbind()
            service.completeExceptionally(EngineGone())
            engines.forEach(IsolatedEngine::gone)
        }

        fun unbind() {
            runCatching { context.unbindService(this) }
        }
    }

    /** One engine's calls on the process's binder; a dead binder is [EngineGone]. */
    private inner class BinderWire(
        private val link: Link, private val service: IAtelier, private val id: Int, val engine: IsolatedEngine,
    ) : EngineWire {
        private val stub = HostStub(engine)

        override fun start(code: Engine.Code, req: Int) = send {
            val blob = Blob.pack(code.prelude, code.script, code.saved)
            try { service.start(id, stub, blob, code.words, req) } finally { blob.close() }
        }
        override fun draw(req: Int) = send { service.draw(id, req) }
        override fun saved(req: Int) = send { service.saved(id, req) }
        override fun restore(state: String, req: Int) = send { service.restore(id, state, req) }
        override fun call(gen: Int, handler: Int, value: String, req: Int) = send { service.call(id, gen, handler, value, req) }
        override fun hear(words: String, req: Int) = send { service.hear(id, words, req) }
        override fun tool(name: String, args: String, req: Int) = send { service.tool(id, name, args, req) }
        override fun interrupt(req: Int) = send { service.interrupt(id, req) }
        override fun answer(call: Int, value: String) = send {
            val blob = Blob.pack(value)
            try { service.answer(id, call, blob) } finally { blob.close() }
        }
        override fun refuse(call: Int, why: String) = send { service.refuse(id, call, why) }
        override fun stop() {
            runCatching { service.stop(id) }
            detach(link, this)
        }

        private inline fun send(block: () -> Unit) {
            try {
                block()
            } catch (e: RemoteException) {
                throw EngineGone()
            }
        }
    }

    /** The engine's calls, from the engine process's binder threads. */
    private class HostStub(private val to: EngineReplies) : IAtelierHost.Stub() {
        override fun done(req: Int, value: String?) = to.done(req, value)
        override fun failed(req: Int, message: String?, file: String?, line: Int) = to.failed(req, message, file, line)
        override fun ask(call: Int, text: String?) = to.ask(call, text)
        override fun fetch(call: Int, url: String?) = to.fetch(call, url)
        override fun cue(name: String?) = to.cue(name)
        override fun log(level: String?, line: String?) = to.log(level, line)
    }

    companion object {
        const val IDLE_MS = 60_000L
    }
}

/**
 * Texts in shared memory, for what is too large for a binder call (the code, a fetched page):
 * a count, then each text's UTF-8 length (-1 for null) and bytes. The engine maps it read-only.
 */
internal object Blob {
    fun pack(vararg texts: String?): SharedMemory {
        val parts = texts.map { it?.toByteArray(Charsets.UTF_8) }
        val shm = SharedMemory.create("atelier", 4 + parts.sumOf { 4 + (it?.size ?: 0) })
        val buf = shm.mapReadWrite()
        try {
            buf.putInt(parts.size)
            parts.forEach { p -> buf.putInt(p?.size ?: -1); if (p != null) buf.put(p) }
        } finally {
            SharedMemory.unmap(buf)
        }
        shm.setProtect(OsConstants.PROT_READ)
        return shm
    }

    /** Null when it is not a blob as [pack] writes it. Closes [shm]. */
    fun unpack(shm: SharedMemory?): List<String?>? = shm?.use {
        val buf = it.mapReadOnly()
        try { read(buf) } finally { SharedMemory.unmap(buf) }
    }

    private fun read(buf: ByteBuffer): List<String?>? = runCatching {
        val n = buf.int
        if (n !in 0..16) return null
        List(n) {
            when (val len = buf.int) {
                -1 -> null
                else -> ByteArray(len).also { b -> buf.get(b) }.toString(Charsets.UTF_8)
            }
        }
    }.getOrNull()
}
