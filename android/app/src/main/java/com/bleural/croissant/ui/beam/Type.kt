package com.bleural.croissant.ui.beam

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bleural.croissant.ui.Grotesk

/** Page pixels to sp, so a style reads like the page's CSS (1 sp is 1.75 px on the R1). */
internal val Float.pxSp: TextUnit get() = (this / 1.75f).sp

/** Page pixels as a [Dp], so a layout reads like the page. */
internal val Float.px: Dp get() = (this / 1.75f).dp
internal val Int.px: Dp get() = (this / 1.75f).dp

private fun grotesk(px: Float, weight: FontWeight, tracking: Float = 0f, line: Float? = null) = Grotesk.copy(
    fontWeight = weight, fontSize = px.pxSp, letterSpacing = tracking.pxSp,
    lineHeight = line?.pxSp ?: TextUnit.Unspecified,
)

/**
 * Beam's type scale, in the page's pixels. Power Grotesk only: Regular for words, Medium for the
 * header and what is focused, Light for big numbers. Nothing under 26 px. The app's own words are
 * lowercase; the styles do not do it, the kit and the pages do.
 */
object BeamType {
    /** The header word, lowercase. */
    val Header: TextStyle = grotesk(34f, FontWeight.Medium, -0.3f)
    /** The one number a screen may have. */
    val Big: TextStyle = grotesk(132f, FontWeight.Light, -5f)
    /** An item's title, 54 a line when it wraps. */
    val Second: TextStyle = grotesk(48f, FontWeight.Normal, -1f, line = 54f)
    /** A list row's title, and a time column's time; a wrapped row runs 46 a line. */
    val Row: TextStyle = grotesk(38f, FontWeight.Normal, -0.6f, line = 46f)
    /** The focused row. */
    val RowFocused: TextStyle = grotesk(38f, FontWeight.Medium, -0.6f, line = 46f)
    /** A row's value or right label. */
    val Value: TextStyle = grotesk(28f, FontWeight.Normal, -0.2f)
    /** Words that are not a row: a paragraph, a question, an answer; 42 a line. */
    val Body: TextStyle = grotesk(32f, FontWeight.Normal, -0.4f, line = 42f)
    /** The same, Medium: the thing in hand. */
    val BodyMedium: TextStyle = grotesk(32f, FontWeight.Medium, -0.4f, line = 42f)
    /** Group names, counts, a unit, a hint. */
    val Label: TextStyle = grotesk(26f, FontWeight.Normal, 0.2f)
    /** A date at the end of a row, a print's time: the label's size. */
    val Stamp: TextStyle = grotesk(26f, FontWeight.Normal, 0.2f)
    /** Home's clock (2026-10-27): bigger than [Big]'s 132. */
    val Clock: TextStyle = grotesk(160f, FontWeight.Light, -6f)
    /** The side wheel's feature name at its largest; sized down to fit, tracking -3%. */
    val Name: TextStyle = grotesk(88f, FontWeight.Normal, -2.64f)
    /** A timer's time in a list. */
    val Mid: TextStyle = grotesk(64f, FontWeight.Light, -2f)
    /** New timer's columns. */
    val Huge: TextStyle = grotesk(96f, FontWeight.Light, -3f)
    /** A version at [Huge]'s size, tracked +4: the font sets 1 and . so close that 0.1.0 at -3 reads 0.10. */
    val Version: TextStyle = grotesk(96f, FontWeight.Light, 4f)
    /** A selector's words. */
    val Select: TextStyle = grotesk(34f, FontWeight.Normal, -0.4f)
    /** A field's text. */
    val FieldText: TextStyle = grotesk(36f, FontWeight.Normal, -0.5f)
}
