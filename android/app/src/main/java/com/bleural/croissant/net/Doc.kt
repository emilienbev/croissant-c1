package com.bleural.croissant.net

/*
 * What a file's page draws an agent's file as, and an agent's words in its conversation:
 * markdown as blocks of styled runs, a CSV or a markdown table as a table. Line by line and
 * forgiving, made for what an LLM writes rather than for CommonMark. Plain Kotlin, so every
 * design draws from the same reading and the JVM tests cover it. [DocStream] reads markdown
 * still arriving.
 */

/** A run of text and how it is set. A link is its text, an image its alt text. */
data class DocSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) {
    companion object {
        /** One block's inline markdown: bold, italic, code, links. */
        fun of(text: String): List<DocSpan> = Inline(text).read()
    }
}

sealed interface DocBlock {
    data class Heading(val level: Int, val spans: List<DocSpan>) : DocBlock
    data class Paragraph(val spans: List<DocSpan>) : DocBlock
    /** A list item: [number] as written for a numbered one, null for a bullet; [depth] 0 at the margin. */
    data class Item(val spans: List<DocSpan>, val depth: Int, val number: Int?) : DocBlock
    data class Code(val text: String, val language: String?) : DocBlock
    /** A quote; its paragraphs are apart by a line break. */
    data class Quote(val spans: List<DocSpan>) : DocBlock
    data object Rule : DocBlock
    data class Table(val table: DocTable) : DocBlock

    companion object {
        fun markdown(text: String): List<DocBlock> = Blocks(text.lines()).read()
    }
}

/** A table, every row as wide as the widest, each cell at most [CELL] characters. */
data class DocTable(val header: List<String>, val rows: List<List<String>>) {
    companion object {
        /** Far more than a column shows, and cheap to measure even when a stray quote in a CSV makes the rest of the file one cell. */
        const val CELL = 200

        /**
         * A CSV file, or a TSV one with [sep] a tab; the first row is the header. A quoted field
         * may hold the separator, a line break, and a quote doubled.
         */
        fun csv(text: String, sep: Char = ','): DocTable {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            fun endRow() {
                row += field.toString(); field.clear()
                if (row.size > 1 || row[0].isNotEmpty()) rows += row
                row = mutableListOf()
            }
            var i = 0
            val s = text.removePrefix("﻿")
            while (i < s.length) {
                val c = s[i++]
                when {
                    quoted && c == '"' -> if (s.getOrNull(i) == '"') { field.append('"'); i++ } else quoted = false
                    quoted -> field.append(c)
                    c == '"' && field.isBlank() -> { field.clear(); quoted = true }
                    c == sep -> { row += field.toString(); field.clear() }
                    c == '\n' -> endRow()
                    c != '\r' -> field.append(c)
                }
            }
            if (field.isNotEmpty() || row.isNotEmpty()) endRow()
            return of(rows)
        }

        internal fun of(rows: List<List<String>>): DocTable {
            val width = rows.maxOfOrNull { it.size } ?: 0
            val padded = rows.map { r -> r.map { it.take(CELL) } + List(width - r.size) { "" } }
            return DocTable(padded.firstOrNull().orEmpty(), padded.drop(1))
        }
    }
}

/**
 * Markdown as it streams in, a chunk at a time (an agent's words while its turn runs): the
 * blocks already finished apart from the one being written ([settled]), so only that one is read
 * again as words come; and that one closed for the time being ([closed]), so it reads as it will
 * once finished: a bold run bold rather than its stars, a link not yet whole as its words.
 */
object DocStream {
    /**
     * Where the block being written starts in [text]: just after the last blank line that a line
     * at the margin follows, outside a fenced block; 0 when there is none. What comes before it is
     * finished: however the text goes on, it reads the same. A nested list item is indented, so a
     * list stays one piece.
     */
    fun settled(text: String): Int {
        var at = 0
        var pos = 0
        var fence: String? = null
        var blank = false
        for (line in text.split('\n')) {
            val t = line.trim()
            if (fence != null) {
                if (t.length >= fence.length && t.all { it == fence!![0] }) fence = null
                blank = false
            } else {
                if (blank && t.isNotEmpty() && !line[0].isWhitespace()) at = pos
                FENCE.matchEntire(t)?.let { fence = it.groupValues[1] }
                blank = t.isEmpty()
            }
            pos += line.length + 1
        }
        return at
    }

