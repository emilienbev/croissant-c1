package com.bleural.croissant.ask

import com.bleural.croissant.audio.Wav
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.Segment
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.offline.SpeechGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/**
 * Speech to text at a provider (the cloud half of the server's stt.py): a hold's words, or a
 * recording's pieces as segments. The audio goes as the server received it from the R1,
 * 16 kHz mono 16-bit PCM, in the WAV the server wrapped it in ([Speech.wav]). [language] is
 * the hint every pass gets unless one is given (the Translator's `from`); the server's was
 * English. No live words: each pass is a billed call, so the words come at release.
 */
abstract class CloudSpeech(val model: String, val language: String?) {
    /** Whether a recording's pieces come back with their own times; one that does not gets one segment a piece. */
    open val timestamps: Boolean get() = false

    /** How long a recording's pieces are: ten minutes for a provider that times its segments, thirty seconds for one that does not. */
    val pieceS: Int get() = if (timestamps) Speech.PIECE_S else Speech.UNTIMED_PIECE_S

    /** One pass over [pcm], with what the provider counted for it (`in`, `out` tokens) where it says, null where it does not. */
    abstract suspend fun counted(pcm: ByteArray, language: String? = null): Pair<String, Counts?>

    suspend fun transcribe(pcm: ByteArray, language: String? = null): String = counted(pcm, language).first

    /** A piece of a recording, [offsetS] seconds into the whole, as segments timed in the whole: here the piece as one. */
    open suspend fun segments(pcm: ByteArray, offsetS: Double): List<Segment> {
        val text = transcribe(pcm)
        val end = offsetS + pcm.size.toDouble() / Speech.BYTES_PER_SECOND
        return if (text.isNotEmpty()) listOf(Segment(Speech.round2(offsetS), Speech.round2(end), text)) else emptyList()
    }

    /** The hint for one pass: [given] unless it is none. */
    protected fun hint(given: String?): String? = given?.takeIf { it.isNotEmpty() } ?: language
}

/**
 * `/audio/transcriptions` at any OpenAI-compatible provider (OpenAI, Groq, one's own server),
 * as the server's SDK sent it: the WAV as `audio.wav`, the model, the language, `json`. A
 * recording's pieces ask `verbose_json` with segment times, which Whisper models give and
 * gpt-4o-transcribe refuses; a refusal turns them off for good, with a line in the log.
 */
class OpenAiSpeech(
    private val provider: Provider,
    model: String,
    private val key: String?,
    private val http: OkHttpClient,
    language: String? = Speech.LANGUAGE,
    private val log: (String) -> Unit = {},
) : CloudSpeech(model, language) {

    @Volatile final override var timestamps: Boolean = true
        private set

    fun form(pcm: ByteArray, language: String?, timed: Boolean): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "audio.wav", Speech.wavBody(pcm))
        .addFormDataPart("model", model)
        .apply { language?.let { addFormDataPart("language", it) } }
        .addFormDataPart("response_format", if (timed) "verbose_json" else "json")
        .apply { if (timed) addFormDataPart("timestamp_granularities[]", "segment") }
        .build()

    private suspend fun post(body: MultipartBody): JSONObject = retriedOnce {
        val req = Request.Builder().url("${provider.url}/audio/transcriptions").bearer(key).post(body).build()
        http.exchange(req, key) { r -> r.ok(key) { JSONObject(it.body.string()) } }
    }

    /** The provider's counts are not read (the server read none): the meter counts the seconds and estimates the words. */
    override suspend fun counted(pcm: ByteArray, language: String?): Pair<String, Counts?> =
        post(form(pcm, hint(language), timed = false)).str("text").trim() to null

    override suspend fun segments(pcm: ByteArray, offsetS: Double): List<Segment> {
        if (!timestamps) return super.segments(pcm, offsetS)
        val r = try {
            post(form(pcm, language, timed = true))
        } catch (e: AskError) {
            if (e.code != 400 && e.code != 422) throw e
            log("${provider.id}:$model gives no timestamps (${e.message.orEmpty().take(120)}); a recording is cut into " +
                "${Speech.UNTIMED_PIECE_S} s pieces from the next one on")
            timestamps = false
            return super.segments(pcm, offsetS)
        }
        val segs = r.optJSONArray("segments") ?: JSONArray()
        return (0 until segs.length()).mapNotNull { i ->
            val s = segs.optJSONObject(i) ?: return@mapNotNull null
            s.str("text").trim().takeIf { it.isNotEmpty() }?.let {
                Segment(Speech.round2(s.optDouble("start") + offsetS), Speech.round2(s.optDouble("end") + offsetS), it)
            }
        }
    }
}

