package com.bleural.croissant.net

import com.bleural.croissant.data.Segment
import org.json.JSONObject

/**
 * Wire protocol.
 *
 * WebSocket already distinguishes text frames from binary ones, so there is no framing
 * format to invent: control is JSON text, audio is raw little-endian PCM in binary frames.
 */
object Protocol {

    /**
     * A hold: its words and nothing else (`"mode":"dictate"`). The server turns the speech
     * into words, `partial` while it is held, `final` at the end, then an empty `answer_done`;
     * every model call is the C1's from `final` on. No photo goes: a photo hold's picture is
     * sent to the chat model from the C1.
     *
     * [from] is the language Whisper is told, a code (the Translator's `from`); without it
     * the server's own. [stt] is the device's pick of speech model (`provider:model`,
     * net/Models.kt), else the server's default. [partials] turns the words while the button
     * is held on or off; null leaves it to the speech model (on locally, off at a provider).
     * [conv] only names the hold in the server's `--debug-keep` history.
     */
    fun start(rate: Int, channels: Int, from: String? = null, stt: String? = null, partials: Boolean? = null,
              conv: String? = null): String = JSONObject()
        .put("type", "start")
        .put("mode", "dictate")
        .put("rate", rate)
        .put("enc", "pcm_s16le")
        .put("ch", channels)
        .apply { if (from != null) put("from", from) }
        .apply { if (stt != null) put("stt", stt) }
        .apply { if (partials != null) put("partials", partials) }
        .apply { if (conv != null) put("conv", conv) }
        .toString()

    fun end(): String = JSONObject().put("type", "end").toString()

    /**
     * Ask for one picture and nothing else: no audio, no assistant, no answer. Every
     * picture goes this way, its prompt final: Ask's `image_generate`, the Transform button
     * (after the C1's description pass, when the picture model wants one) and the shelf's
     * Try again, which must not have its prompt reworded on the way. The server answers
     * with a `generation` frame.
     *
     * [image] true means one binary JPEG frame follows: the photo a transform starts from,
     * sent again on a retry, because the server keeps only the prompt. [id] is made here
     * and becomes the job's id, so the picture can be fetched whether or not the answering
     * frame arrives.
     */
    fun generate(id: String, prompt: String, image: Boolean = false, models: Map<String, String> = emptyMap()): String = JSONObject()
        .put("type", "generate")
        .put("id", id)
        .put("prompt", prompt)
        .apply { if (image) put("image", true) }
        .apply { models.forEach { (slot, id) -> put(slot, id) } }
        .toString()

    /**
     * A recording sent for transcription: the same audio
     * format, [id] the file's name, [startedAt] the device's stamp, [bytes] the PCM
     * that follows, [from] seconds already transcribed that the server may skip, [stt]
     * the speech-to-text entry picked (net/Models.kt), or the server's default.
     */
    fun startTranscribe(rate: Int, channels: Int, id: String, startedAt: String, bytes: Long, from: Double,
                        stt: String? = null): String = JSONObject()
        .put("type", "start")
        .put("mode", "transcribe")
        .put("rate", rate)
        .put("enc", "pcm_s16le")
        .put("ch", channels)
        .put("id", id)
        .put("startedAt", startedAt)
        .put("bytes", bytes)
        .apply { if (from > 0) put("from", from) }
        .apply { if (stt != null) put("stt", stt) }
        .toString()

    sealed interface Incoming {
        /** Live, monotonic transcript while speaking. */
        data class Partial(val text: String) : Incoming
        /** Transcript is settled: the C1 asks the model from here. */
        data class Final(val text: String) : Incoming
        /** The hold's words are all there is: the exchange is closed. */
        data object AnswerDone : Incoming
        data class Failure(val message: String) : Incoming
        /** A transcription's position, 0 to 1: the upload by bytes, then the audio transcribed. */
        data class Progress(val done: Float) : Incoming
        /** One piece of a recording's transcript, offsets in seconds from its start. */
        data class Segments(val items: List<Segment>) : Incoming
        /** The transcription is complete; [id] is what the server filed it under. */
        data class Done(val id: String) : Incoming
        /**
         * The server took a `generate` frame. A picture takes far longer than the
         * connection, so all that arrives here is the id to fetch it by later
         * (`net/Generations.kt`) and the prompt to show meanwhile.
         */
        data class Generation(val id: String, val prompt: String) : Incoming
        data class Unknown(val raw: String) : Incoming
    }

    fun parse(text: String): Incoming = try {
        val o = JSONObject(text)
        when (o.optString("type")) {
            "partial" -> Incoming.Partial(o.optString("text"))
            "final" -> Incoming.Final(o.optString("text"))
            "answer_done" -> Incoming.AnswerDone
            "error" -> Incoming.Failure(o.optString("message", "unknown server error"))
            "progress" -> Incoming.Progress(o.optDouble("done", 0.0).toFloat())
            "segments" -> Incoming.Segments(o.getJSONArray("items").let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { Segment(it.getDouble("start"), it.getDouble("end"), it.getString("text")) } }
            })
            "done" -> Incoming.Done(o.optString("id"))
            "generation" -> Incoming.Generation(o.getString("id"), o.optString("prompt"))
            else -> Incoming.Unknown(text)
        }
    } catch (e: Exception) {
        Incoming.Unknown(text)
    }
}
