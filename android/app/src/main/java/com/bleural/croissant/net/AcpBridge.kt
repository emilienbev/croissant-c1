package com.bleural.croissant.net

import com.bleural.croissant.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The paired acp-bridge (acp-bridge/): a computer running coding agents, reached over one
 * WebSocket with its token. What it says is in acp-bridge/acp_bridge/server.py. The bridge
 * is the record of every session: nothing of them is kept on the device.
 *
 * One socket, opened at the first [ask] and kept until [close]; the bridge's addresses are
 * tried in turn, the last one that answered first ([reached]). Every call throws
 * [IOException] when no address answers or the bridge refuses, with the bridge's own words
 * when it gave some; [Refused] when the token is not the bridge's.
 */
class AcpBridgeClient(private val bridge: () -> Prefs.AcpBridge) {

    class Refused : IOException("the bridge refused this R1: pair again")

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val next = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var opened: CompletableDeferred<WebSocket>? = null

    /** The address the socket is open on, or was last. */
    @Volatile var reached: String? = null
        private set

    /** Hears every event the bridge tells ({"event": ...}), on OkHttp's thread. */
    @Volatile var onEvent: (JSONObject) -> Unit = {}

    /** Told when an open socket goes (the R1 slept, the bridge stopped); not at [close]. */
    @Volatile var onDrop: () -> Unit = {}

    /** Ask the bridge [op] with [params]; its result (a JSONObject or a JSONArray). */
    suspend fun ask(op: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Any {
        val ws = connect()
        val id = next.incrementAndGet()
        val answer = CompletableDeferred<JSONObject>()
        waiting[id] = answer
        val sent = ws.send(JSONObject(params.toString()).put("id", id).put("op", op).toString())
        if (!sent) { waiting.remove(id); throw IOException("the bridge went away") }
        val reply = try {
            withTimeout(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }
        reply.optString("error").takeIf { reply.has("error") }?.let { throw IOException(it) }
        return reply.opt("result") ?: JSONObject()
    }

    /** Close the socket; the next [ask] opens it again. */
    fun close() {
        val ws = socket
        socket = null
        ws?.close(1000, null)
        drop(IOException("closed"))
    }

    private suspend fun connect(): WebSocket {
        socket?.let { return it }
        opened?.let { return it.await() }
        val b = bridge()
        if (!b.set) throw IOException("no bridge paired")
        val trying = CompletableDeferred<WebSocket>()
        opened = trying
        try {
            var last: IOException = IOException("the bridge is away")
            for (host in listOfNotNull(reached?.takeIf { it in b.hosts }) + b.hosts.filter { it != reached }) {
                try {
                    val ws = open(host, b)
                    reached = host
                    socket = ws
                    trying.complete(ws)
                    return ws
                } catch (e: Refused) {
                    throw e
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last
        } catch (e: IOException) {
            trying.completeExceptionally(e)
            throw e
        } finally {
            opened = null
        }
    }

    private suspend fun open(host: String, b: Prefs.AcpBridge): WebSocket {
        val up = CompletableDeferred<WebSocket>()
        val request = Request.Builder().url("ws://$host:${b.port}/")
            .header("Authorization", "Bearer ${b.token}").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { up.complete(webSocket) }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (m.has("event")) onEvent(m)
                else waiting[m.optInt("id")]?.complete(m)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket, IOException("the bridge closed"))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val e = if (response?.code == 401) Refused() else t as? IOException ?: IOException(t.message)
                if (!up.isCompleted) up.completeExceptionally(e)
                gone(webSocket, e)
            }
        })
        return withTimeout(6_000) { up.await() }
    }

    private fun gone(ws: WebSocket, e: IOException) {
        if (socket === ws) { drop(e); onDrop() }
    }

    private fun drop(e: IOException) {
        socket = null
        waiting.values.forEach { it.completeExceptionally(e) }
        waiting.clear()
    }

    // ---- what is asked ----------------------------------------------------------------------

    suspend fun hello(): BridgeInfo = (ask("hello") as JSONObject).let { BridgeInfo(it.optString("host"), it.optInt("version")) }

