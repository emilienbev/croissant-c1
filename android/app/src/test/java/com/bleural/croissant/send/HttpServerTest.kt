package com.bleural.croissant.send

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The server's own limits, Send's and the phone page's alike: a request has a deadline
 * however slowly it trickles, the connections it holds are bounded and closed on stop, and
 * an Error in a handler is a 500, not the end of the app.
 */
class HttpServerTest {

    private val servers = mutableListOf<HttpServer>()

    @After fun stop() = servers.forEach { it.stop() }

    private fun serve(requestMs: Long = HttpServer.REQUEST_MS, handle: (Request) -> Response = { Response(200, "text/plain", "ok".toByteArray()) }) =
        HttpServer(0, InetAddress.getByName("127.0.0.1"), maxBody = 1024, requestMs = requestMs, handle = handle)
            .also { assertTrue(it.start()); servers += it }

    private fun connect(s: HttpServer) = Socket("127.0.0.1", s.boundPort)

    /** Whether the server has let [c] go: its next read ends rather than waits [ms]. */
    private fun closed(c: Socket, ms: Int): Boolean = try {
        c.soTimeout = ms
        c.getInputStream().read() == -1
    } catch (e: SocketTimeoutException) {
        false
    } catch (e: IOException) {
        true
    }

    private fun status(s: HttpServer): Int = connect(s).use { c ->
        c.soTimeout = 5000
        c.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        String(c.getInputStream().readBytes()).substringAfter(' ').substringBefore(' ').toInt()
    }

    @Test fun aRequestThatTricklesIsCutOffAtItsDeadline() {
        val s = serve(requestMs = 600)
        val c = connect(s)
        val t0 = System.nanoTime()
        // A byte every 100 ms: each read is quick, the whole never ends.
        val writer = Thread {
            runCatching { for (b in "GET / HTTP/1.1\r\nX-Slow: ${"a".repeat(200)}".toByteArray()) { c.getOutputStream().write(b.toInt()); Thread.sleep(100) } }
        }.apply { isDaemon = true; start() }
        assertTrue(closed(c, 4000))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$ms ms", ms in 400..3000)
        writer.interrupt()
    }

    @Test fun aConnectionThatSaysNothingIsLetGoAtItsDeadline() {
        val s = serve(requestMs = 500)
        assertTrue(closed(connect(s), 3000))
    }

    @Test fun pastFourAnsweredAndEightWaitingAConnectionIsClosedAtOnceAndStopClosesTheRest() {
        val s = serve(requestMs = 20_000)
        val held = List(12) { connect(s) }
        Thread.sleep(300)
        assertTrue("the thirteenth is closed", closed(connect(s), 1000))
        assertFalse("one being read is held", closed(held.first(), 100))
        assertFalse("one waiting is held", closed(held.last(), 100))
        s.stop()
        for ((i, c) in held.withIndex()) assertTrue("$i still open", closed(c, 1000))
    }

    @Test fun connectionsHeldPastTheirDeadlineAreLetGoAndTheServerAnswersAgain() {
        val s = serve(requestMs = 400)
        val held = List(12) { connect(s) }
        Thread.sleep(1500)
        for (c in held) assertTrue(closed(c, 500))
        assertEquals(200, status(s))
    }

    @Test fun anErrorInTheHandlerIsA500AndTheServerLives() {
        var n = 0
        var peer: InetAddress? = null
        val s = serve { r ->
            peer = r.peer
            if (n++ == 0) throw StackOverflowError() else Response(200, "text/plain")
        }
        assertEquals(500, status(s))
        assertEquals(200, status(s))
        assertEquals(InetAddress.getByName("127.0.0.1"), peer)
    }
}
