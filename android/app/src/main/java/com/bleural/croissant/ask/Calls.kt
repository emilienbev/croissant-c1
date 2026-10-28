package com.bleural.croissant.ask

import com.bleural.croissant.data.CallPrompts
import com.bleural.croissant.data.Language
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The model calls besides a hold's answer, each one call with no tools and no history under
 * its fixed prompt from the catalog ([prompts]), on the hold's own chat model ([chat], a
 * [Metered] one, so each is counted as the server counted them; [entry] its
 * `provider:model`): the Transform button's description pass, the Translator, a question
 * about a tape, an Atelier app's `ask()` (until 2026-10-16 the server's llm.py, tapes.py and session.py).
 * [log] gets what the server logged, never a key and never what was said.
 */
class Calls(private val chat: ChatApi, private val entry: String, private val prompts: CallPrompts, private val log: (String) -> Unit = {}) {

    private val loop = Loop(chat, log)

    /**
     * The Transform button's one vision pass: what was [said] about [photo], back as one
     * description of the finished picture, the prompt the picture is made from. Empty when
     * the model gave none, which the hold says as "Could not describe the picture".
     */
    suspend fun describe(said: String, photo: ByteArray): String = loop.describe(said, photo, prompts.transform)

    /** The Translator's pass: [said] from [from] into [to], [text] getting the translation as it comes, scrubbed. */
    suspend fun translate(said: String, from: Language, to: Language, text: (String) -> Unit): Usage? =
        loop.translate(said, prompts.translateRules(from, to), text)

