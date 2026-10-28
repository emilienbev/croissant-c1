package com.bleural.croissant.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The share's rules: what a URL resolves to and what the page says.
 * Both are what the phone meets, and neither needs a device to be wrong.
 */
class ShareTest {

    private val files = listOf(File("/tmp/a.jpg"), File("/tmp/b.jpg"))
    private val share = Share(files, token = "TOKEN")

    @Test fun `the page is the bare token path`() {
        assertEquals(Ask.Page, share.route("/s/TOKEN"))
        assertEquals(Ask.Page, share.route("/s/TOKEN/"))
        assertEquals(Ask.Page, share.route("/s/TOKEN?utm=1"))
    }

    @Test fun `a photo is its index`() {
        assertEquals(Ask.Item(0), share.route("/s/TOKEN/0.jpg"))
        assertEquals(Ask.Item(1), share.route("/s/TOKEN/1.jpg"))
    }

    /** A wrong token is not a 403 and not a hint: it is the same nothing a wrong path is. */
    @Test fun `a wrong token is unknown`() {
        assertEquals(Ask.Unknown, share.route("/s/OTHER"))
        assertEquals(Ask.Unknown, share.route("/s/OTHER/0.jpg"))
        assertEquals(Ask.Unknown, share.route("/"))
        assertEquals(Ask.Unknown, share.route("/s"))
    }

    /** Past the end of the share, and anything that is not a number, reach no file. */
    @Test fun `only the files in the share exist`() {
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/2.jpg"))
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/-1.jpg"))
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/x.jpg"))
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/0"))
        assertEquals(Ask.Unknown, share.route("/s/TOKEN/0.jpg/1.jpg"))
    }

    @Test fun `the page carries one img per file, and the token with them`() {
        val page = share.page()
        assertTrue(page, page.contains("""<img src="/s/TOKEN/0.jpg">"""))
        assertTrue(page, page.contains("""<img src="/s/TOKEN/1.jpg">"""))
        assertEquals(2, Regex("<img").findAll(page).count())
    }

    /** The copy rule: one photo is "the photo", several are "a photo". */
    @Test fun `the line reads for the number of photos`() {
        assertTrue(Share(files.take(1), "T").page().contains("Press and hold the photo to save it."))
        assertTrue(share.page().contains("Press and hold a photo to save it."))
    }

    @Test fun `the url is the address, the port and the token`() {
        assertEquals("http://192.168.1.42:8766/s/TOKEN", share.url("192.168.1.42"))
        assertEquals("http://100.100.100.2:8766/s/TOKEN", share.url("100.100.100.2"))
    }

    /**
     * Short enough to stay a small code, long enough that nothing guesses it: ten bytes
     * of SecureRandom is fourteen base64url characters, and the whole URL under fifty.
     */
    @Test fun `the token is fourteen url-safe characters and never repeats`() {
        val mint = List(200) { Share.mint() }
        mint.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9_-]{14}"))) }
        assertEquals(200, mint.toSet().size)
        assertTrue(Share(files).url("192.168.1.42").length < 50)
    }
}
