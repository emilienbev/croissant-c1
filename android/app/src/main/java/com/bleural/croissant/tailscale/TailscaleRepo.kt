package com.bleural.croissant.tailscale

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Runs one shell command as root through Magisk's su. The app holds a persistent grant. */
object Root {
    private const val TAG = "Root"

    /** stdout, or null if su refused or the command timed out. */
    fun run(cmd: String, timeoutS: Long = 10): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        val out = InputStreamReader(p.inputStream).readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); Log.w(TAG, "timeout: $cmd"); null }
        else if (p.exitValue() != 0) { Log.w(TAG, "exit ${p.exitValue()}: $cmd"); out.ifEmpty { null } }
        else out
    } catch (e: Exception) {
        Log.w(TAG, "failed: $cmd: $e"); null
    }
}

data class TailscaleUi(
    /** null until the first status has been read; false if su or the daemon is missing. */
    val available: Boolean? = null,
    /** tailscaled's BackendState: Running, Stopped, NeedsLogin, Starting, NoState. */
    val backend: String = "",
    val online: Boolean = false,
    val ip: String? = null,
    val hostname: String? = null,
    val relay: String? = null,
    /** Set while the daemon is waiting for a browser login. */
    val authUrl: String? = null,
    /** TCP reachability of the voice server, in ms; null = not reachable or not checked. */
    val serverMs: Long? = null,
    val busy: Boolean = false,
    /** The running daemon's version ("1.102.4"); null until read. */
    val version: String? = null,
    /** The latest stable tailscale, from pkgs.tailscale.com; null until looked up. */
    val latest: String? = null,
    /** Where the upgrade to [latest] is, or how it ended; null when there has been none. */
    val upgrade: TsUpgrade? = null,
    /** How much of the download is down, 0 to 100, while [upgrade] is Downloading. */
    val percent: Int = 0,
) {
    val running get() = backend == "Running"
    val needsLogin get() = backend == "NeedsLogin"
    /** A newer tailscale than the one running is out. */
    val newer get() = latest != null && version != null && newerThan(latest, version)
    val upgrading get() = upgrade == TsUpgrade.Downloading || upgrade == TsUpgrade.Installing || upgrade == TsUpgrade.Restarting
}

/**
 * An upgrade of the boot-time tailscaled, in its order: the download (checked against the
 * sha256 published beside it), the binaries swapped in, the daemon restarted. Then how it
 * ended: [Updated]; [NotDownloaded], nothing changed; [NotStarted], the new one would not
 * unpack or did not come back, and the old one is back.
 */
enum class TsUpgrade { Downloading, Installing, Restarting, Updated, NotDownloaded, NotStarted }

