package com.bleural.croissant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.bleural.croissant.net.AcpBridgeClient
import com.bleural.croissant.net.AgentInfo
import com.bleural.croissant.net.AgentSession
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How the bridge answered last: not asked yet, being asked, up, away (no address answered), refused (pair again). */
enum class BridgeReach { Unasked, Reaching, Up, Away, Refused }

/** One agent's sessions as loaded so far: pages of its own list, [next] the cursor for more. */
data class AgentSessions(
    val list: List<AgentSession> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class AgentsUi(
    val reach: BridgeReach = BridgeReach.Unasked,
    /** The bridge's computer, by its own name, once it has answered. */
    val host: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val sessions: Map<String, AgentSessions> = emptyMap(),
)

/** A folder's sessions under its name, newest first: one header on an agent's sessions page. */
data class AgentFolder(val cwd: String, val name: String, val sessions: List<AgentSession>)

/**
 * The Agents page's state: the paired bridge's agents and, per agent, its sessions, asked for
 * while the page is up ([page]) and closed when it goes; and the session open on its page
 * ([chat]), watched on the bridge, where a hold's words are the next prompt ([say]). A
 * conversation is kept here by its key once opened, so coming back to it asks only for what
 * it missed; the socket gone while it is open (the R1 slept), it is opened again.
 */
class Agents(private val scope: CoroutineScope, private val bridge: () -> Prefs.AcpBridge, private val named: (String) -> Unit = {}) {
    companion object {
        private const val TAG = "Agents"
        /** The most sessions of an agent read: beyond, the oldest are left out. */
        private const val SESSIONS_MAX = 2000
        /** An away bridge asked again first after this, then twice as long each time, up to [AWAY_MOST_MS]. */
        private const val AWAY_FIRST_MS = 2_000L
        private const val AWAY_MOST_MS = 30_000L

        /**
         * [sessions] by folder, the folder with the newest activity first, each folder's newest
         * first; a folder is named by its last path part.
         */
        fun folders(sessions: List<AgentSession>): List<AgentFolder> =
            sessions.groupBy { it.cwd }
                .map { (cwd, ss) -> AgentFolder(cwd, cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }, ss.sortedByDescending { it.updatedAt ?: 0L }) }
                .sortedByDescending { f -> f.sessions.firstOrNull()?.updatedAt ?: 0L }

        /** How long ago [at] was, short: now, 5m, 3h, 2d, 6w, 2y. */
        fun age(at: Long?, now: Long = System.currentTimeMillis()): String? {
            at ?: return null
            val m = (now - at).coerceAtLeast(0) / 60_000
            return when {
                m < 1 -> "now"
                m < 60 -> "${m}m"
                m < 60 * 24 -> "${m / 60}h"
                m < 60 * 24 * 7 -> "${m / (60 * 24)}d"
                m < 60 * 24 * 365 -> "${m / (60 * 24 * 7)}w"
                else -> "${m / (60 * 24 * 365)}y"
            }
        }
    }

    val client = AcpBridgeClient(bridge).also { c ->
        c.onEvent = ::heard
        c.onDrop = { if (up && _chat.value != null) reopen() }
    }

    private val _state = MutableStateFlow(AgentsUi())
    val state: StateFlow<AgentsUi> = _state.asStateFlow()

    private val _chat = MutableStateFlow<AgentChat?>(null)
    /** The session open on its page, or null. */
    val chat: StateFlow<AgentChat?> = _chat.asStateFlow()

    /** Every conversation opened since the bridge was paired, by [AgentChat.key]. */
    private val chats = ConcurrentHashMap<String, AgentChat>()

    @Volatile private var up = false
    private var asking: Job? = null
    private var opening: Job? = null

    /** The page came up (the bridge is asked for its agents) or went (the socket closes). */
    fun page(on: Boolean) {
        if (on == up) return
        up = on
        if (on) { refresh(); if (_chat.value != null) reopen() }
        else { asking?.cancel(); opening?.cancel(); client.close() }
    }

    // ---- the open session -------------------------------------------------------------------

    /** Open [session] of [agent] in [cwd] on its page, or none (null). */
    fun openChat(agent: String?, session: String?, cwd: String?) {
        val key = if (agent != null && session != null) "$agent/$session" else null
        if (key == _chat.value?.key && (key == null || _chat.value?.opened == true)) return
        _chat.value?.let { if (!it.blank) chats[it.key] = it }
        opening?.cancel()
        if (key == null || cwd == null) { _chat.value = null; return }
        _chat.value = (chats[key] ?: AgentChat(agent!!, session!!, cwd)).copy(opened = false)
        reopen()
    }

    /** Ask the bridge for the open session from the last update heard, again until it answers while the page is up. */
    private fun reopen() {
        opening?.cancel()
        opening = scope.launch {
            var wait = 1_000L
            while (up) {
                val c = _chat.value ?: return@launch
                try {
                    val o = client.open(c.agent, c.session, c.cwd, c.seq, c.epoch)
                    _chat.update { it?.takeIf { x -> x.key == c.key }?.copy(opened = true, error = null, epoch = o.epoch) ?: it }
                    return@launch
                } catch (e: IOException) {
                    if (e is AcpBridgeClient.Refused) { _state.update { it.copy(reach = BridgeReach.Refused) }; return@launch }
                    _chat.update { it?.takeIf { x -> x.key == c.key }?.copy(error = e.message ?: "the bridge is away") ?: it }
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(15_000)
                }
            }
        }
    }

    /** An event from the bridge, on OkHttp's thread: an update of the open session goes into it. */
    private fun heard(e: JSONObject) {
        val key = "${e.optString("agent")}/${e.optString("sessionId")}"
        // The bridge restarted since this was heard: what it held is gone, its log follows anew.
        if (e.optString("event") == "reset") {
            _chat.update { c -> if (c?.key == key) AgentChat(c.agent, c.session, c.cwd, epoch = e.optString("epoch").ifEmpty { null }, sending = c.sending, unsent = c.unsent) else c }
            chats.remove(key)
            return
        }
        if (e.optString("event") != "update") return
        val update = e.optJSONObject("update") ?: return
        _chat.update { c -> if (c?.key == key) c.with(e.optInt("seq"), update) else c }
        if (update.optString("sessionUpdate") == "bridge_turn") markRunning(e.optString("agent"), e.optString("sessionId"), update.optBoolean("running"))
    }

    private fun markRunning(agent: String, session: String, running: Boolean) =
        put(agent) { s -> s.copy(list = s.list.map { if (it.id == session) it.copy(running = running) else it }) }

    /** A new chat of [agent] in [cwd] on its page, blank until a hold: [say] makes the session. */
    fun blank(agent: String, cwd: String) {
        if (_chat.value?.let { it.blank && it.agent == agent && it.cwd == cwd } == true) return
        _chat.value?.let { if (!it.blank) chats[it.key] = it }
        opening?.cancel()
        _chat.value = AgentChat(agent, "", cwd, opened = true)
    }

    /** Whether a hold here has somewhere to go: an open session, not in the middle of a turn. */
    val canTake: Boolean get() = _chat.value?.let { it.opened && !it.running && it.asking == null } == true

    /** A hold's words, from the service's thread: the open session's next prompt, a blank chat's first (its session made first). */
    fun say(words: String) {
        val c = _chat.value ?: return
        _chat.update { it?.copy(sending = words, unsent = null) }
        scope.launch {
            try {
                val session = if (c.blank) client.new(c.agent, c.cwd).also { id ->
                    _chat.update { x -> if (x?.blank == true && x.agent == c.agent && x.cwd == c.cwd) x.copy(session = id) else x }
                } else c.session
                client.prompt(c.agent, session, words)
                _chat.update { it?.copy(sending = null) }
            } catch (e: IOException) {
                _chat.update { it?.copy(sending = null, unsent = words, error = e.message) }
            }
        }
    }

    /** Send the words that did not get there again. */
    fun retry() { _chat.value?.unsent?.let(::say) }

    /** Stop the open session's turn. */
    fun stop() {
        val c = _chat.value ?: return
        scope.launch { runCatching { client.cancel(c.agent, c.session) } }
    }

    /** Answer the permission the open session waits on with [option] (an option's id), or cancel it (null). */
    fun permit(option: String?) {
        val c = _chat.value ?: return
        val a = c.asking ?: return
        _chat.update { it?.copy(asking = null) }
        scope.launch {
            try {
                client.permit(c.agent, c.session, a.request, option)
            } catch (e: IOException) {
                _chat.update { x -> x?.takeIf { it.key == c.key }?.copy(asking = a, error = e.message) ?: x }
            }
        }
    }

    /**
     * Ask the bridge again who it is and which agents it has. Away, it is asked again on its own
     * while the page is up, sooner then less often ([AWAY_FIRST_MS] doubling to [AWAY_MOST_MS]):
     * the app started before the Wi-Fi, or the bridge not yet running. Back, the sessions that
     * could not be read are read again.
     */
    fun refresh() {
        if (!bridge().set) return
        asking?.cancel()
        asking = scope.launch {
            _state.update { it.copy(reach = if (it.reach == BridgeReach.Up) BridgeReach.Up else BridgeReach.Reaching) }
            var wait = AWAY_FIRST_MS
            while (true) {
                try {
                    val info = client.hello()
                    named(info.host)
                    val agents = client.agents()
                    val was = _state.value
                    _state.update { it.copy(reach = BridgeReach.Up, host = info.host, agents = agents) }
                    if (was.reach == BridgeReach.Away) was.sessions.filterValues { it.error != null }.keys.forEach(::sessions)
                    return@launch
                } catch (e: AcpBridgeClient.Refused) {
                    _state.update { it.copy(reach = BridgeReach.Refused) }
                    return@launch
                } catch (e: IOException) {
                    Log.i(TAG, "bridge away: ${e.message}")
                    _state.update { it.copy(reach = BridgeReach.Away) }
                }
                if (!up) return@launch
                delay(wait)
                wait = (wait * 2).coerceAtMost(AWAY_MOST_MS)
            }
        }
    }

    /**
     * An agent's sessions, every page of them, read again: the list in hand stays until the new
     * one is whole, so a page showing it never falls back to the first page's. The first time,
     * each page shows as it comes.
     */
    fun sessions(agent: String) {
        if (_state.value.sessions[agent]?.loading == true) return
        put(agent) { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val first = _state.value.sessions[agent]?.loaded != true
                val all = LinkedHashMap<String, AgentSession>()
                var cursor: String? = null
                do {
                    val page = client.sessions(agent, cursor)
                    page.sessions.forEach { all.putIfAbsent(it.id, it) }
                    cursor = page.next
                    if (first && cursor != null) put(agent) { it.copy(list = all.values.toList(), loaded = true) }
                } while (cursor != null && all.size < SESSIONS_MAX)
                put(agent) { it.copy(list = all.values.toList(), loading = false, loaded = true) }
            } catch (e: IOException) {
                if (e is AcpBridgeClient.Refused) _state.update { it.copy(reach = BridgeReach.Refused) }
                put(agent) { it.copy(loading = false, error = e.message ?: "the bridge is away") }
            }
        }
    }

    private fun put(agent: String, f: (AgentSessions) -> AgentSessions) =
        _state.update { it.copy(sessions = it.sessions + (agent to f(it.sessions[agent] ?: AgentSessions()))) }

    // ---- a session's folder --------------------------------------------------------------

    suspend fun ls(cwd: String, path: String) = client.ls(cwd, path)

    suspend fun text(cwd: String, path: String): String? = client.text(cwd, path)

    private val images = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A picture in a session's folder, its longest side about [side] px; kept while there is room. Null when it cannot be read. */
    suspend fun image(cwd: String, path: String, side: Int, mtime: Long = 0): Bitmap? {
        val key = "$cwd/$path@$mtime@$side"
        images.get(key)?.let { return it }
        return try {
            val bytes = client.bytes(cwd, path)
            withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                if (longest <= 0) return@withContext null
                var sample = 1
                while (longest / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }?.also { images.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Paired again, or unpaired: forget what the last bridge said. */
    fun relinked() {
        asking?.cancel()
        opening?.cancel()
        client.close()
        chats.clear()
        images.evictAll()
        _chat.value = null
        _state.value = AgentsUi()
        if (up) refresh()
    }
}