    suspend fun agents(): List<AgentInfo> = (ask("agents") as JSONArray).let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            AgentInfo(o.getString("id"), o.getString("name"), o.optBoolean("ready"),
                o.optJSONArray("missing").strings(), o.optString("setup"))
        }
    }

    /** One page of an agent's sessions, as its own session/list gives them; [cursor] for the next. */
    suspend fun sessions(agent: String, cursor: String? = null): SessionPage {
        val o = ask("sessions", JSONObject().put("agent", agent).apply { if (cursor != null) put("cursor", cursor) }) as JSONObject
        val a = o.optJSONArray("sessions") ?: JSONArray()
        val list = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            AgentSession(s.getString("sessionId"), s.optString("cwd"), s.optString("title").ifBlank { null },
                s.optString("updatedAt").ifBlank { null }?.let(::instant), s.optBoolean("running"), s.optBoolean("asking"))
        }
        return SessionPage(list, o.optString("nextCursor").ifBlank { null })
    }

    /**
     * Watch a session: the bridge loads it when it is not running it, then tells its updates
     * after [after] (all of them from 0) as events; the log's last seq and the bridge's run
     * ([epoch]: one that is not its run's has it tell a reset first, and the whole log).
     */
    suspend fun open(agent: String, session: String, cwd: String, after: Int, epoch: String?): Opened =
        (ask("open", JSONObject().put("agent", agent).put("sessionId", session).put("cwd", cwd).put("after", after)
            .apply { if (epoch != null) put("epoch", epoch) }, timeoutMs = 120_000) as JSONObject)
            .let { Opened(it.optInt("seq"), it.optString("epoch").ifEmpty { null }) }

    /** A new session of [agent] in the folder [cwd], watched; its id. */
    suspend fun new(agent: String, cwd: String): String =
        (ask("new", JSONObject().put("agent", agent).put("cwd", cwd), timeoutMs = 60_000) as JSONObject).getString("sessionId")

    suspend fun prompt(agent: String, session: String, text: String) {
        ask("prompt", JSONObject().put("agent", agent).put("sessionId", session).put("text", text))
    }

    suspend fun cancel(agent: String, session: String) {
        ask("cancel", JSONObject().put("agent", agent).put("sessionId", session))
    }

    /** A folder [path] (relative, "" for the folder itself) in a session's folder [cwd]: folders first. */
    suspend fun ls(cwd: String, path: String): List<FolderEntry> {
        val a = (ask("ls", JSONObject().put("cwd", cwd).put("path", path)) as JSONObject).optJSONArray("entries") ?: JSONArray()
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let { FolderEntry(it.getString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime")) }
        }
    }

    /** A file's text in a session's folder, or null when it is not text (or too big to read here). */
    suspend fun text(cwd: String, path: String): String? {
        val o = ask("read", JSONObject().put("cwd", cwd).put("path", path)) as JSONObject
        return if (o.optBoolean("binary")) null else o.optString("text")
    }

    /** A file's bytes in a session's folder (a picture), up to the bridge's 12 MB. */
    suspend fun bytes(cwd: String, path: String): ByteArray {
        val o = ask("read", JSONObject().put("cwd", cwd).put("path", path).put("bytes", true), timeoutMs = 60_000) as JSONObject
        return android.util.Base64.decode(o.getString("base64"), android.util.Base64.DEFAULT)
    }

    /** Answer the permission [request] with one of its options' ids; null cancels it. */
    suspend fun permit(agent: String, session: String, request: String, option: String?) {
        ask("permit", JSONObject().put("agent", agent).put("sessionId", session).put("request", request)
            .put("option", option ?: JSONObject.NULL))
    }
}

data class BridgeInfo(val host: String, val version: Int)

/** An agent the bridge knows: [ready] when it can run on that computer, else what it [missing] and how to [setup]. */
data class AgentInfo(val id: String, val name: String, val ready: Boolean, val missing: List<String>, val setup: String)

/**
 * A session as its agent lists it: the folder it works in ([cwd]), its title, its last activity
 * in epoch ms; [running] while a turn of it runs on the bridge, [asking] while a permission of it
 * waits on an R1.
 */
data class AgentSession(val id: String, val cwd: String, val title: String?, val updatedAt: Long?, val running: Boolean, val asking: Boolean = false)

data class SessionPage(val sessions: List<AgentSession>, val next: String?)

/** A session opened on the bridge: its log's last seq, in the bridge's run [epoch]. */
data class Opened(val seq: Int, val epoch: String?)

/** One entry of a folder on the bridge's computer: a folder ([dir]) or a file of [size] bytes. */
data class FolderEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/**
 * The pairing QR's link (acp-bridge pair): `acp-bridge://<host>:<port>?token=...&also=<hosts>`,
 * the bridge's first address and then the others; null for any other code.
 */
fun parseAcpLink(text: String): Prefs.AcpBridge? {
    val uri = runCatching { java.net.URI(text.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "acp-bridge") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }
    }.toMap()
    val token = query["token"]?.takeIf { it.isNotBlank() } ?: return null
    val also = query["also"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != host }
    return Prefs.AcpBridge(listOf(host) + also, if (uri.port > 0) uri.port else Prefs.ACP_BRIDGE_PORT, token)
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun instant(s: String): Long? = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
    ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
