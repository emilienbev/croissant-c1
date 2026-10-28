package com.bleural.croissant.net

import java.util.Locale

/**
 * An agent's words without the markdown models leave in them: code fences and backticks,
 * links and pictures as their words, emphasis, headings, list bullets. Line breaks are kept.
 */
fun plain(text: String): String = text
    .replace(Regex("^```.*$", RegexOption.MULTILINE), "")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace("`", "")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/** How a file is shown, by its name; whether it is text is only sure once it is read. */
enum class FileKind {
    Markdown, Table, Image, Text, Other;

    companion object {
        fun of(path: String): FileKind = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "md", "markdown" -> Markdown
            "csv", "tsv" -> Table
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Image
            in BINARY -> Other
            else -> Text
        }

        private val BINARY = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "gz", "tgz", "tar", "xz", "bz2", "7z",
            "mp3", "wav", "ogg", "flac", "m4a", "mp4", "mov", "webm", "bin", "so", "o", "pyc", "class", "jar",
            "sqlite", "db", "xlsx", "docx", "pptx", "woff", "woff2", "ttf", "otf",
        )
    }
}
