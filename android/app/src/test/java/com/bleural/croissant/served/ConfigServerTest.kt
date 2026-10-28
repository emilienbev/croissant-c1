package com.bleural.croissant.served

import com.bleural.croissant.ask.AskError
import com.bleural.croissant.served.StoreConfig.Companion.GEMINI
import com.bleural.croissant.served.StoreConfig.Companion.OPENAI
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * The page's server on a real socket, as a phone's browser meets it: the token spent once,
 * the cookie, the Host and Origin checks, the body cap, the Wi-Fi key rule, and no answer
 * carrying a key. Requests are written by hand, since HttpURLConnection will not send a
 * Host or an Origin of the test's choosing.
 */
class ConfigServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config by lazy { StoreConfig(tmp.root) }
    private var now = 1_000_000L
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var used = 0
    private val servers = mutableListOf<ConfigServer>()
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun ways(tailnet: String? = "127.0.0.1", wifi: String? = null) = object : Ways {
        override fun tailnet() = tailnet
        override fun wifi() = wifi
    }

    /** On loopback, which stands for the tailnet here unless [tailnetPeer] is the real rule; the page's own files. */
    private fun serve(ways: Ways = ways(), keysOverWifi: Boolean = ConfigServer.KEYS_OVER_PLAIN_WIFI,
                      tailnetPeer: (InetAddress) -> Boolean = { true }, prefer: Way? = null): ConfigServer =
        ConfigServer(config, ways, port = 0, keysOverWifi = keysOverWifi, clock = { now }, tailnetPeer = tailnetPeer,
            log = { logged += it }, onUsed = { used++ }, assets = ::asset).also { assertNull(it.start(prefer)); servers += it }

    /** assets/served/, as the app has it. */
    private fun asset(name: String): ByteArray = File("src/main/assets/served/$name").readBytes()

    @After fun stop() = servers.forEach { it.stop() }

    private class Got(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json() = JSONObject(body)
    }

    private fun ConfigServer.host() = url!!.removePrefix("http://").substringBefore('/')
    private fun ConfigServer.token() = url!!.substringAfterLast('/')

    private fun ConfigServer.send(
        method: String, path: String, cookie: String? = null, host: String = host(), origin: String? = "http://${host()}",
        type: String? = "application/json", body: String = "", extra: List<String> = emptyList(),
    ): Got = Socket("127.0.0.1", boundPort).use { s ->
        s.soTimeout = 5000
        val bytes = body.toByteArray()
        val head = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            cookie?.let { append("Cookie: $it\r\n") }
            if (method != "GET") origin?.let { append("Origin: $it\r\n") }
            if (bytes.isNotEmpty()) { type?.let { append("Content-Type: $it\r\n") }; append("Content-Length: ${bytes.size}\r\n") }
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        val raw = String(s.getInputStream().readBytes(), Charsets.UTF_8)
        seen += raw
        val (top, rest) = raw.indexOf("\r\n\r\n").let { raw.substring(0, it) to raw.substring(it + 4) }
        val lines = top.split("\r\n")
        Got(lines[0].split(' ')[1].toInt(),
            lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }, rest)
    }

    /** The phone scanning the code: the session's cookie, as the browser would send it back. */
    private fun ConfigServer.scan(): String {
        val r = send("GET", "/${token()}")
        assertEquals(303, r.status)
        assertEquals("/", r.headers["location"])
        val set = r.headers["set-cookie"]!!
        assertTrue(set, set.contains("HttpOnly") && set.contains("SameSite=Lax") && set.contains("Path=/"))
        return set.substringBefore(';')
    }

    @Test fun theUrlIsTheOneAddressThePortAndTheToken() {
        val s = serve()
        assertEquals(Way.Tailnet, s.way)
        assertTrue(s.url!!, s.url!!.matches(Regex("http://127\\.0\\.0\\.1:\\d+/[A-Za-z0-9_-]{14}")))
        assertEquals(Way.Wifi, serve(ways(tailnet = null, wifi = "127.0.0.1")).way)
        assertFalse(s.choice)
        // Both up: the tailnet unless Wi-Fi is asked for, and the R1 told it can offer the other.
        val both = serve(ways(tailnet = "127.0.0.1", wifi = "127.0.0.1"))
        assertEquals(Way.Tailnet, both.way)
        assertTrue(both.choice)
        assertEquals(Way.Wifi, serve(ways(tailnet = "127.0.0.1", wifi = "127.0.0.1"), prefer = Way.Wifi).way)
        assertEquals(Way.Tailnet, serve(ways(tailnet = "127.0.0.1", wifi = null), prefer = Way.Wifi).way)
        assertEquals("no tailnet and no wi-fi", ConfigServer(config, ways(null, null), port = 0).start())
        // An address the device no longer has is said to be gone, not a busy port.
        assertEquals("the tailnet address is gone", ConfigServer(config, ways("203.0.113.7"), port = 0).start())
        assertEquals("the wi-fi address is gone", ConfigServer(config, ways(null, "203.0.113.7"), port = 0).start())
    }

    @Test fun theTokenIsSpentOnceAndASecondPhoneGetsNothing() {
        val s = serve()
        assertFalse(s.used)
        val cookie = s.scan()
        assertTrue(s.used)
        val again = s.send("GET", "/${s.token()}")
        assertEquals(404, again.status)
        assertEquals("", again.body)
        assertEquals(404, s.send("GET", "/").status)
        assertEquals(200, s.send("GET", "/", cookie).status)
        assertTrue(s.send("GET", "/", cookie).body.contains("<script src=\"/app.js\""))
        assertFalse(s.send("GET", "/", cookie).body.contains(s.token()))
        assertEquals(1, used)
    }

    @Test fun aWrongTokenIsNothingAndSpendsNothing() {
        val s = serve()
        for (p in listOf("/nope", "/${s.token()}x", "/s/${s.token()}", "/"))
            assertEquals(p, 404, s.send("GET", p).status)
        assertEquals(401, s.send("GET", "/api/providers").status)
        s.scan()
    }

    @Test fun thePageIsItsThreeFilesWithNothingInline() {
        val s = serve()
        val cookie = s.scan()
        for ((path, type) in listOf("/" to "text/html", "/app.js" to "text/javascript", "/app.css" to "text/css")) {
            val r = s.send("GET", path, cookie)
            assertEquals(path, 200, r.status)
            assertTrue(r.headers["content-type"]!!, r.headers["content-type"]!!.startsWith(type))
            assertEquals("no-store", r.headers["cache-control"])
            assertEquals(String(asset(ConfigServer.PAGE[path]!!.first)), r.body)
            assertEquals(404, s.send("GET", path).status)
        }
        val csp = s.send("GET", "/", cookie).headers["content-security-policy"]!!
        assertFalse(csp, csp.contains("unsafe-inline"))
        assertTrue(csp, csp.startsWith("default-src 'self';"))
        // What the policy would refuse: a script or a style written into the page, a handler on an element.
        val html = String(asset("index.html"))
        assertFalse(Regex("<script(?![^>]*\\ssrc=)").containsMatchIn(html))
        assertFalse(Regex("<style|\\sstyle=|\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(String(asset("app.js")).contains("innerHTML"))
        assertEquals(404, s.send("GET", "/index.html", cookie).status)
    }

    @Test fun aCodeNobodyScansIsDeadAfterAFewMinutes() {
        val s = serve()
        now += ConfigServer.TOKEN_MS + 1
        assertEquals(404, s.send("GET", "/${s.token()}").status)
        assertFalse(s.used)
    }

    @Test fun anExpiredCodeIsReplacedWhileThePageIsOpenButASpentOneIsNot() {
        val s = serve()
        val first = s.token()
        assertNull(s.renew())
        now += ConfigServer.TOKEN_MS + 1
        val fresh = s.renew()!!
        assertEquals(fresh, s.url)
        assertNotEquals(first, s.token())
        assertEquals(404, s.send("GET", "/$first").status)
        assertNull("the fresh one lives", s.renew())
        val cookie = s.scan()
        now += ConfigServer.TOKEN_MS * 10
        assertNull("spent: none until the page is opened again", s.renew())
        assertEquals(fresh, s.url)
        assertEquals(200, s.send("GET", "/api/providers", cookie).status)
        val unscanned = serve()
        unscanned.stop()
        now += ConfigServer.TOKEN_MS * 10
        assertNull(unscanned.renew())
    }

    @Test fun theSessionHoldsOnlyFromTheAddressThatSpentTheCode() {
        val a = InetAddress.getByName("100.100.100.2")
        val b = InetAddress.getByName("100.100.100.3")
        val g = Gate("TOKEN", clock = { now })
        val cookie = "${ConfigServer.COOKIE}=" + g.spend("TOKEN", a)!!
        assertTrue(g.admits(cookie, a))
        assertFalse(g.admits(cookie, b))
        assertNull(g.spend("TOKEN", b))
    }

    @Test fun onTheTailnetOnlyATailnetAddressIsAnswered() {
        for (ip in listOf("100.64.0.1", "100.100.100.20", "100.127.255.255")) assertTrue(ip, ConfigServer.onTailnet(InetAddress.getByName(ip)))
        for (ip in listOf("100.63.255.255", "100.128.0.1", "127.0.0.1", "192.168.1.20", "fd7a:115c:a1e0::1"))
            assertFalse(ip, ConfigServer.onTailnet(InetAddress.getByName(ip)))
        val s = serve(tailnetPeer = { ConfigServer.onTailnet(it) })
        assertEquals(404, s.send("GET", "/${s.token()}").status)
        assertFalse(s.used)
        // Over Wi-Fi the house's addresses are answered.
        serve(ways(tailnet = null, wifi = "127.0.0.1"), tailnetPeer = { ConfigServer.onTailnet(it) }).scan()
    }

    @Test fun anotherSitesLinkGetsNoJson() {
        val s = serve()
        val cookie = s.scan()
        assertEquals(403, s.send("GET", "/api/export", cookie, extra = listOf("Sec-Fetch-Site: cross-site")).status)
        assertEquals(200, s.send("GET", "/api/export", cookie, extra = listOf("Sec-Fetch-Site: same-origin")).status)
        assertTrue(s.send("GET", "/", cookie).headers["content-security-policy"]!!.contains("form-action 'none'"))
    }

    @Test fun nothingAnswersWithoutTheCookie() {
        val s = serve()
        val cookie = s.scan()
        for ((m, p) in listOf("GET" to "/", "GET" to "/api/providers", "GET" to "/api/prompts", "GET" to "/api/export",
            "POST" to "/api/import", "PUT" to "/api/providers/gemini/key", "DELETE" to "/api/prompts/gemma")) {
            for (c in listOf(null, "croissant=wrong", "other=${cookie.substringAfter('=')}")) {
                val r = s.send(m, p, c, body = if (m == "GET" || m == "DELETE") "" else """{"key":"$GEMINI"}""")
                // The API says the session is gone, the page nothing.
                assertEquals("$m $p $c", if (p.startsWith("/api/")) 401 else 404, r.status)
                assertEquals("", r.body)
            }
        }
        assertFalse(config.hasKey("gemini"))
        assertEquals(200, s.send("GET", "/api/providers", "a=b; $cookie").status)
    }

    @Test fun anotherHostIsRefusedEvenWithTheTokenOrTheCookie() {
        val s = serve()
        assertEquals(404, s.send("GET", "/${s.token()}", host = "evil.example:${s.boundPort}").status)
        assertFalse(s.used)
        val cookie = s.scan()
        for (h in listOf("evil.example:${s.boundPort}", "127.0.0.1", "localhost:${s.boundPort}"))
            assertEquals(h, 404, s.send("GET", "/api/providers", cookie, host = h).status)
    }

    @Test fun aChangeComesFromThisPageWithAJsonBody() {
        val s = serve()
        val cookie = s.scan()
        val add = """{"name":"terse"}"""
        assertEquals(403, s.send("POST", "/api/prompts", cookie, origin = null, body = add).status)
        assertEquals(403, s.send("POST", "/api/prompts", cookie, origin = "http://evil.example", body = add).status)
        assertEquals(415, s.send("POST", "/api/prompts", cookie, type = "text/plain", body = add).status)
        assertEquals(415, s.send("POST", "/api/prompts", cookie, type = "application/x-www-form-urlencoded", body = add).status)
        assertNull(config.prompts["terse"])
        assertEquals(200, s.send("POST", "/api/prompts", cookie, type = "application/json; charset=utf-8", body = add).status)
        assertNotNull(config.prompts["terse"])
        assertEquals(200, s.send("DELETE", "/api/prompts/terse", cookie).status)
    }

    @Test fun aBodyPastTheCapIsRefusedUnread() {
        val s = serve()
        val cookie = s.scan()
        val r = s.send("POST", "/api/import", cookie, extra = listOf("Content-Type: application/json", "Content-Length: ${ConfigServer.BODY_MAX + 1}"))
        assertEquals(413, r.status)
        assertEquals(413, s.send("POST", "/api/import", cookie, extra = listOf("Content-Type: application/json", "Transfer-Encoding: chunked")).status)
        // The cap leaves room for the longest prompt, and a body of nothing but brackets is refused, not the app's end.
        val long = JSONObject().put("text", "x".repeat(ConfigFile.TEXT_MAX)).toString()
        assertEquals(200, s.send("PUT", "/api/prompts/gemma", cookie, body = long).status)
        val deep = "{\"text\":" + "[".repeat(ConfigServer.BODY_MAX - 16)
        assertEquals(400, s.send("PUT", "/api/prompts/gemma", cookie, body = deep).status)
        assertEquals(200, s.send("GET", "/api/prompts", cookie).status)
    }

    @Test fun noCorsHeaderIsEverSentAndAPreflightGetsNothing() {
        val s = serve()
        val cookie = s.scan()
        val pre = s.send("OPTIONS", "/api/providers", extra = listOf("Origin: http://evil.example", "Access-Control-Request-Method: PUT"))
        assertEquals(401, pre.status)
        s.send("GET", "/api/providers", cookie)
        s.send("POST", "/api/prompts", cookie, body = """{"name":"x"}""")
        for (raw in seen) assertFalse(raw, raw.lowercase().contains("access-control-"))
        assertTrue(seen.last().contains("X-Frame-Options: DENY"))
    }

    @Test fun overPlainWifiKeysComeAndGoOnlyWhenAllowed() {
        config.saveKey("openai", OPENAI)
        val s = serve(ways(tailnet = null, wifi = "127.0.0.1"), keysOverWifi = false)
        val cookie = s.scan()
        assertFalse(s.send("GET", "/api/providers", cookie).json().getBoolean("keys"))
        val r = s.send("PUT", "/api/providers/gemini/key", cookie, body = JSONObject().put("key", GEMINI).toString())
        assertEquals(403, r.status)
        assertEquals(ConfigApi.KEYS_WIFI, r.json().getString("error"))
        assertFalse(config.hasKey("gemini"))
        val file = s.send("GET", "/api/export", cookie)
        assertEquals(200, file.status)
        assertFalse(file.body.contains(OPENAI.takeLast(8)))
        // As decided, the same way takes them, and says it is Wi-Fi for the page's warning.
        val open = serve(ways(tailnet = null, wifi = "127.0.0.1"))
        val oc = open.scan()
        assertEquals("wifi", open.send("GET", "/api/providers", oc).json().getString("way"))
        assertTrue(open.send("GET", "/api/providers", oc).json().getBoolean("keys"))
        assertEquals(200, open.send("PUT", "/api/providers/gemini/key", oc, body = JSONObject().put("key", GEMINI).toString()).status)
        assertTrue(config.hasKey("gemini"))
        assertTrue(open.send("GET", "/api/export", oc).body.contains(GEMINI))
        assertEquals("tailnet", serve().let { t -> t.send("GET", "/api/providers", t.scan()).json().getString("way") })
    }

    @Test fun theExportIsAFileToSaveAndImportsBack() {
        config.saveKey("gemini", GEMINI)
        config.savePrompt("default", "Mine.")
        val s = serve()
        val cookie = s.scan()
        val file = s.send("GET", "/api/export", cookie)
        assertEquals("attachment; filename=\"croissant-settings.json\"", file.headers["content-disposition"])
        assertEquals(GEMINI, file.json().getJSONObject("keys").getString("gemini"))
        config.savePrompt("default", "Changed.")
        val r = s.send("POST", "/api/import", cookie, body = file.body)
        assertEquals(r.body, 200, r.status)
        assertEquals("Mine.", config.prompts["default"]?.text)
        assertEquals(400, s.send("POST", "/api/import", cookie, body = """{"providers":{}}""").status)
    }

    /**
     * Every endpoint walked, refusals and a provider quoting the key included: no answer but
     * the tailnet's export carries a key or its last eight, and neither does any log line.
     */
    @Test fun noAnswerEverCarriesAKey() {
        val s = serve()
        val cookie = s.scan()
        fun j(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString()
        s.send("PUT", "/api/providers/gemini/key", cookie, body = j("key" to GEMINI))
        s.send("PUT", "/api/providers/openai/key", cookie, body = j("key" to OPENAI))
        s.send("PUT", "/api/providers/gemini/key", cookie, body = j("key" to "$GEMINI​"))
        s.send("PUT", "/api/providers/nobody/key", cookie, body = j("key" to OPENAI))
        s.send("PUT", "/api/providers/gemini/key", cookie, body = """{"key": "$GEMINI" """)
        s.send("PUT", "/api/providers/gemini/key", cookie, type = "text/plain", body = j("key" to GEMINI))
        s.send("PUT", "/api/providers/gemini/key", cookie, origin = null, body = j("key" to GEMINI))
        s.send("PUT", "/api/providers/gemini/key", body = j("key" to GEMINI))
        config.lister = { Result.failure(AskError(401, "Incorrect API key provided: $OPENAI")) }
        s.send("POST", "/api/providers/openai/list", cookie)
        config.lister = { throw IOException("unreachable: $GEMINI") }
        s.send("POST", "/api/providers/gemini/list", cookie)
        config.lister = { Result.failure(AskError(401, "Incorrect API key provided: $OPENAI")) }
        s.send("POST", "/api/providers/groq/try", cookie, body = j("key" to OPENAI))
        s.send("POST", "/api/providers/openai/try", cookie, body = j("key" to OPENAI))
        config.lister = { Result.failure(IOException("refused")) }
        s.send("GET", "/api/providers", cookie)
        s.send("POST", "/api/providers", cookie, body = j("id" to "mybox", "kind" to "openai", "url" to "http://100.100.100.9:8000/v1"))
        s.send("PUT", "/api/providers/openai/address", cookie, body = j("url" to "https://api.openai.com/v1"))
        s.send("GET", "/api/prompts", cookie)
        s.send("GET", "/api/prompts/default", cookie)
        s.send("PUT", "/api/prompts/default", cookie, body = j("text" to "Be brief, really."))
        s.send("POST", "/api/prompts", cookie, body = j("name" to "terse"))
        s.send("POST", "/api/prompts/terse/rename", cookie, body = j("to" to "short"))
        s.send("POST", "/api/prompts/gemma/reset", cookie)
        s.send("DELETE", "/api/prompts/short", cookie)
        s.send("DELETE", "/api/providers/mybox", cookie)
        s.send("GET", "/", cookie)
        s.send("GET", "/nothing", cookie)
        val bad = JSONObject().put("format", ConfigFile.FORMAT).put("version", 1)
            .put("keys", JSONObject().put("gemini", GEMINI)).toString()
        s.send("POST", "/api/import", cookie, body = bad)
        val wifi = serve(ways(tailnet = null, wifi = "127.0.0.1"), keysOverWifi = false)
        val wc = wifi.scan()
        wifi.send("GET", "/api/export", wc)
        wifi.send("GET", "/api/providers", wc)
        val all = seen.toList()
        val export = s.send("GET", "/api/export", cookie).body

        assertTrue(config.hasKey("gemini") && config.hasKey("openai"))
        for (k in listOf(GEMINI, OPENAI)) {
            for (raw in all) assertFalse(raw, raw.contains(k.takeLast(8)))
            for (line in logged) assertFalse(line, line.contains(k.takeLast(8)))
            assertTrue("the tailnet's export carries the keys (decided)", export.contains(k))
        }
    }

    @Test fun itListensOnTheOneAddressOnly() {
        val s = serve()
        val other = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull()
        assumeTrue("no address but loopback here", other != null)
        val reached = runCatching { Socket().use { it.connect(InetSocketAddress(other, s.boundPort), 1000) } }.isSuccess
        assertFalse("answered on $other too", reached)
    }

    // Not a connection to the freed port after: on a shared machine something else may take it.
    @Test fun stoppingClosesThePortAndTheSession() {
        val s = serve()
        s.scan()
        s.stop()
        assertFalse(s.listening)
        assertEquals(-1, s.boundPort)
    }
}
