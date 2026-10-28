package com.bleural.croissant.net

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {

    @Test fun aFileKindIsItsExtension() {
        assertEquals(
            listOf(FileKind.Markdown, FileKind.Table, FileKind.Table, FileKind.Image, FileKind.Text, FileKind.Text, FileKind.Other),
            listOf("README.md", "out/data.CSV", "a.tsv", "shots/chart.jpeg", "app.v2/Makefile", "main.py", "site.zip").map { FileKind.of(it) },
        )
    }

    @Test fun plainDropsMarkdownButKeepsLines() {
        val md = "## Done\n\nI wrote `hello.py`:\n\n```python\nprint(1)\n```\n\nFiles:\n- hello.py\n* out.txt\n**All good.**"
        assertEquals("Done\n\nI wrote hello.py:\n\nprint(1)\n\nFiles:\nhello.py\nout.txt\nAll good.", plain(md))
    }
}
