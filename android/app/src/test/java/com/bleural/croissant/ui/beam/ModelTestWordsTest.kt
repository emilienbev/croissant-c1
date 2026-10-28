package com.bleural.croissant.ui.beam

import com.bleural.croissant.net.BenchCaseResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** The model test's words as Beam says them (AskTools.kt): how long it takes, and what a miss did. */
class ModelTestWordsTest {
    @Test fun howLongBeforeTheStart() {
        assertEquals("0 sentences", testLine(0))
        assertEquals("1 sentence, under a minute", testLine(1))
        assertEquals("27 sentences, under a minute", testLine(27))
        assertEquals("37 sentences, about a minute", testLine(37))
        assertEquals("80 sentences, about 2 minutes", testLine(80))
    }

    private fun miss(expect: String, vararg called: String, argsOk: Boolean? = null, dated: Boolean = false, error: String? = null) =
        missWords(BenchCaseResult(0, "say", expect, called.toList(), false, dated, "", 0, error, argsOk))

    @Test fun whatAMissDid() {
        assertEquals("none", miss("agenda_add"))
        assertEquals("web_search", miss("none", "web_search"))
        assertEquals("agenda_list, agenda_add", miss("agenda_add", "agenda_list", "agenda_add", "agenda_add"))
        assertEquals("wrong args", miss("shopping_add_item", "shopping_add_item", argsOk = false))
        assertEquals("a date made up", miss("agenda_add", "agenda_add", dated = true))
        assertEquals("error", miss("agenda_add", "agenda_add", error = "timeout"))
    }
}
