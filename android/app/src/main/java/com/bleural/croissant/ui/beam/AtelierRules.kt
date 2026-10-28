package com.bleural.croissant.ui.beam

import com.bleural.croissant.atelier.AppPage
import com.bleural.croissant.atelier.AppRuntime
import com.bleural.croissant.atelier.Piece
import com.bleural.croissant.atelier.Use
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * Atelier's words and measures as Beam draws them (Atelier.kt), kept free of Compose so they
 * are tested on their own: an app's hue lifted to carry black words, what an app may use in
 * plain words, a tool's arguments, where a script failed, and the space between an app's pieces.
 */

/**
 * The least relative luminance (WCAG) a hue carries black words at, 4.5 to 1 against black;
 * what a lifted hue is brought to, a little over it. Every neon of Beam's clears it, the
 * recorder's red (0.23) the closest.
 */
private const val LEAST = 0.175
private const val LIFTED = 0.18

private fun linear(c: Double): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }

/** [r], [g], [b] (0 to 255) as WCAG's relative luminance, 0 black to 1 white. */
internal fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

internal fun luminance(rgb: Int): Double = luminance(((rgb shr 16) and 0xFF).toDouble(), ((rgb shr 8) and 0xFF).toDouble(), (rgb and 0xFF).toDouble())

/**
 * An app's hue (0xRRGGBB, as croissant.json gives it) as Beam draws it: as it is when black words
 * read on it ([LEAST]); else brought to full brightness keeping its hue, and if that is not
 * enough, mixed with white just as far as it needs. Beam's neons stay as they are; an app's
 * own dark pick is lifted, a navy to a periwinkle. Light enough for black on it, it is light
 * enough on the black ground too.
 */
internal fun liftHue(rgb: Int): Int {
    if (luminance(rgb) >= LEAST) return rgb and 0xFFFFFF
    val r0 = ((rgb shr 16) and 0xFF).toDouble()
    val g0 = ((rgb shr 8) and 0xFF).toDouble()
    val b0 = (rgb and 0xFF).toDouble()
    val top = maxOf(r0, g0, b0)
    val k = if (top > 0.0) 255.0 / top else 1.0
    val r = r0 * k
    val g = g0 * k
    val b = b0 * k
    fun mixed(t: Double) = Triple(r + (255.0 - r) * t, g + (255.0 - g) * t, b + (255.0 - b) * t)
    var t = 0.0
    if (luminance(r, g, b) < LIFTED) {
        // The least white that brings it there: luminance only grows with it.
        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2
            val (mr, mg, mb) = mixed(mid)
            if (luminance(mr, mg, mb) >= LIFTED) hi = mid else lo = mid
        }
        t = hi
    }
    val (mr, mg, mb) = mixed(t)
    fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
    return (c(mr) shl 16) or (c(mg) shl 8) or c(mb)
}

/** What [use] reaches, in the words the install sheet and the info page say it. */
internal fun useWords(use: Use): String = when (use) {
    Use.Network -> "the network"
    Use.Model -> "the model"
    Use.Words -> "your words"
}

/** The install page's line for an app that asks for nothing, in place of what it may use. */
internal const val USES_NOTHING = "it uses nothing but its page"

/**
 * A tool's arguments as the install page shows them, from its parameters (a JSON Schema object,
 * as croissant.json has it): a line each, in its order, its name and its description as written
 * ("name: the item, a word or two"), the name alone when it has none.
 */
internal fun argLines(parameters: String): List<String> {
    val props = runCatching { JSONObject(parameters).optJSONObject("properties") }.getOrNull() ?: return emptyList()
    return props.keys().asSequence().map { k ->
        val about = props.optJSONObject(k)?.optString("description").orEmpty().trim()
        if (about.isEmpty()) k else "$k: $about"
    }.toList()
}

/** A tool as the info page names it: "add_item", "add item". */
internal fun toolWords(name: String): String = name.replace('_', ' ')

