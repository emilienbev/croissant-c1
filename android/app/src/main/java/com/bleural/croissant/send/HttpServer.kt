package com.bleural.croissant.send

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One request as it came: [headers] by lower-case name, [body] empty unless it carried one, [peer] who sent it. */
class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray = ByteArray(0),
              val peer: InetAddress? = null) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** One answer: [headers] beyond the type and length; [then] runs once it has been written whole. */
class Response(
    val status: Int,
    val type: String,
    val body: ByteArray = ByteArray(0),
    val headers: List<Pair<String, String>> = emptyList(),
    val then: (() -> Unit)? = null,
)

/**
 * The device's own tiny HTTP server, for Send and for the page a phone edits the C1 on: one
 * request per connection, answered by [handle], then closed.
 *
 * Every response closes its connection. Safari opens several at once for a page of photos,
 * and a keep-alive it then sits on would hold a worker for nothing; four workers and no
 * keep-alive serves a handful of requests without a pool that can starve. Eight more wait
 * their turn; past that a connection is closed at once, and each one has [requestMs] from
 * its accept to say all it has to say, however slowly it trickles, so a client that holds
 * connections open cannot keep the device's file handles or its workers.
 *
 * [bind] null listens on every address (Send's two ways in); otherwise on that one only. A
 * body is read only up to [maxBody] (0: none, GET only), by its `Content-Length`; a longer
 * one is refused unread. [idleMs], when not 0, closes the server that long after it starts
 * with nobody asking, and [afterMs] past the last request. [log] never gets a path, a header
 * or a body: only what failed, so no token or key can reach a log through here.
 */
