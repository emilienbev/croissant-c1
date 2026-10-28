package com.bleural.croissant.ui

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard's rules (`ui/Keyboard.kt`): the edits, the capitals, the layouts and where a touch lands. */
class KeyboardTest {
    @Test fun typingReplacesTheSelection() {
        assertEquals(Edit(2, 2, "a"), typed(TextRange(2), "a"))
        assertEquals(Edit(1, 4, "a"), typed(TextRange(4, 1), "a"))
    }

    @Test fun deleteTakesTheSelectionOrOneCharacter() {
        assertEquals(Edit(1, 3, ""), deleted("abcd", TextRange(1, 3)))
        assertEquals(Edit(2, 3, ""), deleted("abcd", TextRange(3)))
        assertNull(deleted("abcd", TextRange(0)))
        // A character outside the basic plane is two chars, deleted as one.
        assertEquals(Edit(1, 3, ""), deleted("a😀", TextRange(3)))
    }

    @Test fun theWheelStepsTheCaretAndStopsAtTheEnds() {
        assertEquals(3, stepped("abcd", 2, 1))
        assertEquals(1, stepped("abcd", 2, -1))
        assertEquals(4, stepped("abcd", 4, 1))
        assertEquals(0, stepped("abcd", 0, -1))
        assertEquals(4, stepped("abcd", 1, 9))
        // A character outside the basic plane is one step.
        assertEquals(3, stepped("a\uD83D\uDE00b", 1, 1))
        assertEquals(1, stepped("a\uD83D\uDE00b", 3, -1))
    }

    @Test fun capitalsFollowTheField() {
        val s = KeyboardCapitalization.Sentences
        assertTrue(capsAt("", 0, s))
        assertTrue(capsAt("Hi. ", 4, s))
        assertFalse(capsAt("Hi ", 3, s))
        assertFalse(capsAt("Hi.", 3, s))
        assertTrue(capsAt("new ", 4, KeyboardCapitalization.Words))
        assertFalse(capsAt("", 0, KeyboardCapitalization.None))
    }

    @Test fun everyRowOfAPageIsAsWide() {
        for (mode in KeyMode.entries) for (page in KeyPage.entries) {
            val widths = Layouts.rows(mode, page).map { it.width }.distinct()
            assertEquals("$mode $page", 1, widths.size)
        }
    }

    @Test fun aPasswordCanHoldEveryPrintableAscii() {
        val typeable = KeyPage.entries.flatMap { Layouts.rows(KeyMode.Password, it) }
            .flatMap { it.keys }
            .filter { it.kind == KeyKind.Char }
            .flatMap { listOf(it.text, it.text.uppercase()) }
            .toSet() + " "
        val missing = (' '..'~').map { it.toString() }.filter { it !in typeable }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun touchesLandOnTheNearestKey() {
        val rows = Layouts.rows(KeyMode.Text, KeyPage.Letters)
        // 480 wide, rows 64 tall from 10: key widths of 48.
        fun at(x: Float, y: Float) = keyAt(rows, Offset(x, y), 480f, 10f, 64f)?.text
        assertEquals("q", at(1f, 30f))
        assertEquals("p", at(479f, 30f))
        // The second row is inset by half a key; its end keys take the inset.
        assertEquals("a", at(2f, 100f))
        assertEquals("l", at(478f, 100f))
        assertEquals("s", at(24f + 48f + 1f, 100f))
        // Above the first row and under the last still land.
        assertEquals("q", at(1f, -20f))
        assertEquals(KeyKind.Action, keyAt(rows, Offset(479f, 900f), 480f, 10f, 64f)?.kind)
    }

    @Test fun theSameKeyIsTheSameObject() {
        assertTrue(Layouts.rows(KeyMode.Text, KeyPage.Letters) === Layouts.rows(KeyMode.Text, KeyPage.Letters))
    }

    @Test fun theKeysTypeThroughTheFieldsTransformation() {
        val text = TextFieldState("12")
        val keys = KeyboardState()
        keys.attach(KeyField(text, KeyMode.Number, ImeAction.Done, KeyboardCapitalization.None, {}) { InputTransformation.maxLength(3) })
        val digits = Layouts.rows(KeyMode.Number, KeyPage.Letters).flatMap { it.keys }.filter { it.kind == KeyKind.Char }
        keys.press(digits.first { it.text == "3" })
        keys.press(digits.first { it.text == "4" })
        assertEquals("123", text.text.toString())
        assertEquals(TextRange(3), text.selection)
    }
}
