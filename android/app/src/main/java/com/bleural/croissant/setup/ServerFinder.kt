package com.bleural.croissant.setup

import com.bleural.croissant.Prefs
import com.bleural.croissant.tailscale.Peer
import com.bleural.croissant.tailscale.TailscaleRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** A peer and what it said on the server's port: [ms] its round trip when it is a croissant-server, null when not; [asked] once it has answered or given up. */
data class Candidate(val peer: Peer, val ms: Long? = null, val asked: Boolean = false)

/** [peers] null until the tailnet has been read. */
data class ServerSearch(val peers: List<Candidate>? = null) {
    val answering get() = peers.orEmpty().filter { it.ms != null }
    val done get() = peers != null && peers.all { it.asked }
}

/**
 * Finds the Mac running `server/`: every peer on the tailnet asked at once, by address, on
 * the default port. A peer counts only when `GET /` says `croissant-server ok` (server/app.py),
 * so another service that happens to listen on 8765 is not taken for it.
 */
class ServerFinder(private val tailscale: TailscaleRepo) {
    private val _state = MutableStateFlow(ServerSearch())
    val state: StateFlow<ServerSearch> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun search() {
        job?.cancel()
        _state.value = ServerSearch()
        job = scope.launch {
            // An offline node cannot be the server, so it is not listed at all.
            val peers = tailscale.peers().filter { it.online }
            _state.value = ServerSearch(peers.map { Candidate(it) })
            peers.forEach { p ->
                launch {
                    val ms = check(p.ip, Prefs.DEFAULT_PORT)
                    _state.update { s -> s.copy(peers = s.peers?.map { if (it.peer == p) it.copy(ms = ms, asked = true) else it }) }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    companion object {
        /** The round trip of `GET /` when [host]:[port] is a croissant-server, else null. Blocking; call off the main thread. */
        fun check(host: String, port: Int): Long? = try {
            val t0 = System.nanoTime()
            val c = URL("http://$host:$port/").openConnection() as HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            try {
                val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText().startsWith("croissant-server")
                if (ok) (System.nanoTime() - t0) / 1_000_000 else null
            } finally { c.disconnect() }
        } catch (e: Exception) { null }
    }
}