    /**
     * [tail], the block being written, as it reads for now. Its last line, the one still
     * arriving: a marker at its very end with nothing after it yet is left out; a link or picture
     * not yet whole is its words; and what is open there (code, bold, italic) is closed at its
     * end, the last opened first. Inside a fenced block not yet closed nothing changes: it reads
     * as code to the end already.
     */
    fun closed(tail: String): String {
        if (openFence(tail)) return tail
        val cut = tail.lastIndexOf('\n') + 1
        var line = tail.substring(cut)
        line = line.replace(PART_LINK, "$1").replace(PART_TEXT, "$1").replace(BARE_MARK, "$1")
        return tail.substring(0, cut) + line + closers(line)
    }

    private fun openFence(text: String): Boolean {
        var fence: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (fence == null) FENCE.matchEntire(t)?.let { fence = it.groupValues[1] }
            else if (t.length >= fence!!.length && t.all { it == fence!![0] }) fence = null
        }
        return fence != null
    }

    /** What closes the code, bold and italic left open in [line], innermost first. */
    private fun closers(line: String): String {
        val open = mutableListOf<String>()
        var code = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            var n = 0
            while (line.getOrNull(i + n) == c) n++
            when {
                c == '`' -> code = when { code == 0 -> n; code == n -> 0; else -> code }
                code > 0 -> Unit
                c == '\\' -> n = 2
                c == '*' || c == '_' -> {
                    val before = line.getOrNull(i - 1)
                    val after = line.getOrNull(i + n)
                    val bullet = c == '*' && i == line.indexOfFirst { !it.isWhitespace() } && after == ' '
                    val inWord = c == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
                    if (!bullet && !inWord) {
                        var left = n
                        while (left > 0) {
                            val mark = if (left >= 2) "$c$c" else "$c"
                            left -= mark.length
                            val k = open.lastIndexOf(mark)
                            when {
                                k >= 0 && before?.isWhitespace() != true -> open.removeAt(k)
                                after != null && !after.isWhitespace() -> open += mark
                            }
                        }
                    }
                }
            }
            i += n
        }
        return (if (code > 0) "`".repeat(code) else "") + open.asReversed().joinToString("")
    }
}

/** A link or picture whose address is still arriving, and one whose text is: their words. */
private val PART_LINK = Regex("""!?\[([^\]\n]*)]\([^)\n]*$""")
private val PART_TEXT = Regex("""!?\[([^\]\n]*)]?$""")
/** Markers at the very end, after a space or nothing: an opening still waiting for its words. */
private val BARE_MARK = Regex("""(^|\s)(?:\*{1,3}|_{1,3}|`{1,3})$""")

private val HEADING = Regex("""^(#{1,6})\s+(.*?)(?:\s+#+)?\s*$""")
private val RULE = Regex("""^([-*_])(?:\s*\1){2,}\s*$""")
private val FENCE = Regex("""^(`{3,}|~{3,})\s*([^`\s]*)[^`]*$""")
private val ITEM = Regex("""^(?:[-*+]|(\d{1,9})[.)])(?:\s+(.*))?$""")
private val ALIGN = Regex("""^\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?$""")

private class Blocks(private val lines: List<String>) {
    private val out = mutableListOf<DocBlock>()
    /** The lines of the paragraph or item being gathered. */
    private val para = mutableListOf<String>()
    private var item: DocBlock.Item? = null
    /** The indents of the list levels open, outermost first. */
    private val levels = mutableListOf<Int>()
    private var i = 0

