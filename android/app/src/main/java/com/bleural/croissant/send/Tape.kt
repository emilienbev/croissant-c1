package com.bleural.croissant.send

import com.bleural.croissant.data.Recording
import com.bleural.croissant.data.lengthWords
import com.bleural.croissant.data.paragraphs
import com.bleural.croissant.data.stampText
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * A tape as the phone gets it when its code is scanned: the transcript as a page to read,
 * and the same words as a text file to keep. Paragraphs and
 * stamps as the recording page shows them, so the two read alike.
 */

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, H:mm", Locale.ENGLISH)

/** "Thu 24 Sep 2026, 16:00 · 55 min 12 s": the one line under the name. */
private fun tapeLine(rec: Recording): String =
    listOfNotNull(rec.startedAt?.format(WHEN), lengthWords(rec.durationMs)).joinToString(" · ")

/** The name, the line, then each paragraph under its stamp, a blank line between. */
fun tapeText(rec: Recording): String = buildString {
    append(rec.title).append('\n').append(tapeLine(rec)).append('\n')
    for (p in paragraphs(rec.meta?.transcript.orEmpty())) append('\n').append(stampText(p.start)).append('\n').append(p.text).append('\n')
}

/** "Tape 3.txt", or the tape's own name with anything a file system would refuse left out. */
fun tapeFileName(rec: Recording): String =
    rec.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { "Tape ${rec.number}" } + ".txt"

/**
 * The page: the name, the line, a link to save the text, and the paragraphs. The link is
 * the only copy that is not the tape's own; Safari's select and copy do the rest. The
 * words are the tape's, so everything that comes from it is escaped.
 */
fun tapePage(rec: Recording, textPath: String): String {
    val paras = paragraphs(rec.meta?.transcript.orEmpty()).joinToString("\n") {
        "<p><span>${stampText(it.start)}</span>${esc(it.text)}</p>"
    }
    return """
        <!doctype html>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${esc(rec.title)}</title>
        <style>
        body{margin:0 auto;max-width:680px;padding:24px 16px 48px;background:#0B0B0C;color:#F2F2F3;
          font:17px/1.5 -apple-system,system-ui,sans-serif;-webkit-text-size-adjust:100%}
        h1{margin:0;font-size:30px;font-weight:500;line-height:1.15}
        .line{margin:6px 0 0;color:#8E8A87;font-size:15px}
        a{display:inline-block;margin:18px 0 8px;color:#FF4F00;text-decoration:none;font-size:15px}
        p{margin:22px 0 0}
        p span{display:block;margin-bottom:2px;color:#8E8A87;font-size:14px;font-variant-numeric:tabular-nums}
        </style>
        <h1>${esc(rec.title)}</h1>
        <div class="line">${esc(tapeLine(rec))}</div>
        <a href="$textPath" download>Save as text</a>
    """.trimIndent() + "\n" + paras + "\n"
}

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
