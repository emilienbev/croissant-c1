package com.bleural.croissant.data

/*
 * A tape read as words: its transcript folded into paragraphs and the lengths and stamps
 * that go with them. Here rather than in a design because the screens and the share
 * (send/) both read a tape this way.
 */

/** "12 s", "1 min 49 s", "17 min", "1 h 05 min": the length in a tape's details, to the second under an hour. */
fun lengthWords(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return when {
        h > 0 -> "%d h %02d min".format(h, m)
        m > 0 && sec > 0 -> "$m min $sec s"
        m > 0 -> "$m min"
        else -> "$sec s"
    }
}

/** "17:13", or "1:05:12" past an hour: a transcript stamp. */
fun lengthClock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A transcript paragraph: where it starts, in seconds, and its words. */
data class Paragraph(val start: Double, val text: String)

/**
 * Whisper's segments folded into paragraphs for reading: a new one after about 45
 * seconds, or at a gap of more than two seconds between segments.
 */
fun paragraphs(segments: List<Segment>): List<Paragraph> {
    val out = ArrayList<Paragraph>()
    var start = 0.0; var end = 0.0
    val words = StringBuilder()
    for (s in segments) {
        val text = s.text.trim()
        if (text.isEmpty()) continue
        if (words.isNotEmpty() && (s.start - end > 2.0 || s.end - start > 45.0)) {
            out += Paragraph(start, words.toString()); words.clear()
        }
        if (words.isEmpty()) start = s.start else words.append(' ')
        words.append(text)
        end = s.end
    }
    if (words.isNotEmpty()) out += Paragraph(start, words.toString())
    return out
}

/** "4:30", or "1:04:30" past an hour: a paragraph's stamp. */
fun stampText(seconds: Double): String = lengthClock((seconds * 1000).toLong())
