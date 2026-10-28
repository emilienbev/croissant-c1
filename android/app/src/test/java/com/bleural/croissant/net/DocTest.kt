package com.bleural.croissant.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DocTest {

    // What an agent writes for a README: most of what the reader has to handle, in one file.
    private val readme = """
        # Weather page

        I built a **small** page. It reads the forecast from `api.open-meteo.com`
        and shows *today* and the next [five days](https://open-meteo.com/en/docs).

        ## Files

        - `index.html`: the page
        - `app.js`: fetches and draws
          - retries once on failure
        1. Open **index.html**
        2. Allow location

        > Note: the API needs no key.

        ---

        ```js
        fetch(url)
          .then(r => r.json())
        ```

        | Day | High | Low |
        |:----|-----:|----:|
        | Mon | **21** | 12 |
        | Tue | 19 |
    """.trimIndent()

    @Test fun readsTheBlocksAnLlmWrites() {
        val b = DocBlock.markdown(readme)
        assertEquals(
            listOf("Heading", "Paragraph", "Heading", "Item", "Item", "Item", "Item", "Item", "Quote", "Rule", "Code", "Table"),
            b.map { it::class.simpleName },
        )
        assertEquals(DocBlock.Heading(1, listOf(DocSpan("Weather page"))), b[0])
        val p = b[1] as DocBlock.Paragraph
        assertEquals(
            "I built a small page. It reads the forecast from api.open-meteo.com and shows today and the next five days.",
            p.spans.joinToString("") { it.text },
        )
        assertEquals(DocSpan("api.open-meteo.com", code = true), p.spans.first { it.code })
        assertEquals(listOf(DocSpan("small", bold = true), DocSpan("today", italic = true)), p.spans.filter { it.bold || it.italic })
        val items = b.filterIsInstance<DocBlock.Item>()
        assertEquals(listOf(0, 0, 1, 0, 0), items.map { it.depth })
        assertEquals(listOf(null, null, null, 1, 2), items.map { it.number })
        assertEquals(listOf(DocSpan("Open "), DocSpan("index.html", bold = true)), items[3].spans)
        assertEquals(DocBlock.Quote(listOf(DocSpan("Note: the API needs no key."))), b[8])
        assertEquals(DocBlock.Code("fetch(url)\n  .then(r => r.json())", "js"), b[10])
        assertEquals(
            DocTable(listOf("Day", "High", "Low"), listOf(listOf("Mon", "21", "12"), listOf("Tue", "19", ""))),
            (b[11] as DocBlock.Table).table,
        )
    }

    @Test fun inlineMarkersOnlyWhereTheyClose() {
        assertEquals(
            listOf(DocSpan("Run "), DocSpan("snake_case.py", code = true), DocSpan(" with 2 * 3 "),
                DocSpan("args", bold = true, italic = true), DocSpan(", see the docs, my_var_name and **stray")),
            DocSpan.of("Run `snake_case.py` with 2 * 3 ***args***, see [the docs](https://x.org/a_(b)), my_var_name and **stray"),
        )
        assertEquals(listOf(DocSpan("a | b and a\nbreak")), DocSpan.of("""a \| b and a<br>break"""))
    }

    @Test fun anUnclosedFenceRunsToTheEnd() {
        val b = DocBlock.markdown("Here:\n\n```\nline 1\n\nline 2")
        assertEquals(DocBlock.Code("line 1\n\nline 2", null), b.last())
    }

    @Test fun aCodeBlockLosesOnlyItsFencesIndent() {
        val b = DocBlock.markdown("  ```\n\tfoo\n    bar\n baz\n  ```")
        assertEquals(DocBlock.Code("foo\n  bar\nbaz", null), b.single())
    }

    @Test fun aRunawayCellIsClipped() {
        // A stray quote: the rest of the file is one cell.
        val t = DocTable.csv("name,notes\nLee,\"said hi\n" + "Kim,9\n".repeat(100))
        assertEquals(1, t.rows.size)
        assertEquals(DocTable.CELL, t.rows[0][1].length)
        assertEquals(DocTable(listOf("a"), listOf(listOf("x".repeat(DocTable.CELL)))), DocBlock.markdown("| a |\n|---|\n| ${"x".repeat(500)} |").let { (it.single() as DocBlock.Table).table })
    }

    @Test fun readsACsvWithQuotesAndBreaks() {
        val csv = "name,notes,score\r\n\"Smith, J\",\"said \"\"hi\"\"\nthen left\",7\nLee,,9,extra\n\nKim\n"
        assertEquals(
            DocTable(
                listOf("name", "notes", "score", ""),
                listOf(
                    listOf("Smith, J", "said \"hi\"\nthen left", "7", ""),
                    listOf("Lee", "", "9", "extra"),
                    listOf("Kim", "", "", ""),
                ),
            ),
            DocTable.csv(csv),
        )
        assertEquals(DocTable(listOf("a", "b, c"), listOf(listOf("1", "2"))), DocTable.csv("a\tb, c\n1\t2", '\t'))
    }

    // ---- streaming ----

    @Test fun aFinishedBlockIsBeforeTheBlankLineAtTheMargin() {
        val text = "First paragraph.\n\n- one\n\n  - nested\n\nNow wri"
        assertEquals(text.indexOf("Now"), DocStream.settled(text))
        assertEquals(0, DocStream.settled("One line, still arriving"))
    }

    @Test fun aFenceStillOpenIsOneBlockWithItsBlankLines() {
        val text = "Before.\n\n```kotlin\nval a = 1\n\nval b"
        assertEquals(text.indexOf("```"), DocStream.settled(text))
        assertEquals("```kotlin\nval a = 1\n\nval b", DocStream.closed("```kotlin\nval a = 1\n\nval b"))
    }

    @Test fun whatIsOpenIsClosedForNow() {
        assertEquals("It is **bold so far**", DocStream.closed("It is **bold so far"))
        assertEquals("Run `npm in`", DocStream.closed("Run `npm in"))
        assertEquals("**bold *and it***", DocStream.closed("**bold *and it"))
        assertEquals(listOf(DocSpan("It is "), DocSpan("bold so far", bold = true)), DocSpan.of(DocStream.closed("It is **bold so far")))
    }

    @Test fun aMarkerWithNothingAfterItYetIsLeftOut() {
        assertEquals("Then ", DocStream.closed("Then **"))
        assertEquals("Then ", DocStream.closed("Then `"))
    }

    @Test fun aLinkNotYetWholeIsItsWords() {
        assertEquals("See the docs", DocStream.closed("See the [docs](https://exa"))
        assertEquals("See the do", DocStream.closed("See the [do"))
    }

    @Test fun onlyTheLastLineIsClosed() {
        assertEquals("- a * b\n- **two**", DocStream.closed("- a * b\n- **two"))
        assertEquals("* item **bold**", DocStream.closed("* item **bold"))
        assertEquals("snake_case and 2 * 3", DocStream.closed("snake_case and 2 * 3"))
    }
}