    fun read(): List<DocBlock> {
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            val fence = FENCE.matchEntire(t)
            val heading = HEADING.matchEntire(t)
            val listed = ITEM.matchEntire(t)
            when {
                t.isEmpty() -> flush()
                fence != null -> { flush(); code(fence.groupValues[1], fence.groupValues[2], indent(line)) }
                heading != null -> { end(); out += DocBlock.Heading(heading.groupValues[1].length, DocSpan.of(heading.groupValues[2])) }
                RULE.matches(t) -> { end(); out += DocBlock.Rule }
                t.startsWith(">") -> { end(); quote() }
                '|' in t && lines.getOrNull(i + 1)?.trim()?.let { '|' in it && ALIGN.matches(it) } == true -> { end(); table() }
                listed != null -> {
                    flush()
                    val at = indent(line)
                    while (levels.isNotEmpty() && levels.last() > at) levels.removeAt(levels.lastIndex)
                    if (levels.isEmpty() || levels.last() < at) levels += at
                    item = DocBlock.Item(emptyList(), levels.size - 1, listed.groupValues[1].toIntOrNull())
                    para += listed.groupValues[2]
                }
                else -> {
                    if (para.isEmpty() && indent(line) == 0) levels.clear()
                    para += line.trimStart()
                }
            }
            i++
        }
        flush()
        return out
    }

    /** The paragraph or item gathered so far, as a block. */
    private fun flush() {
        if (para.isNotEmpty()) {
            val spans = DocSpan.of(joined(para))
            out += item?.copy(spans = spans) ?: DocBlock.Paragraph(spans)
            para.clear()
        }
        item = null
    }

    /** A block that closes any list. */
    private fun end() {
        flush()
        levels.clear()
    }

    /** A fenced block, to its closing fence or the end of the text; its lines lose the fence's indent. */
    private fun code(fence: String, language: String, indent: Int) {
        val body = mutableListOf<String>()
        while (++i < lines.size) {
            val t = lines[i].trim()
            if (t.length >= fence.length && t.all { it == fence[0] }) break
            body += dedent(lines[i], indent)
        }
        out += DocBlock.Code(body.joinToString("\n"), language.ifEmpty { null })
    }

    private fun quote() {
        val body = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().startsWith(">")) body += lines[i++].trim().replace(Regex("""^(>\s?)+"""), "")
        i--
        val paras = body.joinToString("\n").split(Regex("""\n\s*\n""")).map { joined(it.lines()) }.filter { it.isNotBlank() }
        out += DocBlock.Quote(DocSpan.of(paras.joinToString("\n")))
    }

    private fun table() {
        val rows = mutableListOf(cells(lines[i]))
        i += 2
        while (i < lines.size && lines[i].trim().let { it.isNotEmpty() && '|' in it }) rows += cells(lines[i++])
        i--
        out += DocBlock.Table(DocTable.of(rows))
    }

    /** A table line's cells as plain text: the outer pipes dropped, an escaped one kept. */
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
            .split(Regex("""(?<!\\)\|"""))
            .map { cell -> DocSpan.of(cell.replace("\\|", "|").trim()).joinToString("") { it.text } }

    /** Lines of one block as its text: joined by a space, or by a line break after two spaces or a backslash. */
    private fun joined(lines: List<String>): String = buildString {
        lines.forEachIndexed { k, l ->
            val hard = l.endsWith("  ") || l.trimEnd().endsWith("\\")
            append(l.trim().let { if (hard) it.removeSuffix("\\").trimEnd() else it })
            if (k < lines.lastIndex) append(if (hard) '\n' else ' ')
        }
    }

    /** [line] without its leading spaces and tabs, as far as [cols] columns (a tab is 4, as in [indent]). */
    private fun dedent(line: String, cols: Int): String {
        var k = 0
        var n = 0
        while (n < cols && k < line.length && (line[k] == ' ' || line[k] == '\t')) n += if (line[k++] == '\t') 4 else 1
        return line.substring(k)
    }

    private fun indent(line: String): Int {
        var n = 0
        for (c in line) n += when (c) { ' ' -> 1; '\t' -> 4; else -> return n }
        return n
    }
}

private val BREAK = Regex("""br\s*/?""", RegexOption.IGNORE_CASE)

/** Inline markdown read in one pass, emphasis toggled where a marker is open and has a close ahead. */
private class Inline(private val s: String) {
    private val out = mutableListOf<DocSpan>()
    private val run = StringBuilder()
    private var bold = false
    private var italic = false