class HttpServer(
    private val port: Int,
    private val bind: InetAddress? = null,
    private val maxBody: Int = 0,
    private val idleMs: Long = 0,
    private val afterMs: Long = 0,
    private val requestMs: Long = REQUEST_MS,
    private val log: (String) -> Unit = {},
    private val handle: (Request) -> Response,
) {

    companion object {
        /** How often the accept loop wakes to check the clock. */
        private const val TICK_MS = 1_000

        /** A connection that has said nothing for this long is not going to. */
        private const val READ_MS = 10_000

        /** All a request may take, from its accept to its last byte read. */
        const val REQUEST_MS = 15_000L

        /** Requests answered at once, and waiting. */
        private const val WORKERS = 4
        private const val WAITING = 8

        /** A request line or a header longer than this is not one of ours. */
        private const val LINE_MAX = 8 * 1024

        private const val HEADERS_MAX = 64

        private val REASONS = mapOf(
            200 to "OK", 303 to "See Other", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 411 to "Length Required", 413 to "Content Too Large",
            415 to "Unsupported Media Type", 500 to "Internal Server Error", 502 to "Bad Gateway",
        )
    }

    private var socket: ServerSocket? = null
    private val workers = ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(WAITING))
    /** Every connection accepted and not yet closed, waiting or being served: closed on [stop]. */
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var live = false

    /** True once it is listening. False means the port was taken or the address is gone, which is nothing we can fix. */
    fun start(): Boolean = try {
        val s = if (bind == null) ServerSocket(port) else ServerSocket(port, 50, bind)
        s.soTimeout = TICK_MS
        socket = s
        if (idleMs > 0) deadline = System.currentTimeMillis() + idleMs
        live = true
        Thread({ accept(s) }, "http-$port").apply { isDaemon = true }.start()
        true
    } catch (e: Exception) {
        log("cannot listen on $port: ${e.javaClass.simpleName}")
        false
    }

    fun stop() {
        live = false
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
        for (c in open) runCatching { c.close() }
        open.clear()
    }

    /** Whether it is still listening: false once stopped, or once it has served its time and closed itself. */
    val listening: Boolean get() = live

    /** The port actually bound, which is [port] unless that was 0, as a test asks for. */
    val boundPort: Int get() = socket?.localPort ?: -1

    private fun accept(s: ServerSocket) {
        while (live) {
            val c = try {
                s.accept()
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) { log("done listening"); stop() }
                continue
            } catch (e: Exception) {
                if (live) log("accept: ${e.javaClass.simpleName}")
                return
            }
            val at = System.nanoTime()
            open += c
            runCatching { workers.execute { serve(c, at) } }.onFailure { open -= c; runCatching { c.close() } }
        }
    }

    /** Errors too ([StackOverflowError] from a parser): one that left a worker would take the app, which is HOME, with it. */
    private fun serve(c: Socket, at: Long) = try {
        exchange(c, at)
    } catch (e: Throwable) {
        log("serve: ${e.javaClass.simpleName}")
    } finally {
        open -= c
        runCatching { c.close() }
    }

    /** One request read and answered; [serve] closes the connection. */
    private fun exchange(c: Socket, at: Long) {
        val input = BufferedInputStream(Timed(c, c.getInputStream(), at + requestMs * 1_000_000))
        val out = c.getOutputStream()
        val asked = try {
            read(input, c.inetAddress)
        } catch (e: TooLarge) {
            runCatching { write(out, Response(413, "text/plain")) }
            return
        } catch (e: Exception) {
            log("read: ${e.javaClass.simpleName}")
            return
        } ?: return
        if (afterMs > 0) deadline = maxOf(deadline, System.currentTimeMillis() + afterMs)
        val answer = try {
            handle(asked)
        } catch (e: Throwable) {
            log("handle: ${e.javaClass.simpleName}")
            Response(500, "text/plain")
        }
        runCatching { write(out, answer) }.onFailure { e -> log("write: ${e.javaClass.simpleName}"); return }
        answer.then?.invoke()
    }

    private class TooLarge : IOException()

    /**
     * [inner] with every wait bounded by what is left before [deadline] (System.nanoTime), so
     * the request line, the headers and the body together take no longer than [requestMs].
     */
    private class Timed(private val socket: Socket, private val inner: InputStream, private val deadline: Long) : InputStream() {
        private fun bound() {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("too slow")
            socket.soTimeout = minOf(left, READ_MS.toLong()).toInt().coerceAtLeast(1)
        }

        override fun read(): Int { bound(); return inner.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int { bound(); return inner.read(b, off, len) }
    }

    /** The request line, the headers, and a body of `Content-Length` bytes up to [maxBody]; null for a connection that said nothing. */
    private fun read(input: InputStream, peer: InetAddress?): Request? {
        val first = line(input) ?: return null
        val parts = first.split(' ')
        val headers = HashMap<String, String>()
        repeat(HEADERS_MAX + 1) { n ->
            val h = line(input) ?: throw IOException("cut short")
            if (h.isEmpty()) {
                val length = headers["content-length"]?.let { v -> v.trim().toIntOrNull() ?: throw IOException("bad length") } ?: 0
                if (length < 0) throw IOException("bad length")
                // A chunked body is never one of ours: refused rather than read.
                if (length > maxBody || headers.containsKey("transfer-encoding")) throw TooLarge()
                val body = ByteArray(length)
                var at = 0
                while (at < length) {
                    val got = input.read(body, at, length - at)
                    if (got < 0) throw IOException("cut short")
                    at += got
                }
                if (parts.size < 2) return Request("", "", headers, body, peer)
                return Request(parts[0], parts[1], headers, body, peer)
            }
            if (n == HEADERS_MAX) throw IOException("too many headers")
            val colon = h.indexOf(':')
            if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
        }
        throw IOException("too many headers")
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (b.size() == 0) null else throw IOException("cut short")
            if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            if (b.size() >= LINE_MAX) throw TooLarge()
            b.write(c)
        }
    }

    private fun write(out: OutputStream, r: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(r.status).append(' ').append(REASONS[r.status] ?: "Status").append("\r\n")
            append("Content-Type: ").append(r.type).append("\r\n")
            for ((k, v) in r.headers) append(k).append(": ").append(v).append("\r\n")
            append("Content-Length: ").append(r.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    /** For a test that wants the workers drained before it asserts. */
    fun awaitIdle(ms: Long) { workers.awaitTermination(ms, TimeUnit.MILLISECONDS) }
}
