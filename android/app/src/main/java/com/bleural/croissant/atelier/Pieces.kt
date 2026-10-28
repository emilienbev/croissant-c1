package com.bleural.croissant.atelier

import org.json.JSONObject

/**
 * One piece of an app's page, as its `render` returned it (`atelier/prelude.js`). Pieces
 * say what, never how: no size, colour or place. The design draws each its own way.
 *
 * A handler is its index in the render's table ([AppPage.gen]); the device calls it back
 * with [AppRuntime.call]. Null: the piece does nothing when touched.
 */
sealed interface Piece {
    /** Body text. */
    data class Words(val text: String) : Piece
    /** A big value, with its name under it. */
    data class Big(val value: String, val label: String?) : Piece
    /** A row: its text, a value at its end, and what a tap does. */
    data class Row(val text: String, val value: String?, val tap: Int?) : Piece
    /** A switch, called with the new state when turned. */
    data class Toggle(val text: String, val on: Boolean, val change: Int?) : Piece
    /** A text field, typed on the keys; [change] has each new text, [done] the text when the keys go. */
    data class Field(val value: String, val hint: String?, val keys: Keys, val change: Int?, val done: Int?) : Piece
    /** A key. */
    data class Pill(val text: String, val tap: Int?) : Piece
}

enum class Keys { Text, Number }

/**
 * A page as one render drew it: [gen] numbers the render, so a tap on a page that has
 * since changed under the finger is dropped rather than calling the wrong handler.
 * [takesWords]: the app has an `onWords`, so a hold on its page brings it the words.
 */
data class AppPage(val gen: Int, val pieces: List<Piece>, val takesWords: Boolean) {
    companion object {
        /** The prelude's `__draw()` output. A piece of a kind this kit does not know is an error. */
        fun parse(json: String): AppPage {
            val o = JSONObject(json)
            val a = o.getJSONArray("pieces")
            val pieces = (0 until a.length()).map { i -> piece(a.getJSONObject(i)) }
            return AppPage(o.getInt("gen"), pieces, o.optBoolean("words"))
        }

        private fun piece(p: JSONObject): Piece = when (val t = p.optString("t")) {
            "words" -> Piece.Words(p.text("text"))
            "big" -> Piece.Big(p.text("value"), p.textOrNull("label"))
            "row" -> Piece.Row(p.text("text"), p.textOrNull("value"), p.handler("tap"))
            "toggle" -> Piece.Toggle(p.text("text"), p.optBoolean("on"), p.handler("change"))
            "field" -> Piece.Field(
                p.text("value"), p.textOrNull("hint"),
                if (p.optString("keys") == "number") Keys.Number else Keys.Text,
                p.handler("change"), p.handler("done"),
            )
            "pill" -> Piece.Pill(p.text("text"), p.handler("tap"))
            else -> throw BadApp("render returned a piece Croissant does not know: \"$t\"")
        }

        private fun JSONObject.text(k: String) = if (isNull(k)) "" else optString(k)
        private fun JSONObject.textOrNull(k: String) = if (!has(k) || isNull(k)) null else optString(k)
        private fun JSONObject.handler(k: String) = if (!has(k) || isNull(k)) null else optInt(k)
    }
}
