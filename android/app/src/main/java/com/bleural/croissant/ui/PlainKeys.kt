package com.bleural.croissant.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp

/**
 * The plain keys, for a design that draws none of its own (Keyboard.kt does the keys and the
 * typing). Rows 64 apart under 10 of padding, on [Ink.Background]; each key a faint face
 * ([Ink.Line]) 6 narrower and 10 shorter than its cell, its character at 20 sp in
 * [Ink.OnBackground]; the action key lit in [accent] with its word in the ground; a pressed
 * character lit too and shown at 32 sp on an [accent] tab above the finger.
 */
@Composable
fun PlainKeys(state: KeyboardState, accent: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val skin = remember(accent, measurer) { PlainKeySkin(accent, measurer) }
    Keys(state, skin, modifier.fillMaxWidth())
}

private val PLAIN_ROW = 64.px
private val PLAIN_TOP = 10.px
private val PLAIN_BOTTOM = 12.px

private class PlainKeySkin(private val accent: Color, private val measurer: TextMeasurer) : KeySkin {
    override val row: Dp = PLAIN_ROW
    override val top: Dp = PLAIN_TOP
    override val bottom: Dp = PLAIN_BOTTOM

    private val char = Grotesk.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal)
    private val big = Grotesk.copy(fontSize = 27.sp, fontWeight = FontWeight.Normal)
    private val word = Grotesk.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    private val tab = Grotesk.copy(fontSize = 32.sp, fontWeight = FontWeight.Medium)

    override fun DrawScope.ground() = drawRect(Ink.Background)

    override fun DrawScope.key(cap: KeyCap, state: KeyboardState) {
        val u = 1.px.toPx()
        val face = Rect(cap.cell.left + 3 * u, cap.cell.top + 5 * u, cap.cell.right - 3 * u, cap.cell.bottom - 5 * u)
        val k = cap.key
        val lit = cap.pressed || k.kind == KeyKind.Action || (k.kind == KeyKind.Shift && state.shift == Shift.Lock)
        drawRoundRect(if (lit) accent else Ink.Line, face.topLeft, face.size, CornerRadius(10 * u))
        val on = if (lit) Ink.Background else Ink.OnBackground
        when (k.kind) {
            KeyKind.Char -> label(cap.label, if (face.width > 100 * u) big else char, on, face)
            KeyKind.Space -> {}
            KeyKind.Page -> label(
                when (KeyPage.valueOf(k.text)) { KeyPage.Letters -> "abc"; KeyPage.Symbols -> "123"; KeyPage.More -> "=\\<" },
                word, on, face,
            )
            KeyKind.Action -> label(
                when (state.field?.action) { ImeAction.Go -> "go"; ImeAction.Search -> "search"; ImeAction.Send -> "send"; ImeAction.Next -> "next"; ImeAction.Previous -> "back"; else -> "done" },
                word.copy(fontWeight = FontWeight.Medium), on, face,
            )
            KeyKind.Shift -> glyph(SHIFT, if (state.shift == Shift.Once) accent else on, face, 30 * u)
            KeyKind.Delete -> glyph(DELETE, on, face, 34 * u)
        }
    }

    override fun DrawScope.popup(cap: KeyCap) {
        val u = 1.px.toPx()
        val w = maxOf(cap.cell.width * 1.5f, 72 * u)
        val h = 96 * u
        val left = (cap.cell.center.x - w / 2).coerceIn(0f, size.width - w)
        val rect = Rect(left, cap.cell.top + 5 * u - h - 6 * u, left + w, cap.cell.top + 5 * u - 6 * u)
        drawRoundRect(accent, rect.topLeft, rect.size, CornerRadius(14 * u))
        label(cap.label, tab, Ink.Background, rect)
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