    fun read(): List<DocSpan> {
        var i = 0
        var linkEnd = -1 // the ']' closing a link's text; its "(url)" is skipped from there to skipTo
        var skipTo = 0
        while (i < s.length) {
            val c = s[i]
            when {
                linkEnd in 0..i -> { i = maxOf(i, skipTo); linkEnd = -1 }
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCTUATION -> { run.append(s[i + 1]); i += 2 }
                c == '`' -> i = code(i)
                c == '*' || c == '_' -> i = emphasis(i)
                c == '[' || (c == '!' && s.getOrNull(i + 1) == '[') -> {
                    val open = if (c == '!') i + 1 else i
                    val close = closing(open, '[', ']')
                    val end = if (close > 0 && s.getOrNull(close + 1) == '(') closing(close + 1, '(', ')') else -1
                    if (end > 0) { linkEnd = close; skipTo = end + 1; i = open + 1 } else { run.append(c); i++ }
                }
                c == '<' -> {
                    val end = s.indexOf('>', i)
                    val tag = if (end > 0) s.substring(i + 1, end) else ""
                    when {
                        BREAK.matches(tag) -> { run.append('\n'); i = end + 1 }
                        tag.startsWith("http://") || tag.startsWith("https://") -> { run.append(tag); i = end + 1 }
                        else -> { run.append(c); i++ }
                    }
                }
                else -> { run.append(c); i++ }
            }
        }
        emit()
        return out
    }

    /** A code span: as many backticks close it as opened it; with no close, they are text. */
    private fun code(i: Int): Int {
        var n = 0
        while (s.getOrNull(i + n) == '`') n++
        val tick = "`".repeat(n)
        var close = s.indexOf(tick, i + n)
        while (close >= 0) {
            var e = close
            while (s.getOrNull(e) == '`') e++
            if (e - close == n) break
            close = s.indexOf(tick, e)
        }
        if (close < 0) { run.append(tick); return i + n }
        emit()
        val inner = s.substring(i + n, close)
        val trimmed = if (inner.length >= 2 && inner.isNotBlank() && inner.first() == ' ' && inner.last() == ' ') inner.substring(1, inner.length - 1) else inner
        add(DocSpan(trimmed, bold, italic, code = true))
        return close + n
    }

    /** A run of `*` or `_`: two toggle bold, one italic, and any left over is text. */
    private fun emphasis(i: Int): Int {
        val c = s[i]
        var n = 0
        while (s.getOrNull(i + n) == c) n++
        val before = s.getOrNull(i - 1)
        val after = s.getOrNull(i + n)
        // An underscore inside a word is the word's (snake_case).
        if (c == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true) { run.append(s, i, i + n); return i + n }
        val opens = after != null && !after.isWhitespace()
        var left = n
        if (left >= 2 && (bold || opens && s.indexOf("$c$c", i + n) >= 0)) { emit(); bold = !bold; left -= 2 }
        if (left >= 1 && (italic || opens && s.indexOf(c, i + n) >= 0)) { emit(); italic = !italic; left -= 1 }
        repeat(left) { run.append(c) }
        return i + n
    }

    /** Where the bracket opened at [at] closes, counting the ones nested in it; -1 when it does not. */
    private fun closing(at: Int, open: Char, close: Char): Int {
        var depth = 0
        for (j in at until s.length) {
            if (s[j] == open) depth++
            if (s[j] == close && --depth == 0) return j
        }
        return -1
    }

    private fun emit() {
        if (run.isEmpty()) return
        add(DocSpan(run.toString(), bold, italic))
        run.clear()
    }

    /** A span, merged into the one before when they are set alike. */
    private fun add(span: DocSpan) {
        val last = out.lastOrNull()
        if (last != null && last.bold == span.bold && last.italic == span.italic && last.code == span.code) {
            out[out.lastIndex] = last.copy(text = last.text + span.text)
        } else out += span
    }

    companion object {
        private const val PUNCTUATION = """!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""
    }
}
