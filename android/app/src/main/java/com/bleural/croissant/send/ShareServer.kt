package com.bleural.croissant.send

import android.util.Log
import java.net.URLEncoder

/**
 * The device serving its own photos or a tape's transcript, for as long as someone is looking at the code.
 *
 * Bound to the wildcard address, so one server answers on the Wi-Fi address and the
 * tailnet address both and the screen's switch costs nothing here. Plain HTTP: on the
 * LAN it is the house, and over the tailnet WireGuard has already encrypted it. GET only:
 * the server ([HttpServer]) reads no body.
 */
class ShareServer(
    private val share: Share,
    port: Int = Share.PORT,
    private val onServed: (Ask) -> Unit,
) {

    companion object {
        private const val TAG = "ShareServer"

        /** A screen left open listens this long with nobody scanning. */
        const val IDLE_MS = 3 * 60_000L

        /** Kept alive this long past the last request, for a photo the phone asks for twice. */
        const val AFTER_MS = 60_000L
    }

    private val http = HttpServer(port, idleMs = IDLE_MS, afterMs = AFTER_MS, log = { Log.w(TAG, it) }, handle = ::answer)

    /** True once it is listening. False means the port was taken, which is nothing we can fix. */
    fun start(): Boolean = http.start()

    fun stop() = http.stop()

    /** Whether it is still listening: false once it has served its time and closed itself. */
    val listening: Boolean get() = http.listening

    /** The port actually bound, which is the one asked for unless that was 0, as a test asks for. */
    val boundPort: Int get() = http.boundPort

    /** What the request asked for, answered; it is reported once the answer has gone. */
    private fun answer(r: Request): Response {
        val ask = if (r.method == "GET") share.route(r.path) else Ask.Unknown
        val then = { onServed(ask) }
        return when (ask) {
            is Ask.Page -> Response(200, "text/html; charset=utf-8", share.page().toByteArray(), then = then)
            is Ask.Item -> Response(200, "image/jpeg", share.files[ask.index].readBytes(), then = then)
            is Ask.Text -> Response(200, "text/plain; charset=utf-8", share.text().toByteArray(),
                listOf(attachment(share.textName())), then = then)
            is Ask.Unknown -> Response(404, "text/plain", then = then)
        }
    }

    /**
     * Saved rather than shown, under [name]: the plain `filename` in ASCII for anything
     * old, `filename*` in UTF-8 for a tape named with accents, which Safari prefers.
     */
    private fun attachment(name: String): Pair<String, String> {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '%') it else '_' }.joinToString("")
        val utf8 = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "Content-Disposition" to "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf8"
    }

    /** For a test that wants the workers drained before it asserts. */
    fun awaitIdle(ms: Long) = http.awaitIdle(ms)
}
