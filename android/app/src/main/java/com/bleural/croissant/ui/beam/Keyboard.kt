package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.bleural.croissant.ui.KeyCap
import com.bleural.croissant.ui.KeyKind
import com.bleural.croissant.ui.KeyPage
import com.bleural.croissant.ui.KeySkin
import com.bleural.croissant.ui.KeyboardState
import com.bleural.croissant.ui.Keys
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.Shift

/**
 * Beam's keyboard, the faint keys (ui/Keyboard.kt does the keys and the typing). Rows 64
 * apart under 10 of padding; each key a faint face 6 narrower and 10 shorter than its cell,
 * its character at 34 in ink; the action key in the hue with its word in black; a pressed
 * character lit in the hue and shown at 56 in a hue tab above the finger.
 */
@Composable
internal fun BeamKeyboard(state: KeyboardState, r: Ramp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val skin = remember(r, measurer) { BeamKeySkin(r, measurer) }
    Keys(state, skin, modifier.fillMaxWidth())
}

/**
 * Round a Beam field ([OwnTextField]): the caret's handle and a selection in [r]'s hue (the
 * selection at 40%) rather than the system's orange.
 */
@Composable
internal fun HueSelection(r: Ramp, content: @Composable () -> Unit) {
    val colours = remember(r) { TextSelectionColors(handleColor = r.hue, backgroundColor = r.hue.copy(alpha = 0.4f)) }
    CompositionLocalProvider(LocalTextSelectionColors provides colours, content = content)
}

/**
 * A field's own word for the action key, by its state ([Field]'s `action`): "find" on
 * Atelier's add page, where no `imeAction` says it. Read and written on the main thread.
 */
internal val ACTION_WORDS = java.util.WeakHashMap<TextFieldState, String>()

/** The keys' height for [rows] rows, what the pages stand above while they are up. */
internal fun keysHeight(rows: Int): Dp = 10.px + 64.px * rows + 12.px

private class BeamKeySkin(private val r: Ramp, private val measurer: TextMeasurer) : KeySkin {
    override val row: Dp = 64.px
    override val top: Dp = 10.px
    override val bottom: Dp = 12.px

    private val char = BeamType.Select
    private val big = BeamType.Second
    private val word = BeamType.Label
    private val tab = BeamType.Select.copy(fontSize = 56f.pxSp, fontWeight = FontWeight.Medium)

    override fun DrawScope.ground() = drawRect(GROUND)

    override fun DrawScope.key(cap: KeyCap, state: KeyboardState) {
        val u = 1.px.toPx()
        val face = Rect(cap.cell.left + 3 * u, cap.cell.top + 5 * u, cap.cell.right - 3 * u, cap.cell.bottom - 5 * u)
        val k = cap.key
        val lit = cap.pressed || k.kind == KeyKind.Action || (k.kind == KeyKind.Shift && state.shift == Shift.Lock)
        drawRoundRect(if (lit) r.hue else FAINT, face.topLeft, face.size, CornerRadius(10 * u))
        val on = if (lit) GROUND else INK
        when (k.kind) {
            KeyKind.Char -> label(cap.label, if (face.width > 100 * u) big else char, on, face)
            KeyKind.Space -> {}
            KeyKind.Page -> label(
                when (KeyPage.valueOf(k.text)) { KeyPage.Letters -> "abc"; KeyPage.Symbols -> "123"; KeyPage.More -> "=\\<" },
                word, on, face,
            )
            KeyKind.Action -> label(
                state.field?.text?.let { ACTION_WORDS[it] }
                    ?: when (state.field?.action) { ImeAction.Go -> "go"; ImeAction.Search -> "search"; ImeAction.Send -> "send"; ImeAction.Next -> "next"; ImeAction.Previous -> "back"; else -> "done" },
                word.copy(fontWeight = FontWeight.Medium), on, face,
            )
            KeyKind.Shift -> glyph(SHIFT, if (state.shift == Shift.Once) r.hue else on, face, 30 * u)
            KeyKind.Delete -> glyph(DELETE, on, face, 34 * u)
        }
    }

    override fun DrawScope.popup(cap: KeyCap) {
        val u = 1.px.toPx()
        val w = maxOf(cap.cell.width * 1.5f, 72 * u)
        val h = 96 * u
        val left = (cap.cell.center.x - w / 2).coerceIn(0f, size.width - w)
        val rect = Rect(left, cap.cell.top + 5 * u - h - 6 * u, left + w, cap.cell.top + 5 * u - 6 * u)
        drawRoundRect(r.hue, rect.topLeft, rect.size, CornerRadius(14 * u))
        label(cap.label, tab, GROUND, rect)
    }

    private fun DrawScope.label(text: String, style: TextStyle, colour: Color, inside: Rect) {
        val m = measurer.measure(text, style.copy(color = colour))
        drawText(m, topLeft = Offset(inside.center.x - m.size.width / 2f, inside.center.y - m.size.height / 2f))
    }

    /** A 24-unit outline drawn [side] across, centred in [inside]. */
    private fun DrawScope.glyph(path: Path, colour: Color, inside: Rect, side: Float) {
        val s = side / 24f
        withTransform({ translate(inside.center.x - side / 2, inside.center.y - side / 2); scale(s, s, Offset.Zero) }) {
            drawPath(path, colour, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val SHIFT = Path().apply {
    moveTo(12f, 4f); lineTo(20f, 12.5f); lineTo(15.5f, 12.5f); lineTo(15.5f, 19.5f)
    lineTo(8.5f, 19.5f); lineTo(8.5f, 12.5f); lineTo(4f, 12.5f); close()
}

private val DELETE = Path().apply {
    moveTo(8.5f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(8.5f, 19f); lineTo(2.5f, 12f); close()
    moveTo(11.5f, 9f); lineTo(17.5f, 15f); moveTo(17.5f, 9f); lineTo(11.5f, 15f)
}
