package com.bleural.croissant.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound

/** One band of a sheet: its word, hue and glyph, and what to do on the tap. */
class SheetAction(val word: String, val hue: Color, val glyph: ImageVector, val onAction: () -> Unit)

/**
 * What a sheet asks: one action, or a choice of several (the control centre's Power), under
 * Cancel. [question] and [line] under it, when given, say what is being asked above Cancel
 * (the Translator's local mode); every other sheet has none.
 */
class SheetSpec(val actions: List<SheetAction>, val question: String? = null, val line: String? = null)

/** The one open sheet, held by the shell so it draws above the rail. Screens call [show]. */
class SheetState {
    var current by mutableStateOf<SheetSpec?>(null)
        private set
    fun show(action: String, hue: Color, glyph: ImageVector, onAction: () -> Unit) = show(listOf(SheetAction(action, hue, glyph, onAction)))
    fun show(actions: List<SheetAction>, question: String? = null, line: String? = null) {
        current = SheetSpec(actions, question, line)
        Cues.play(Sound.SheetIn)
    }
    /** Closed without acting: Cancel or the scrim. */
    fun dismiss() { current = null; Cues.play(Sound.SheetDismiss) }
    /** Closed by an action band; the action fires its own event. */
    fun act(action: SheetAction) { current = null; action.onAction() }
    /**
     * Taken away because what it was asked about has left the screen. Silent, and silent
     * even when nothing was open: the thing that changed the screen has already spoken,
     * exactly as the control centre's own close does.
     */
    fun close() { current = null }
}

val LocalSheet = compositionLocalOf { SheetState() }