/**
 * Gemini asked for a transcript (`models/{model}:generateContent`, the key in
 * `x-goog-api-key`): the WAV inline and one instruction, nothing else, as the server's SDK
 * sent it. The audio is counted by its seconds; the instruction and what came out, thinking
 * included, as Gemini counted them.
 */
class GeminiSpeech(
    private val provider: Provider,
    model: String,
    private val key: String?,
    private val http: OkHttpClient,
    language: String? = Speech.LANGUAGE,
) : CloudSpeech(model, language) {

    fun body(pcm: ByteArray, language: String?): JSONObject {
        val said = language?.let { ", in " + (Language.of(it)?.word ?: it) }.orEmpty()
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.getEncoder().encodeToString(Speech.wav(pcm)))))
            .put(JSONObject().put("text", PROMPT.replace("{lang}", said))))))
    }

    override suspend fun counted(pcm: ByteArray, language: String?): Pair<String, Counts?> {
        val key = key ?: throw AskError(0, "${provider.title} has no key")
        val req = Request.Builder().url("${provider.url}/${Lister.model(model)}:generateContent")
            .googKey(key).post(Http.json(body(pcm, hint(language)))).build()
        val r = http.exchange(req, key) { it.ok(key) { x -> JSONObject(x.body.string()) } }
        val counts = r.optJSONObject("usageMetadata")?.let { m ->
            val details = m.optJSONArray("promptTokensDetails") ?: JSONArray()
            val heard = (0 until details.length()).mapNotNull { details.optJSONObject(it) }
                .filter { it.str("modality") != "AUDIO" }.sumOf { it.optInt("tokenCount") }
            Counts(input = heard.toDouble(), out = (m.optInt("candidatesTokenCount") + m.optInt("thoughtsTokenCount")).toDouble())
        }
        return text(r).trim() to counts
    }

    companion object {
        const val PROMPT = "Transcribe the speech in this audio exactly as it was said{lang}. Reply with " +
            "the transcript only: no preamble, no notes, no timestamps, no description of " +
            "sounds. If nobody speaks, reply with nothing."

        /** The first candidate's text, its thoughts left out (the SDK's `text`). */
        fun text(r: JSONObject): String {
            val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
                .filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.str("text") }
        }
    }
}

/**
 * A speech model whose every pass goes in [ledger] under [entry] (`provider:model`), as the
 * server's MeteredStt counted it: the seconds heard, and what was written as the provider
 * counted it, else a token per four characters.
 */
class MeteredSpeech(val inner: CloudSpeech, private val entry: String, private val ledger: Ledger) {
    val pieceS: Int get() = inner.pieceS

    /**
     * A hold's words: "" when [SpeechGate] finds no speech in [pcm] (Whisper-like models
     * hallucinate on silence, and a gated hold costs no call), else one pass, counted.
     */
    suspend fun hear(pcm: ByteArray, language: String? = null): String =
        if (!SpeechGate.speech(pcm)) "" else transcribe(pcm, language)

    suspend fun transcribe(pcm: ByteArray, language: String? = null): String {
        val (text, counts) = inner.counted(pcm, language)
        count(pcm, text, counts)
        return text
    }

    suspend fun segments(pcm: ByteArray, offsetS: Double): List<Segment> =
        inner.segments(pcm, offsetS).also { found -> count(pcm, found.joinToString(" ") { it.text }, null) }

    /**
     * A recording on this model (the server's Transcription, for a provider), read from its
     * WAV [file] one piece at a time ([Speech.pieces], from [fromS]): each piece gated as a hold
     * is and a silent one sent nowhere, [each] getting a piece's offset, its segments (none
     * when gated) and how far through the whole it is, 0 to 1. At most one piece is in memory
     * (ten minutes, 19 MB, at a provider that times its segments), never the tape. The
     * segments of the whole come back.
     */
    suspend fun recording(file: File, fromS: Double = 0.0, each: suspend (Double, List<Segment>, Double) -> Unit = { _, _, _ -> }): List<Segment> {
        val seconds = Speech.seconds(file)
        val all = mutableListOf<Segment>()
        withContext(Dispatchers.IO) {
            Speech.pieces(file, fromS, pieceS) { offset, piece ->
                val found = if (SpeechGate.speech(piece)) segments(piece, offset) else emptyList()
                all += found
                each(offset, found, minOf(1.0, (offset + piece.size.toDouble() / Speech.BYTES_PER_SECOND) / seconds))
            }
        }
        return all
    }