    /**
     * `tape_ask` as the server ran it: the tape the model named is fetched by [tapeRead] (the
     * device's `tape_read`, which finds it by number or by words of its title and gives its
     * transcript), then asked [readTape] in a fresh call, and only the answer goes back, after
     * the tape's title. A missing argument, or why `tape_read` refused (no such tape, not
     * transcribed yet, the tapes there are), goes back as it is, for the model to relay or to
     * try again with a tape from the list.
     */
    suspend fun tapeAsk(args: JSONObject, tapeRead: suspend (JSONObject) -> Any?): Any? {
        val tape = args.opt("tape").takeIf(::pyTruthy)?.toString()?.trim().orEmpty()
        val question = args.opt("question").takeIf(::pyTruthy)?.toString()?.trim().orEmpty()
        if (tape.isEmpty() || question.isEmpty()) return JSONObject().put("error", "tape and question are required")
        val found = try {
            tapeRead(JSONObject().put("tape", tape))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        if (found !is JSONObject || found.has("error")) return found
        val title = found.opt("title").takeIf(::pyTruthy)?.toString() ?: "Tape ${py(found.opt("number"))}"
        return "$title: " + readTape(found, question)
    }

    /**
     * A question about [tape] (`tape_read`'s answer) in a call of its own: no persona, no
     * tools, no history, the transcript and the question alone, under the reading rules. A
     * fresh call rather than the transcript in the hold, where an hour of talk (about 14K
     * tokens) would sit beside the tools for every later round.
     */
    suspend fun readTape(tape: JSONObject, question: String): String {
        val doc = document(tape)
        val (answer, usage) = loop.complete(doc + "\n\nQuestion: " + question, prompts.read)
        if (usage != null) log("tape ${py(tape.opt("number"))} on $entry: ${doc.length} chars, prompt ${usage.promptTokens} tokens in " +
            "%.1f s, answer ${usage.outTokens} tokens in %.1f s".format(Locale.ROOT, usage.promptS, usage.outS))
        return answer
    }

    /**
     * An Atelier app's `ask(text)`: one plain answer under the app rules. Throws
     * [IllegalArgumentException] for no text or one over [ASK_MAX_CHARS], and an
     * [IOException] saying "LLM error: " and why when the provider fails: its refusal in its own
     * words (its key redacted), else that the model did not answer, never where it was asked.
     */
    suspend fun appAsk(text: String, app: String? = null): String {
        if (text.isBlank()) throw IllegalArgumentException("text is required")
        if (text.codePointCount(0, text.length) > ASK_MAX_CHARS) throw IllegalArgumentException("the question is over $ASK_MAX_CHARS characters")
        log("ask from app ${app ?: "?"} on $entry: ${text.length} chars")
        return try {
            loop.complete(text, prompts.app).first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A connection's failure names the address it tried: not for an app's eyes.
            val why = if (e is IOException && e !is AskError) "the model did not answer" else e.message
            throw IOException("LLM error: $why", e)
        }
    }

    companion object {
        /** The longest question an app may ask in one `ask()`, in characters (code points, as Python counts). */
        const val ASK_MAX_CHARS = 16_000

        /**
         * Whisper's segments are two to five seconds long, and a stamp on each would cost an
         * hour's talk about 6K tokens of numbers; merged into blocks of about thirty seconds,
         * each opened with its time, the stamps cost a few hundred and an answer can still say
         * where in the tape it was.
         */
        const val BLOCK_S = 30.0

        private val RECORDED = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy 'at' HH:mm", Locale.ENGLISH)

        /** 0:42, 12:05, 1:02:09. */
        fun stamp(seconds: Double): String {
            val s = seconds.toLong()
            val h = s / 3600
            val m = s % 3600 / 60
            val two = { n: Long -> n.toString().padStart(2, '0') }
            return if (h != 0L) "$h:${two(m)}:${two(s % 60)}" else "$m:${two(s % 60)}"
        }

        /** The segments merged into lines of about [every] seconds, each opened with its time. */
        fun blocks(segments: JSONArray?, every: Double = BLOCK_S): List<String> {
            val lines = mutableListOf<String>()
            var start: Double? = null
            val words = mutableListOf<String>()
            for (i in 0 until (segments?.length() ?: 0)) {
                val seg = segments!!.optJSONObject(i) ?: continue
                val text = (if (seg.has("text")) py(seg.opt("text")) else "").trim()
                if (text.isEmpty()) continue
                val at = seg.optDouble("start", 0.0)
                if (start != null && at - start >= every) {
                    lines += "[${stamp(start)}] " + words.joinToString(" ")
                    start = null
                    words.clear()
                }
                if (start == null) start = at
                words += text
            }
            if (words.isNotEmpty()) lines += "[${stamp(start ?: 0.0)}] " + words.joinToString(" ")
            return lines
        }

        /** What the reader is told about the tape before its words: which, when, how long. */
        fun heading(tape: JSONObject): String {
            val n = py(tape.opt("number"))
            val title = tape.opt("title")
            val parts = mutableListOf("Tape $n" + if (pyTruthy(title) && title.toString() != "Tape $n") ", \"$title\"" else "")
            started(py(tape.opt("startedAt")))?.let { parts += "recorded " + it.format(RECORDED) }
            val minutes = Math.rint(tape.optLong("durationMs", 0) / 60000.0).toLong()
            if (minutes != 0L) parts += "$minutes minutes long"
            return "Recording: " + parts.joinToString(", ") + "."
        }

        /**
         * The user message's fixed part: heading and transcript. The question goes after it,
         * so a second question about the same tape shares the whole prefix with the first.
         */
        fun document(tape: JSONObject): String = heading(tape) + "\n\nTranscript:\n" + blocks(tape.optJSONArray("segments")).joinToString("\n")

        /** When a tape began, as `datetime.fromisoformat` reads it: with its offset, without, or a day; null when it is none of those. */
        private fun started(s: String): LocalDateTime? =
            runCatching { OffsetDateTime.parse(s).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s).atStartOfDay() }.getOrNull()

        /** Python's `str()` of a JSON value read here: None for null. */
        private fun py(v: Any?): String = if (v == null || v == JSONObject.NULL) "None" else v.toString()
    }
}
