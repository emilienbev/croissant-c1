package com.bleural.croissant.ui.beam

import com.bleural.croissant.atelier.AppPage
import com.bleural.croissant.atelier.AppRuntime
import com.bleural.croissant.atelier.Keys
import com.bleural.croissant.atelier.Piece
import com.bleural.croissant.atelier.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atelier as Beam draws it (AtelierRules.kt): hues, words, the space between pieces, touches. */
class AtelierRulesTest {
    @Test fun beamsNeonsStayAsTheyAre() {
        // Every feature's hue (Ramp.kt hueOf), white included.
        val neons = listOf(0xFF4F00, 0xFF2B2B, 0x1FA2FF, 0x8F8AFF, 0xFF2E8F, 0xD08CFF, 0x7DD3FC, 0x2CE5E8, 0xFFE01A, 0x62F23A, 0xFFA41B, 0x4CF2B8, 0xFF8F80, 0xFFFFFF)
        for (h in neons) assertEquals("%06X".format(h), h, liftHue(h))
    }

    @Test fun darkHuesAreLiftedToCarryBlackWords() {
        for (h in listOf(0x000080, 0x800000, 0x006400, 0x000000, 0x202020, 0x4B0082, 0x0000FF, 0x8B4513)) {
            val l = liftHue(h)
            assertTrue("%06X lifted to %06X".format(h, l), luminance(l) >= 0.175)
        }
        // Brightened first, keeping its hue: a dark red becomes the red.
        assertEquals(0xFF0000, liftHue(0x800000))
        // Then mixed with white only as far as it needs: a navy becomes a periwinkle, still blue.
        val navy = liftHue(0x000080)
        assertTrue(luminance(navy) < 0.2)
        assertEquals(0xFF, navy and 0xFF)
        assertTrue((navy shr 16) and 0xFF < 0xC0)
    }

    @Test fun whatAnAppMayUseInTheInstallPagesWords() {
        assertEquals(listOf("the network", "the model", "your words"), Use.entries.map(::useWords))
    }

    @Test fun aToolsArgumentsAsAppJsonHasThem() {
        val one = """{"type":"object","properties":{"name":{"type":"string","description":" the item, a word or two "}},"required":["name"]}"""
        assertEquals(listOf("name: the item, a word or two"), argLines(one))
        // No description: the name alone. (org.json off the device keeps no order, so as a set.)
        val two = """{"type":"object","properties":{"topic":{"type":"string"},"day":{"type":"string","description":"today or tomorrow"}}}"""
        assertEquals(setOf("topic", "day: today or tomorrow"), argLines(two).toSet())
        assertEquals(emptyList<String>(), argLines("""{"type":"object","properties":{}}"""))
        assertEquals(emptyList<String>(), argLines("""{"type":"object"}"""))
        assertEquals(emptyList<String>(), argLines("not json"))
    }

    @Test fun whereAScriptFailed() {
        assertEquals("render, croissant.js line 42", failedWhere(AppRuntime.Failure("TypeError", AppRuntime.During.Render, 42)))
        assertEquals("start", failedWhere(AppRuntime.Failure("SyntaxError", AppRuntime.During.Start, null)))
        assertEquals("add a glass ran into an error", failedFoot(AppRuntime.Failure("x", AppRuntime.During.Handler("add a glass"), 3)))
        assertEquals("it ran into an error", failedFoot(AppRuntime.Failure("x", AppRuntime.During.Handler(""), null)))
        assertEquals("3f9c2ab", shortSha("3f9c2ab0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6"))
    }

    @Test fun piecesSpacedAsDrawn() {
        // shopping: rows from 74, the pill 28 under them.
        assertEquals(8f, pieceGap(null, Lay.Row))
        assertEquals(0f, pieceGap(Lay.Row, Lay.Row))
        assertEquals(28f, pieceGap(Lay.Row, Lay.Pills))
        // water: the number on 214 (its box from 104), the pill on 304, the rows on 386.
        assertEquals(214f - 110f - 66f, pieceGap(null, Lay.Big))
        assertEquals(304f - (268f + 8f), pieceGap(Lay.Big, Lay.Pills))
        assertEquals(386f - 350f, pieceGap(Lay.Pills, Lay.Row))
        // bill: the field from 88, the number on 292, the rows from 372.
        assertEquals(88f - 66f, pieceGap(null, Lay.Field))
        assertEquals(292f - 110f - 148f, pieceGap(Lay.Field, Lay.Big))
        assertEquals(372f - (346f + 8f), pieceGap(Lay.Big, Lay.Row))
        // shopping, empty: words on 112.
        assertEquals(112f - 32f - 66f, pieceGap(null, Lay.Words))
    }

    @Test fun touchesGoToThePieceInTheirPlace() {
        val page = AppPage(
            7,
            listOf(
                Piece.Words("hi"), Piece.Row("tip", "15 %", 0), Piece.Toggle("eggs", true, 1),
                Piece.Field("84", null, Keys.Number, 2, 3), Piece.Pill("add", 4), Piece.Row("total", "96", null),
            ),
            takesWords = false,
        )
        assertEquals(listOf(1, 2, 4), page.pieces.indices.filter { touchable(page.pieces[it]) })
        assertEquals(0 to null, resolve(page, Touch(1, Act.Tap)))
        assertEquals(1 to false, resolve(page, Touch(2, Act.Flip)))
        assertEquals(2 to "845", resolve(page, Touch(3, Act.Type, "845")))
        assertEquals(3 to "845", resolve(page, Touch(3, Act.Done, "845")))
        assertEquals(4 to null, resolve(page, Touch(4, Act.Tap)))
        // A row with no tap, words, a place past the end, a touch of the wrong kind: nothing.
        assertNull(resolve(page, Touch(5, Act.Tap)))
        assertNull(resolve(page, Touch(0, Act.Tap)))
        assertNull(resolve(page, Touch(9, Act.Tap)))
        assertNull(resolve(page, Touch(2, Act.Tap)))
        assertFalse(Act.Type.dims)
    }
}
