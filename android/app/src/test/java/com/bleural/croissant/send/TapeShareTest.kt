package com.bleural.croissant.send

import com.bleural.croissant.data.Recording
import com.bleural.croissant.data.RecordingMeta
import com.bleural.croissant.data.Segment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A tape's transcript sent by its code: the routes, the page,
 * the text, and the text coming back over a real socket as a file to save.
 */
class TapeShareTest {

    private fun tape(name: String? = null, segments: List<Segment> = SEGMENTS) = Recording(
        File("/tmp/20260924-160000.wav"),
        RecordingMeta("2026-09-24T16:00:00+01:00", 55 * 60_000L + 12_000, RecordingMeta.STOP, sent = "x", transcript = segments, number = 3, name = name),
    )

    private val share = Share(Offer.Tape(tape()), token = "TOKEN")
    private lateinit var server: ShareServer

    @Before fun start() {
        server = ShareServer(share, port = 0) {}
        assertTrue("could not listen", server.start())
    }

    @After fun stop() = server.stop()

    @Test fun `the page and the text are the only two paths`() {
        assertEquals(Ask.Page, share.route("/s/TOKEN"))
        assertEquals(Ask.Text, share.route("/s/TOKEN/transcript.txt"))
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/0.jpg"))
        assertEquals(Ask.Unknown, share.route("/s/OTHER/transcript.txt"))
        assertTrue(Share(listOf(File("/tmp/a.jpg")), "TOKEN").route("/s/TOKEN/transcript.txt") == Ask.Unknown)
    }

    /** The page is the one piece: the phone opening it is the send done; the text is extra. */
    @Test fun `the page is what counts as sent`() {
        assertEquals(1, share.pieces)
        assertEquals(0, share.piece(Ask.Page))
        assertNull(share.piece(Ask.Text))
        assertTrue(share.files.isEmpty())
    }

    @Test fun `the text is the name, the line, and each paragraph under its stamp`() {
        assertEquals(
            "Tape 3\nThu 24 Sep 2026, 16:00 · 55 min 12 s\n\n0:00\nRight, shall we start? Sam sent the numbers.\n\n0:11\nYes.\n",
            share.text(),
        )
    }

    @Test fun `the page links the text and escapes the tape's words`() {
        val page = Share(Offer.Tape(tape(name = "Q&A <draft>", segments = listOf(Segment(0.0, 2.0, "a < b & \"c\"")))), "T").page()
        assertTrue(page, page.contains("""<a href="/s/T/transcript.txt" download>"""))
        assertTrue(page, page.contains("<h1>Q&amp;A &lt;draft&gt;</h1>"))
        assertTrue(page, page.contains("a &lt; b &amp; &quot;c&quot;"))
        assertFalse(page, page.contains("<draft>"))
    }

    @Test fun `the file is named after the tape, less what a file system refuses`() {
        assertEquals("Tape 3.txt", share.textName())
        assertEquals("Budget 2425.txt", Share(Offer.Tape(tape(name = "Budget 24/25")), "T").textName())
        assertEquals("Réunion.txt", Share(Offer.Tape(tape(name = "Réunion?")), "T").textName())
        assertEquals("Tape 3.txt", Share(Offer.Tape(tape(name = "///")), "T").textName())
    }

    @Test fun `the text comes back as a file to save, named in utf-8`() {
        val named = Share(Offer.Tape(tape(name = "Réunion")), "T")
        val s = ShareServer(named, port = 0) {}.apply { assertTrue(start()) }
        try {
            val c = URL("http://127.0.0.1:${s.boundPort}/s/T/transcript.txt").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("text/plain; charset=utf-8", c.getHeaderField("Content-Type"))
            assertEquals("attachment; filename=\"R_union.txt\"; filename*=UTF-8''R%C3%A9union.txt", c.getHeaderField("Content-Disposition"))
            assertTrue(String(c.inputStream.readBytes(), Charsets.UTF_8).startsWith("Réunion\n"))
            c.disconnect()
        } finally { s.stop() }
    }

    @Test fun `the page comes back as html over the socket`() {
        val c = URL("http://127.0.0.1:${server.boundPort}/s/TOKEN").openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertTrue(String(c.inputStream.readBytes()).contains("Sam sent the numbers."))
        c.disconnect()
    }

    private companion object {
        val SEGMENTS = listOf(
            Segment(0.0, 4.0, " Right, shall we start? "), Segment(4.2, 8.0, "Sam sent the numbers."),
            Segment(11.0, 14.0, "Yes."),
        )
    }
}
