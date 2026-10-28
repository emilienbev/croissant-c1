package com.bleural.croissant.send

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The server answering a real socket. Worth a real connection rather
 * than a call into [ShareServer.serve]: what is being checked is the thing Safari meets,
 * headers and all, and the handler is only half of that.
 */
class ShareServerTest {

    private lateinit var one: File
    private lateinit var two: File
    private lateinit var share: Share
    private lateinit var server: ShareServer
    private val served = mutableListOf<Ask>()
    private val reported = Semaphore(0)

    @Before fun start() {
        one = File.createTempFile("send", ".jpg").apply { writeBytes(ByteArray(2048) { 1 }); deleteOnExit() }
        two = File.createTempFile("send", ".jpg").apply { writeBytes(ByteArray(4096) { 2 }); deleteOnExit() }
        share = Share(listOf(one, two))
        server = ShareServer(share, port = 0) { synchronized(served) { served += it }; reported.release() }
        assertTrue("could not listen", server.start())
    }

    @After fun stop() = server.stop()

    private fun get(path: String): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
        c.disconnect()
        return code to body
    }

    @Test fun `the page comes back as html`() {
        val (code, body) = get("/s/${share.token}")
        assertEquals(200, code)
        assertTrue(String(body).contains("<img src=\"/s/${share.token}/0.jpg\">"))
    }

    @Test fun `a photo comes back whole`() {
        val (code, body) = get("/s/${share.token}/1.jpg")
        assertEquals(200, code)
        assertEquals(4096, body.size)
        assertTrue(body.all { it == 2.toByte() })
    }

    @Test fun `a wrong token gets a 404 and no bytes`() {
        assertEquals(404, get("/s/nope/0.jpg").first)
        assertEquals(404, get("/nothing").first)
        assertEquals(0, get("/s/nope").second.size)
    }

    /**
     * What the screen counts to say a photo has gone. The server reports a request after
     * answering it, on one of its workers, so the reports arrive in any order: wait for all
     * four, then compare them as a set.
     */
    @Test fun `every request is reported`() {
        get("/s/${share.token}")
        get("/s/${share.token}/0.jpg")
        get("/s/${share.token}/1.jpg")
        get("/s/wrong")
        assertTrue("not every request was reported", reported.tryAcquire(4, 5, TimeUnit.SECONDS))
        synchronized(served) {
            assertEquals(4, served.size)
            assertEquals(setOf(Ask.Page, Ask.Item(0), Ask.Item(1), Ask.Unknown), served.toSet())
        }
    }

    /** Safari opens several at once for a page of photos; none of them may be dropped. */
    @Test fun `several connections at once are all answered`() {
        val got = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val threads = (0 until 8).map { i ->
            Thread { got += get("/s/${share.token}/${i % 2}.jpg").second.size }.apply { start() }
        }
        threads.forEach { it.join(5000) }
        assertEquals(8, got.size)
        assertTrue(got.toString(), got.all { it == 2048 || it == 4096 })
    }

    @Test fun `stopping closes the port`() {
        server.stop()
        assertTrue(!server.listening)
        runCatching { get("/s/${share.token}") }.onSuccess { fail("still answering") }
    }

    private fun fail(m: String): Nothing = throw AssertionError(m)
}
