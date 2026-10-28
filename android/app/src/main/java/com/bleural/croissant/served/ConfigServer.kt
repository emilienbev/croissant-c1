package com.bleural.croissant.served

import com.bleural.croissant.send.HttpServer
import com.bleural.croissant.send.Request
import com.bleural.croissant.send.Response
import com.bleural.croissant.send.Share
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** How a phone reaches the page: over the tailnet, or over plain Wi-Fi. */
enum class Way { Tailnet, Wifi }

/**
 * The page as the R1 offers it: [url] for the QR, the [way] it is served on, whether a phone
 * has [used] the code and when ([usedAt], ms since the epoch); [choice] whether the other way
 * was up too when it opened, so the R1 can offer it.
 */
data class PhonePage(val url: String, val way: Way, val used: Boolean, val usedAt: Long = 0, val choice: Boolean = false)

/** This C1's addresses, each null while that way is down (Core's: Tailscale's own, wlan0's). */
interface Ways {
    fun tailnet(): String?
    fun wifi(): String?
}

/**
 * The QR's one-time token and the session it becomes. The token is spent by the first
 * request that carries it, or dead [ttlMs] after it was minted unspent ([clock] in ms,
 * monotonic); the session is a cookie only the phone that spent it holds, from the address
 * it spent it from. One session per token: a second phone scanning the same code gets nothing.
 */
class Gate(val token: String = Share.mint(), private val clock: () -> Long = ConfigServer.MONOTONIC,
           private val ttlMs: Long = ConfigServer.TOKEN_MS) {

    private val minted = clock()
    @Volatile private var session: String? = null
    @Volatile private var holder: InetAddress? = null

    /** Whether a phone has spent the token. */
    val used: Boolean get() = session != null

    /** Dead unspent: what [ConfigServer.renew] replaces while the R1's page is still open. */
    val expired: Boolean @Synchronized get() = session == null && clock() - minted > ttlMs

    /** [given] spends the token from [peer]: the session's cookie value, or null for a wrong, spent or expired one. */
    @Synchronized
    fun spend(given: String, peer: InetAddress): String? {
        if (session != null || clock() - minted > ttlMs || !same(given, token)) return null
        holder = peer
        return ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            .also { session = it }
    }

    /** Whether [cookies] (a request's `Cookie` header) carries the session, from the address that spent the token. */
    @Synchronized
    fun admits(cookies: String?, peer: InetAddress): Boolean {
        val s = session ?: return false
        if (peer != holder) return false
        val v = cookies?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("${ConfigServer.COOKIE}=") }
            ?.substringAfter('=') ?: return false
        return same(v, s)
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private companion object {
        val random = SecureRandom()
    }
}

/**
 * The page a phone edits this C1's providers, keys and prompts on, served while the R1's
 * "on a phone" page is open ([start] to [stop]).
 *
 * On one address only, never every one: the tailnet's when Tailscale is up (the keys stay
 * inside WireGuard), else the Wi-Fi's; the Wi-Fi's when it is asked for ([start]), where what
 * is typed crosses the house's network unencrypted. The QR's URL carries a one-time token ([Gate]):
 * the first load spends it, sets the session's cookie (HttpOnly, SameSite=Lax: a code opened
 * from the camera is a navigation from another app, which Strict may leave without it) and
 * goes on to `/`, so the URL in a phone's history is dead. The cookie counts only from the
 * address that spent the token, and on the tailnet only from a tailnet address (100.64.0.0/10).
 * Nothing answers otherwise: a wrong token, a spent one, a wrong path, no cookie and another
 * address are the same empty 404, as Send's, but for the API without the session, an empty
 * 401, so the page can say it has closed.
 *
 * The page is three files, `assets/served/` ([assets] gives one by its name): index.html,
 * app.js and app.css, with no inline script or style, so the policy allows only this origin's.
 *
 * Every request must name this address as its `Host` (a name that resolves here by a trick
 * of DNS is refused); a change (POST, PUT, DELETE) must come from this page's own origin with
 * a JSON body; no CORS header is ever sent, so another site's page can neither ask nor read.
 * Over plain Wi-Fi keys come in and go out only with [KEYS_OVER_PLAIN_WIFI].
 */
