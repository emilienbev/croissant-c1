package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Ask's answer on one chat model, with the tool loop (server/tools/c1_loop.py is its Python copy), and the single
 * calls around it: a Transform's description, a tape's or an app's question, a translation.
 * What it hands on is already scrubbed of markdown, piece by piece, as the server's session
 * scrubbed what it sent, and a piece that scrubs to nothing is not handed on.
 */
class Loop(private val chat: ChatApi, private val log: (String) -> Unit = {}) {

    /**
     * Answer [question], giving [text] the answer as it is produced; the whole answer comes
     * back at the end.
     *
     * [prior] is the conversation so far, question and answer, oldest first: text only (c1_loop.py's
     * `as_messages`). [tools] is what the hold offers, exactly and in order,
     * none when empty. [execute] runs one call and returns a JSON-able result (a string goes
     * to the model as it is); an exception from it becomes `{"error": ...}`, since a tool must
     * never take the answer down. A photo hold ([image]) comes with no tools.
     *
     * The first round streams live: a plain question is answered there and the model, told
     * to call first and confirm after, rarely chatters before a call. [stats], when given,
     * gets one [Usage] per round, from the counts the client reports.
     *
     * Later rounds only happen after tools ran; their text is held back and sent only if the
     * round ends without another call, because in a round that does call again the text is
     * the model thinking aloud ("let me check the calendar"), which the device must not show.
     * After [MAX_TOOL_ROUNDS] the model is offered no tools, so it has to answer in words.
     */
    suspend fun answer(
        question: String,
        system: String,
        prior: List<Pair<String, String>>,
        tools: List<ToolSpec>,
        execute: suspend (Call) -> Any?,
        image: ByteArray? = null,
        stats: MutableList<Usage>? = null,
        text: (String) -> Unit = {},
    ): String {
        val answer = StringBuilder()
        val out: (String) -> Unit = { piece -> scrub(piece).takeIf { it.isNotEmpty() }?.let { answer.append(it); text(it) } }
        val messages = mutableListOf(Msg("system", system))
        for ((q, a) in prior) messages += listOf(Msg("user", q), Msg("assistant", a))
        messages += Msg("user", question, images = listOfNotNull(image))
        val known = tools.map { it.name }.toSet()
        var sentAny = false

        for (round in 0..MAX_TOOL_ROUNDS) {
            // Last round: no tools offered, so the model has to answer in words.
            val offered = tools.takeIf { it.isNotEmpty() && round < MAX_TOOL_ROUNDS }
            val live = round == 0
            val content = StringBuilder()
            val reply = chat.stream(messages, offered) { piece ->
                if (live) out(piece)
                content.append(piece)
            }
            if (reply.usage != null) stats?.add(reply.usage)
            if (live && content.isNotEmpty()) sentAny = true
            if (reply.calls.isEmpty()) {
                if (!live && content.isNotEmpty()) out((if (sentAny) " " else "") + content)
                return answer.toString()
            }

            messages += Msg("assistant", content.toString(), calls = reply.calls, raw = reply.raw)
            for (c in reply.calls) {
                val result: Any? = if (c.name !in known) {
                    log("model called unknown tool ${c.name}")
                    JSONObject().put("error", "no such tool ${c.name}")
                } else {
                    log("tool ${c.name}(${pyJson(c.args)})")
                    try {
                        execute(c)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("tool ${c.name} failed: ${e.message}")
                        JSONObject().put("error", e.message ?: e.javaClass.simpleName)
                    }
                }
                // A result that is already text goes in as it is. The search digest is written
                // to be read, and quoting it would turn every newline into \n, which spends
                // tokens on punctuation a small model then has to look past.
                messages += Msg("tool", result as? String ?: pyJson(result), call = c, value = result)
            }
        }
        return answer.toString()
    }

    /**
     * One vision pass, no tools: the photo plus what was said, back as one line of text, the
     * Transform button's whole assistant. The model is not choosing anything, so there is no
     * loop to run and nothing to stream; temperature 0, as tool arguments are, because this is
     * one too.
     */
    suspend fun describe(instruction: String, image: ByteArray, system: String): String =
        scrub(chat.complete(listOf(Msg("system", system), Msg("user", instruction, images = listOf(image)))).first.trim()).trim().trim('"')

    /** One call, no tools, no history: a tape's question, an app's `ask()`. */
    suspend fun complete(user: String, system: String): Pair<String, Usage?> {
        val (text, usage) = chat.complete(listOf(Msg("system", system), Msg("user", user)))
        return scrub(text.trim()) to usage
    }

    /**
     * The Translator's pass: one streamed call, no tools, no history, [text] getting the
     * translation as it comes. Temperature 0, so the same sentence comes back the same way.
     */
    suspend fun translate(said: String, system: String, text: (String) -> Unit): Usage? =
        chat.stream(listOf(Msg("system", system), Msg("user", said)), null) { piece ->
            scrub(piece).takeIf { it.isNotEmpty() }?.let(text)
        }.usage

    companion object {
        /**
         * Rounds of "model asks for tools, tools answer" before the answer is forced. Two is the
         * normal case (call, then confirm); more is the model chaining a list with an update.
         * Beyond this it is looping.
         */
        const val MAX_TOOL_ROUNDS = 4

        // Markdown the model may still emit, removed per piece. Asking for "plain text" in the
        // prompt costs tool calls (data/AskPrompt.kt), so this is done here instead. Bold and
        // italic markers and inline code can go anywhere; heading marks and list bullets only
        // open a line, which a streamed piece may or may not start, hence the lookbehind on a
        // newline or the start of the piece.
        private val INLINE = Regex("\\*{1,3}|`+|_{2}")
        private val LINE_START = Regex("(?:(?<=\\n)|^)[ \\t]*(?:#{1,6}[ \\t]+|[-*]\\s+|\\d+\\.\\s+)")

        fun scrub(text: String): String = LINE_START.replace(INLINE.replace(text, ""), "")

        /** A hold's totals: prompt tokens, prefill tok/s, output tokens, decode tok/s. */
        fun rates(rounds: List<Usage>): Rates {
            val ps = rounds.sumOf { it.promptS }
            val os = rounds.sumOf { it.outS }
            val pt = rounds.sumOf { it.promptTokens }
            val ot = rounds.sumOf { it.outTokens }
            return Rates(pt, if (ps > 0) pt / ps else 0.0, ot, if (os > 0) ot / os else 0.0)
        }
    }

    /** What [rates] adds up: the effective prefill rate, Ollama counting a cached prefix's tokens but not its time. */
    data class Rates(val promptTokens: Int, val prefill: Double, val outTokens: Int, val decode: Double)
}
