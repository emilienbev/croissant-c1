package com.bleural.croissant.send

import com.bleural.croissant.data.Recording
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * What a request asked for: the page, one of the photos, a tape's text, or nothing we serve.
 *
 * Everything that is not exactly one of our paths is [Unknown], including a path
 * carrying a token that is not this share's. There is no 403: a wrong token and a wrong
 * path are the same answer, so nothing on the network learns that a share exists.
 */
sealed interface Ask {
    data object Page : Ask
    data class Item(val index: Int) : Ask
    data object Text : Ask
    data object Unknown : Ask
}

/** What a send offers the phone: photos, or one tape's transcript. */
sealed interface Offer {
    /** Photos or generated pictures, a JPEG each, stacked on the page. */
    class Photos(val files: List<File>) : Offer
    /** A tape's transcript: read on the page, or saved from it as a text file. */
    class Tape(val rec: Recording) : Offer
}

/**
 * One send: what is being offered and the secret that reaches it.
 *
 * The token is in the path rather than a header or a form because the whole interaction
 * is a QR code and a camera: whatever the phone needs to know has to fit in the URL. Ten
 * bytes is eighty bits, which is far past what a three-minute window on a private LAN
 * needs, and it keeps the URL short enough to stay a 29-module code that scans from arm's
 * length.
 */
class Share(val offer: Offer, val token: String = mint()) {

    constructor(files: List<File>, token: String = mint()) : this(Offer.Photos(files), token)

    companion object {
        /** The port the device listens on. 8765 is the Mac's; this is its neighbour. */
        const val PORT = 8766

        /** A tape's text, under the token like a photo; the name the phone keeps comes from the header. */
        private const val TAPE_TEXT = "transcript.txt"

        private val random = SecureRandom()

        fun mint(): String = ByteArray(10).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    /** The photos being offered; none for a tape. */
    val files: List<File> get() = (offer as? Offer.Photos)?.files.orEmpty()

    /** How many things the phone has to fetch before the screen says sent: each photo, or a tape's page. */
    val pieces: Int get() = when (offer) {
        is Offer.Photos -> offer.files.size
        is Offer.Tape -> 1
    }

    /** Which of the [pieces] a request delivered, or null for one that delivered none. */
    fun piece(ask: Ask): Int? = when {
        offer is Offer.Photos && ask is Ask.Item -> ask.index
        offer is Offer.Tape && ask is Ask.Page -> 0
        else -> null
    }

    /** The URL the QR carries, for whichever address the screen is offering. */
    fun url(host: String): String = "http://$host:$PORT/s/$token"

    /**
     * What [path] resolves to, the query string and any trailing slash ignored so that a
     * scanner or a browser that decorates the URL still lands on the page.
     */
    fun route(path: String): Ask {
        val clean = path.substringBefore('?').substringBefore('#').trimEnd('/')
        val prefix = "/s/$token"
        if (clean == prefix) return Ask.Page
        if (!clean.startsWith("$prefix/")) return Ask.Unknown
        val rest = clean.removePrefix("$prefix/")
        if (offer is Offer.Tape) return if (rest == TAPE_TEXT) Ask.Text else Ask.Unknown
        val i = rest.removeSuffix(".jpg").toIntOrNull() ?: return Ask.Unknown
        return if (rest.endsWith(".jpg") && i in files.indices) Ask.Item(i) else Ask.Unknown
    }

    /** The page Safari gets: the photos, or the tape. */
    fun page(): String = when (offer) {
        is Offer.Photos -> photosPage()
        is Offer.Tape -> tapePage(offer.rec, "/s/$token/$TAPE_TEXT")
    }

    /** A tape's transcript as a text file, and the name the phone saves it under. */
    fun text(): String = (offer as Offer.Tape).rec.let(::tapeText)
    fun textName(): String = (offer as Offer.Tape).rec.let(::tapeFileName)

    /**
     * The photos stacked full width under one line.
     *
     * The line is there because iOS gives no affordance for saving an image and the
     * gesture is not guessable. It is the only copy on the page; the pictures say the
     * rest. Nothing of the device's goes into the HTML but the token we minted, so there
     * is nothing here to escape.
     */
    private fun photosPage(): String {
        val what = if (files.size == 1) "the photo" else "a photo"
        val imgs = files.indices.joinToString("\n") { "<img src=\"/s/$token/$it.jpg\">" }
        return """
            <!doctype html>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Croissant</title>
            <style>
            body{margin:0;background:#0B0B0C;color:#8E8A87;
              font:16px/1.4 -apple-system,system-ui,sans-serif;text-align:center}
            p{margin:18px 16px}
            img{display:block;width:100%;height:auto;margin:0 0 12px}
            </style>
            <p>Press and hold $what to save it.</p>
        """.trimIndent() + "\n" + imgs + "\n"
    }
}