class ConfigServer(
    private val config: Config,
    private val ways: Ways,
    private val port: Int = PORT,
    private val keysOverWifi: Boolean = KEYS_OVER_PLAIN_WIFI,
    clock: () -> Long = MONOTONIC,
    private val tailnetPeer: (InetAddress) -> Boolean = ::onTailnet,
    private val log: (String) -> Unit = {},
    private val onUsed: () -> Unit = {},
    private val assets: (String) -> ByteArray? = { null },
) {

    companion object {
        /** Send is 8766, the Mac's server 8765. */
        const val PORT = 8767

        /**
         * Whether a key may be typed, imported or exported over plain Wi-Fi, without Tailscale.
         * Allowed (decided 2026-10-16): Wi-Fi is offered beside the tailnet, and the R1 and the
         * page both warn that what is typed there crosses the network unencrypted.
         */
        const val KEYS_OVER_PLAIN_WIFI = true

        /** A code nobody scans is dead this long after it was shown, and replaced while the page is open ([renew]). */
        const val TOKEN_MS = 3 * 60_000L

        /** A request is a small JSON: a key, a prompt, a settings file. */
        const val BODY_MAX = 256 * 1024

        const val COOKIE = "croissant"

        /** Milliseconds that only go forward (Core gives the elapsed realtime, which counts deep sleep too). */
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000_000 }

        /** Whether [a] is on a tailnet: Tailscale's IPv4 addresses are 100.64.0.0/10. */
        fun onTailnet(a: InetAddress): Boolean =
            a is Inet4Address && a.address.let { (it[0].toInt() and 0xFF) == 100 && (it[1].toInt() and 0xC0) == 64 }

        private val HEADERS = listOf(
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY",
            "Content-Security-Policy" to "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )

        /**
         * The page's files by path: what [assets] is asked for, and its type. The page sets
         * every text it shows (a provider's name, a prompt, an error) with `textContent` only,
         * never `innerHTML`.
         */
        val PAGE = mapOf(
            "/" to ("index.html" to "text/html; charset=utf-8"),
            "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
            "/app.css" to ("app.css" to "text/css; charset=utf-8"),
        )
    }

    private val clock = clock
    @Volatile private var gate = Gate(clock = clock)
    @Volatile private var http: HttpServer? = null
    @Volatile private var origin = ""

    /** The way it is served on, once started. */
    var way: Way? = null
        private set

    /** Whether the other way was up too when it started, so the R1 can offer it. */
    var choice = false
        private set

    /** The page's files, read once at the start. */
    private var files: Map<String, ByteArray> = emptyMap()

    /** What the QR carries, once started; a new one after [renew]. */
    @Volatile var url: String? = null
        private set

    /** Whether a phone has used the code. */
    val used: Boolean get() = gate.used

    val listening: Boolean get() = http?.listening == true

    /** The port actually bound, which is [port] unless that was 0, as a test asks for. */
    val boundPort: Int get() = http?.boundPort ?: -1

    /**
     * A fresh code for one nobody scanned in time, while the page is open: the new URL, or
     * null when the code still lives, a phone has spent it (no other is minted until the
     * page is opened again), or it is not listening.
     */
    @Synchronized
    fun renew(): String? {
        if (http == null || !gate.expired) return null
        gate = Gate(clock = clock)
        return "$origin/${gate.token}".also { url = it }
    }

    /** Listening on one address, the tailnet's unless [prefer] is Wi-Fi and it is up: null when it is, else why not. */
    fun start(prefer: Way? = null): String? {
        val tailnet = ways.tailnet()
        val wifi = ways.wifi()
        val (w, address) = when {
            prefer == Way.Wifi && wifi != null -> Way.Wifi to wifi
            tailnet != null -> Way.Tailnet to tailnet
            wifi != null -> Way.Wifi to wifi
            else -> return "no tailnet and no wi-fi"
        }
        val bind = runCatching { InetAddress.getByName(address) }.getOrNull()
        val here = bind != null && runCatching { NetworkInterface.getByInetAddress(bind) }.getOrNull() != null
        if (!here) return "the ${if (w == Way.Tailnet) "tailnet" else "wi-fi"} address is gone"
        files = PAGE.values.mapNotNull { (name, _) -> runCatching { assets(name) }.getOrNull()?.let { name to it } }.toMap()
        val api = ConfigApi(config, keys = w == Way.Tailnet || keysOverWifi, way = w)
        val s = HttpServer(port, bind, BODY_MAX, log = log) { secure(answer(it, api, w)) }
        if (!s.start()) return "port $port is busy"
        val host = (if (':' in address) "[$address]" else address) + ":" + s.boundPort
        http = s
        way = w
        choice = tailnet != null && wifi != null
        origin = "http://$host"
        url = "$origin/${gate.token}"
        return null
    }

    fun stop() {
        http?.stop()
        http = null
    }

    private fun answer(r: Request, api: ConfigApi, w: Way): Response {
        val gate = gate
        val peer = r.peer ?: return nothing()
        if (w == Way.Tailnet && !tailnetPeer(peer)) return nothing()
        if (r.header("host") != origin.removePrefix("http://")) return nothing()
        val path = r.path.substringBefore('?').substringBefore('#')
        val one = path.trimEnd('/')
        if (r.method == "GET" && one.lastIndexOf('/') == 0 && one.length > 1) gate.spend(one.substring(1), peer)?.let { session ->
            return Response(303, "text/plain", headers = listOf("Location" to "/",
                "Set-Cookie" to "$COOKIE=$session; Path=/; HttpOnly; SameSite=Lax"), then = onUsed)
        }
        // The API says a gone session is gone, so the page can say so; anything else stays nothing.
        if (!gate.admits(r.header("cookie"), peer)) return if (path.startsWith("/api/")) Response(401, "text/plain") else nothing()
        if (r.method != "GET") {
            if (r.header("origin") != origin) return refused(403, "not from this page")
            if (r.body.isNotEmpty() && r.header("content-type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                return refused(415, "the body is JSON")
        }
        if (r.method == "GET") PAGE[path]?.let { (name, type) ->
            files[name]?.let { return Response(200, type, it, listOf("Cache-Control" to "no-store")) }
        }
        if (path.startsWith("/api/")) {
            // Lax lets another site's link carry the cookie on a top-level GET: the page's own requests are same-origin.
            if (r.header("sec-fetch-site").let { it != null && it != "same-origin" }) return refused(403, "not from this page")
            return api.handle(r.method, path.removePrefix("/api/"), r.body)
        }
        return nothing()
    }

    private fun refused(status: Int, why: String) =
        Response(status, ConfigApi.JSON, JSONObject().put("error", why).toString().toByteArray())

    private fun nothing() = Response(404, "text/plain")

    private fun secure(r: Response) = Response(r.status, r.type, r.body, r.headers + HEADERS, r.then)
}