/** [a] is a later version than [b], both dotted numbers ("1.104.2"). */
internal fun newerThan(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** Another node on the tailnet, by the address an Android app can reach (MagicDNS names it cannot resolve). */
data class Peer(val name: String, val ip: String, val online: Boolean)

/**
 * Talks to the boot-time tailscaled through its CLI over root,
 * since its socket is root-only. `ts` is the wrapper that knows the socket path.
 */
class TailscaleRepo(private val serverHost: () -> String, private val serverPort: () -> Int, private val cache: File) {

    companion object {
        private const val TAG = "Tailscale"
        private const val DIR = "/data/adb/tailscale"
        private const val TS = "$DIR/ts"
        private const val POLL_MS = 3_000L
        private const val PKGS = "https://pkgs.tailscale.com/stable/"
        /** How long the latest version is taken as known: the page asks again after that. */
        private const val LATEST_MS = 3_600_000L
        /** How long a restarted daemon has to come back: the boot script restarts it within 15 s. */
        private const val BACK_MS = 60_000L
        /** A new R1's name on the tailnet; renamed from the admin console if wanted. */
        const val HOSTNAME = "croissant-c1"
    }

    private val _state = MutableStateFlow(TailscaleUi())
    val state: StateFlow<TailscaleUi> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null
    private var latestAt = 0L
    private val http = OkHttpClient()

    fun start() {
        if (!_state.value.upgrading) _state.update { it.copy(upgrade = null) }
        if (latestAt == 0L || SystemClock.elapsedRealtime() - latestAt > LATEST_MS) scope.launch { lookUpLatest() }
        poll = scope.launch {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stop() { poll?.cancel(); poll = null }

    fun refresh() {
        val raw = Root.run("$TS status --json", 8)
        // While an upgrade restarts the daemon it does not answer, and the page stays as it is.
        if (raw == null) { if (!_state.value.upgrading) _state.update { it.copy(available = false) }; return }
        try {
            val o = JSONObject(raw)
            val self = o.optJSONObject("Self")
            val ips = o.optJSONArray("TailscaleIPs")
            _state.update {
                it.copy(
                    available = true,
                    backend = o.optString("BackendState"),
                    online = self?.optBoolean("Online") == true,
                    ip = (0 until (ips?.length() ?: 0)).map { i -> ips!!.getString(i) }.firstOrNull { s -> '.' in s },
                    hostname = self?.optString("HostName")?.ifEmpty { null },
                    relay = self?.optString("Relay")?.ifEmpty { null },
                    authUrl = o.optString("AuthURL").ifEmpty { null },
                    serverMs = probeServer(),
                    version = o.optString("Version").substringBefore('-').ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad status: $e")
            _state.update { it.copy(available = false) }
        }
    }

    /**
     * tailscale up / down. `up` after a logout blocks on the browser login, so it runs detached.
     * A node that is not logged in comes up with the flags a new node needs: the
     * tailnet's DNS and routes left alone (either would take over the R1's own network) and
     * its name; a node that is only stopped keeps what it has, so a bare `up` is enough.
     * An `up` still waiting for its login is left to it, so asking again never piles them up;
     * one that went with a restarted daemon is gone, and asking again replaces it.
     */
    fun setUp(up: Boolean) {
        _state.update { it.copy(busy = true) }
        val first = _state.value.let { it.needsLogin || it.backend == "NoState" }
        val flags = if (first) " --accept-dns=false --accept-routes=false --hostname=$HOSTNAME" else ""
        scope.launch {
            // `[.]` so the pattern does not match this command's own shell.
            if (up) Root.run("pgrep -f 'tailscaled[.]sock up' >/dev/null || nohup $TS up$flags >/dev/null 2>&1 &", 5)
            else Root.run("$TS down", 15)
            delay(1500)
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    /** Log the node out; the next `up` yields a login URL for whichever account you choose. */
    fun logout() {
        _state.update { it.copy(busy = true) }
        scope.launch {
            Root.run("$TS logout", 20)
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    private fun lookUpLatest() = try {
        val v = JSONObject(get("$PKGS?mode=json").use { it.string() }).optString("TarballsVersion").ifEmpty { null }
        _state.update { it.copy(latest = v) }
        latestAt = SystemClock.elapsedRealtime()
    } catch (e: Exception) {
        Log.w(TAG, "latest: $e")
    }

    /**
     * Upgrade the daemon to [TailscaleUi.latest], each step in [TailscaleUi.upgrade]: download
     * and check it, unpack it beside the old binaries and swap them, keeping the old as
     * `.prev`, then restart the daemon (the boot script starts it again). Back with the new
     * version, and running if it was, the old ones go; not back within [BACK_MS], they are put
     * back and the daemon restarted again. The login is in the state file, which stays.
     */
    fun upgrade() {
        val s = _state.value
        val to = s.latest ?: return
        if (s.upgrading) return
        _state.update { it.copy(upgrade = TsUpgrade.Downloading, percent = 0, busy = true) }
        scope.launch {
            val end = try { install(to, from = s.version, wasRunning = s.running) } catch (e: Exception) { Log.w(TAG, "upgrade: $e"); TsUpgrade.NotStarted }
            _state.update { it.copy(upgrade = end, busy = false) }
            refresh()
        }
    }

    private suspend fun install(to: String, from: String?, wasRunning: Boolean): TsUpgrade {
        val tgz = File(cache, "tailscale.tgz")
        try {
            if (!download(to, tgz)) return TsUpgrade.NotDownloaded
            _state.update { it.copy(upgrade = TsUpgrade.Installing) }
            val swapped = Root.run(
                "rm -rf $DIR/new && mkdir $DIR/new && tar -xzf ${tgz.path} -C $DIR/new && cd $DIR/new/tailscale_${to}_arm64 && " +
                    "chown root:root tailscaled tailscale && chmod 700 tailscaled tailscale && " +
                    "chcon u:object_r:system_file:s0 tailscaled tailscale && ./tailscaled --version >/dev/null && " +
                    "mv -f $DIR/tailscaled $DIR/tailscaled.prev && mv -f $DIR/tailscale $DIR/tailscale.prev && " +
                    "mv tailscaled tailscale $DIR/ && cd / && rm -rf $DIR/new && echo swapped", 60,
            )?.contains("swapped") == true
            if (!swapped) { Root.run("rm -rf $DIR/new", 10); return TsUpgrade.NotStarted }
        } finally {
            tgz.delete()
        }
        _state.update { it.copy(upgrade = TsUpgrade.Restarting) }
        Root.run("kill \$(pidof tailscaled)", 5)
        if (cameBack(to, wasRunning)) { Root.run("rm -f $DIR/tailscaled.prev $DIR/tailscale.prev", 5); return TsUpgrade.Updated }
        Log.w(TAG, "upgrade: $to did not come back, putting the old one back")
        Root.run("mv -f $DIR/tailscaled.prev $DIR/tailscaled && mv -f $DIR/tailscale.prev $DIR/tailscale && kill \$(pidof tailscaled)", 10)
        if (from != null) cameBack(from, wasRunning)
        return TsUpgrade.NotStarted
    }

    /** The daemon answers with version [to] within [BACK_MS], and runs again if it [wasRunning]. */
    private suspend fun cameBack(to: String, wasRunning: Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + BACK_MS
        while (SystemClock.elapsedRealtime() < until) {
            delay(2000)
            val o = Root.run("$TS status --json", 8)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            if (o.optString("Version").substringBefore('-') == to && (!wasRunning || o.optString("BackendState") == "Running")) return true
        }
        return false
    }

    /** The tarball for [version] into [to], its percent in the state as it comes; true if its sha256 is the one published. */
    private fun download(version: String, to: File): Boolean = try {
        val name = "tailscale_${version}_arm64.tgz"
        val want = get("$PKGS$name.sha256").use { it.string() }.trim().substringBefore(' ')
        val sha = MessageDigest.getInstance("SHA-256")
        get(PKGS + name).use { body ->
            val total = body.contentLength()
            var done = 0L
            FileOutputStream(to).use { out ->
                val buf = ByteArray(1 shl 16)
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    done += n
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    if (p != _state.value.percent) _state.update { it.copy(percent = p) }
                }
            }
        }
        sha.digest().joinToString("") { "%02x".format(it) } == want
    } catch (e: IOException) {
        Log.w(TAG, "download: $e"); false
    }

    private fun get(url: String): ResponseBody {
        val r = http.newCall(Request.Builder().url(url).build()).execute()
        if (!r.isSuccessful) { r.close(); throw IOException("http ${r.code} for $url") }
        return r.body
    }

    /**
     * The other nodes, online first, each by its first IPv4 address and its name on the
     * tailnet (the first label of its MagicDNS name, what the admin console calls it, rather
     * than the OS hostname: "my-mac", not "My MacBook Pro"); empty when the status
     * cannot be read.
     */
    fun peers(): List<Peer> {
        val raw = Root.run("$TS status --json", 8) ?: return emptyList()
        return try {
            val peers = JSONObject(raw).optJSONObject("Peer") ?: return emptyList()
            peers.keys().asSequence().mapNotNull { k ->
                val p = peers.getJSONObject(k)
                val ips = p.optJSONArray("TailscaleIPs")
                val ip = (0 until (ips?.length() ?: 0)).map { ips!!.getString(it) }.firstOrNull { '.' in it } ?: return@mapNotNull null
                val name = p.optString("DNSName").substringBefore('.').ifEmpty { p.optString("HostName") }.ifEmpty { ip }
                Peer(name, ip, p.optBoolean("Online"))
            }.sortedWith(compareBy({ !it.online }, { it.name.lowercase() })).toList()
        } catch (e: Exception) {
            Log.w(TAG, "bad status: $e"); emptyList()
        }
    }

    private fun probeServer(): Long? = try {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(serverHost(), serverPort()), 2000) }
        (System.nanoTime() - t0) / 1_000_000
    } catch (e: Exception) { null }
}