/** Where a script failed, under its message: "render, croissant.js line 42", or "start". */
internal fun failedWhere(f: AppRuntime.Failure): String {
    val during = when (val d = f.during) {
        AppRuntime.During.Start -> "start"
        AppRuntime.During.Render -> "render"
        AppRuntime.During.Words -> "your words"
        is AppRuntime.During.Handler -> d.label.ifBlank { "a tap" }
    }
    return if (f.line != null) "$during, croissant.js line ${f.line}" else during
}

/** The line at the foot when a touch or a hold ran into an error and the page stayed. */
internal fun failedFoot(f: AppRuntime.Failure): String = when (val d = f.during) {
    is AppRuntime.During.Handler -> "${d.label.ifBlank { "it" }} ran into an error"
    AppRuntime.During.Words -> "your words ran into an error"
    else -> "it ran into an error"
}

/** A commit as the info page shows it: its first seven. */
internal fun shortSha(commit: String): String = commit.take(7)

/** What a touch on a piece asks of the app: a row's or a pill's tap, a toggle turned, a field's text as typed, a field let go. */
internal enum class Act(val dims: Boolean) { Tap(true), Flip(true), Type(false), Done(false) }

/**
 * A touch on piece [at] of the page, waiting its turn: the app runs one handler at a time, and a
 * call names the render it was made on, so a touch made while a handler runs is held and sent
 * on the page that handler leaves, to the piece in the same place ([resolve]). [text] is a
 * field's.
 */
internal class Touch(val at: Int, val act: Act, val text: String? = null)

/** Whether the wheel can be on [p]: what a tap acts on (a field is tapped to type, the wheel moving its caret). */
internal fun touchable(p: Piece): Boolean = when (p) {
    is Piece.Row -> p.tap != null
    is Piece.Toggle -> p.change != null
    is Piece.Pill -> p.tap != null
    else -> false
}

/**
 * [t] on [page]: the handler and the value to call it with (a toggle's new state as the page
 * now has it, a field's text), or null when the piece in its place no longer takes it.
 */
internal fun resolve(page: AppPage, t: Touch): Pair<Int, Any?>? = when (val p = page.pieces.getOrNull(t.at)) {
    is Piece.Row -> p.tap?.takeIf { t.act == Act.Tap }?.let { it to null }
    is Piece.Pill -> p.tap?.takeIf { t.act == Act.Tap }?.let { it to null }
    is Piece.Toggle -> p.change?.takeIf { t.act == Act.Flip }?.let { it to !p.on }
    is Piece.Field -> when (t.act) {
        Act.Type -> p.change?.let { it to t.text.orEmpty() }
        Act.Done -> p.done?.let { it to t.text.orEmpty() }
        else -> null
    }
    else -> null
}

/** The kinds of piece the page lays out, a run of pills being one ([Piece.Pill]s flow side by side). */
internal enum class Lay { Words, Big, Row, Field, Pills }

internal fun layOf(p: Piece): Lay = when (p) {
    is Piece.Words -> Lay.Words
    is Piece.Big -> Lay.Big
    is Piece.Row, is Piece.Toggle -> Lay.Row
    is Piece.Field -> Lay.Field
    is Piece.Pill -> Lay.Pills
}

/**
 * The space over a piece, from the bottom of the one above it ([prev], null for the first, from the
 * content's top at 66), in page px. Each piece's box: words from 32 over their first baseline to 10
 * under their last ([TextShelf]); a big value from 110 over its number's baseline to 8 under its
 * label's; a row 64; a field 60; a pill 46 (its ring drawn outside). On a page these put the rows
 * from 74, the field from 88, the number on 214 (292 under a field), the words on 112; a pill 28
 * under rows or a big value, rows 36 under a pill and 18 under a big value or a field.
 */
internal fun pieceGap(prev: Lay?, next: Lay): Float = when (next) {
    Lay.Words -> if (prev == Lay.Words) 18f else 14f
    Lay.Field -> 22f
    Lay.Big -> if (prev == Lay.Field) 34f else 38f
    Lay.Row -> when (prev) {
        Lay.Row -> 0f
        Lay.Big, Lay.Field -> 18f
        Lay.Pills -> 36f
        null, Lay.Words -> 8f
    }
    Lay.Pills -> if (prev == null) 22f else 28f
}