    private suspend fun count(pcm: ByteArray, text: String, counts: Counts?) = withContext(Dispatchers.IO) {
        val c = counts ?: Counts(out = (text.codePointCount(0, text.length) / 4).toDouble())
        ledger.add(ModelSlot.Stt, entry, c.copy(secs = pcm.size.toDouble() / Speech.BYTES_PER_SECOND))
    }
}

object Speech {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val PIECE_S = 600
    const val UNTIMED_PIECE_S = 30
    /** A piece ends at the quietest 20 ms in its last five seconds, so a word is not cut in two. */
    const val SEARCH_S = 5
    private const val FRAME = SAMPLE_RATE / 50

    /** The server's `stt_language`. */
    const val LANGUAGE = "en"

    /** The client for [model] at [p], [key] its key; throws for a provider whose API has no speech. */
    fun of(p: Provider, model: String, key: String?, language: String? = LANGUAGE, http: OkHttpClient = Http.client,
           log: (String) -> Unit = {}): CloudSpeech = when (p.kind) {
        Kind.OpenAi -> OpenAiSpeech(p, model, key, http, language, log)
        Kind.Gemini -> GeminiSpeech(p, model, key, http, language)
        Kind.Ollama -> throw IllegalArgumentException("${p.id}:$model: an ${p.kind.wire} provider cannot transcribe")
    }

    /** [pcm] as a WAV file: the 44-byte header Python's `wave` writes for it, then the samples. */
    fun wav(pcm: ByteArray): ByteArray = Wav.header(pcm.size) + pcm

    /** [wav] as a request body, written from [pcm] as it is sent rather than copied first. */
    fun wavBody(pcm: ByteArray): RequestBody = object : RequestBody() {
        override fun contentType() = WAV
        override fun contentLength() = Wav.HEADER_BYTES + pcm.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            sink.write(Wav.header(pcm.size))
            sink.write(pcm)
        }
    }

    private val WAV = "audio/wav".toMediaType()

    /** How long the recording in [file] (a WAV of the capture's format) is, in seconds. */
    fun seconds(file: File): Double = (file.length() - Wav.HEADER_BYTES).coerceAtLeast(0) / 2 * 2 / BYTES_PER_SECOND.toDouble()

    /**
     * The recording in [file] from [fromS] cut into pieces of at most [pieceS] seconds, each
     * ending at the quietest 20 ms frame in the last [SEARCH_S] seconds of its window (the
     * server's `transcription.pieces`), [each] getting a piece's offset in seconds and its
     * PCM. Only that window and the piece are read, one piece at a time. Blocking: on IO.
     */
    suspend fun pieces(file: File, fromS: Double = 0.0, pieceS: Int = PIECE_S, each: suspend (Double, ByteArray) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            val n = (raf.length() - Wav.HEADER_BYTES).coerceAtLeast(0) / 2
            var start = (fromS * SAMPLE_RATE).toLong()
            while (start < n) {
                var end = minOf(n, start + pieceS.toLong() * SAMPLE_RATE)
                if (end < n) {
                    val lo = maxOf(start + SAMPLE_RATE, end - SEARCH_S * SAMPLE_RATE)
                    if (lo < end) quietest(read(raf, lo, end))?.let { end = lo + it.toLong() * FRAME }
                }
                if (end <= start) break
                each(start.toDouble() / SAMPLE_RATE, read(raf, start, end))
                start = end
            }
        }
    }

    /** Samples [from] to [to] of the recording, as bytes. */
    private fun read(raf: RandomAccessFile, from: Long, to: Long): ByteArray {
        raf.seek(Wav.HEADER_BYTES + from * 2)
        return ByteArray(((to - from) * 2).toInt()).also { raf.readFully(it) }
    }

    /** The whole 20 ms frame of [window] with the least power (the first of equals), null when there is none. */
    private fun quietest(window: ByteArray): Int? {
        val frames = window.size / 2 / FRAME
        if (frames == 0) return null
        var best = 0
        var least = Double.MAX_VALUE
        for (f in 0 until frames) {
            var sum = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) {
                val x = ((window[2 * i].toInt() and 0xff) or (window[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                sum += x * x
            }
            if (sum / FRAME < least) {
                least = sum / FRAME
                best = f
            }
        }
        return best
    }

    /** Python's `round(v, 2)`: the exact value to two places, half to even. */
    fun round2(v: Double): Double = if (!v.isFinite()) v else BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toDouble()
}
