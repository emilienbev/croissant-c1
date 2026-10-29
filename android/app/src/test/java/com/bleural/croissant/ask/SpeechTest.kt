package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.audio.Wav
import com.bleural.croissant.data.Segment
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

/** Cloud speech against answers in each API's shape (test/resources/ask/): what goes out, what comes back, what is counted. */
class SpeechTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "sk-proj-fake-0000000000000000000000000000"
    private val geminiKey = "AIza00000000000000000000000000000000MNOP"

    private fun provider(id: String) = Providers.resolve(id, ProviderSpec(), "100.100.100.7")

    /** [seconds] of PCM: a 400 Hz tone where [loud] says, zeros elsewhere. */
    private fun pcm(seconds: Int, loud: (Double) -> Boolean = { true }): ByteArray {
        val n = seconds * Speech.SAMPLE_RATE
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = if (loud(i.toDouble() / Speech.SAMPLE_RATE)) (8000 * sin(2 * PI * 400 * i / Speech.SAMPLE_RATE)).toInt() else 0
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** A second of speech as the gate sees it: loud, then the room. */
    private val voice = pcm(1) { it < 0.8 }

    /** Each part of a multipart request by name: its file name, its type, its bytes. */
    private fun parts(r: Request): Map<String, Triple<String?, String?, ByteArray>> = (r.body as MultipartBody).parts.associate { p ->
        val disposition = p.headers!!["Content-Disposition"]!!
        val name = Regex("name=\"([^\"]+)\"").find(disposition)!!.groupValues[1]
        val file = Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
        name to Triple(file, p.body.contentType()?.toString(), Buffer().also { p.body.writeTo(it) }.readByteArray())
    }

    private fun Map<String, Triple<String?, String?, ByteArray>>.text(name: String) = get(name)?.third?.let { String(it) }

    @Test fun theWavIsTheServers() {
        // What Python's wave module writes for the same two samples (stt.wav on the server).
        val server = "524946462800000057415645666d74201000000001000100803e0000007d00000200100064617461040000000100ff7f"
        assertEquals(server, Speech.wav(byteArrayOf(1, 0, -1, 0x7f)).joinToString("") { "%02x".format(it) })
    }

    @Test fun openAiAsItsSdkSentIt() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/audio/transcriptions", body = fixture("openai_transcription.json"))
        val ledger = Ledger(null)
        val speech = MeteredSpeech(Speech.of(provider("openai"), "gpt-4o-transcribe", key, http = http.client), "openai:gpt-4o-transcribe", ledger)
        assertEquals("Set a timer for five minutes.", speech.hear(voice))
        val r = http.asked.single().request
        assertEquals("Bearer $key", r.header("Authorization"))
        assertFalse(key in r.url.toString())
        val p = parts(r)
        assertEquals(listOf("file", "model", "language", "response_format"), p.keys.toList())
        assertEquals("gpt-4o-transcribe", p.text("model"))
        assertEquals("en", p.text("language"))
        assertEquals("json", p.text("response_format"))
        assertEquals("audio.wav", p["file"]!!.first)
        assertEquals("audio/wav", p["file"]!!.second)
        assertArrayEquals(Speech.wav(voice), p["file"]!!.third)
        // The seconds heard, and a token per four characters written: the server read no counts here.
        val row = ledger.since(0.0).single()
        assertEquals("stt" to "openai:gpt-4o-transcribe", row.slot to row.model)
        assertEquals(Counts(out = 7.0, secs = 1.0), row.counts)
    }

    @Test fun silenceCostsNoCall() = runBlocking {
        val http = FakeHttp()
        val ledger = Ledger(null)
        val speech = MeteredSpeech(Speech.of(provider("openai"), "whisper-1", key, http = http.client), "openai:whisper-1", ledger)
        assertEquals("", speech.hear(ByteArray(Speech.BYTES_PER_SECOND)))
        assertEquals("", speech.hear(pcm(1) { false }))
        assertTrue(http.asked.isEmpty())
        assertTrue(ledger.since(0.0).isEmpty())
    }

    @Test fun theLanguageAsked() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/audio/transcriptions", body = fixture("openai_transcription.json"))
        Speech.of(provider("openai"), "whisper-1", key, http = http.client).transcribe(voice, "fr")
        Speech.of(provider("openai"), "whisper-1", key, language = null, http = http.client).transcribe(voice)
        assertEquals("fr", parts(http.asked[0].request).text("language"))
        assertFalse("language" in parts(http.asked[1].request))
    }

    @Test fun geminiAsItsSdkSentIt() = runBlocking {
        val http = FakeHttp()
        http.on("POST", ":generateContent", body = fixture("gemini_transcription.json"))
        val ledger = Ledger(null)
        val speech = MeteredSpeech(Speech.of(provider("gemini"), "gemini-2.5-flash", geminiKey, http = http.client), "gemini:gemini-2.5-flash", ledger)
        // The thought is not the transcript.
        assertEquals("Set a timer for five minutes.", speech.hear(voice))
        val asked = http.asked.single()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", asked.path)
        assertEquals(geminiKey, asked.request.header("x-goog-api-key"))
        assertFalse(geminiKey in asked.request.url.toString())
        val parts = asked.json().getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val audio = parts.getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", audio.getString("mimeType"))
        assertArrayEquals(Speech.wav(voice), Base64.getDecoder().decode(audio.getString("data")))
        assertEquals(GeminiSpeech.PROMPT.replace("{lang}", ", in English"), parts.getJSONObject(1).getString("text"))
        assertFalse(asked.json().has("generationConfig"))
        // The instruction's tokens and what came out with the thinking; the audio by its seconds.
        assertEquals(Counts(input = 41.0, out = 20.0, secs = 1.0), ledger.since(0.0).single().counts)
    }

    @Test fun geminiIsToldTheLanguageByName() {
        val g = GeminiSpeech(provider("gemini"), "gemini-2.5-flash", geminiKey, FakeHttp().client)
        fun said(lang: String?) = g.body(voice, lang).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text")
        assertTrue(said("ja").contains("exactly as it was said, in Japanese. Reply"))
        assertTrue(said("xx").contains("exactly as it was said, in xx. Reply"))
        assertTrue(said(null).contains("exactly as it was said. Reply"))
    }

    @Test fun aRecordingsPieceTimedInTheWhole() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/audio/transcriptions", body = fixture("openai_transcription_verbose.json"))
        val speech = Speech.of(provider("openai"), "whisper-1", key, http = http.client)
        assertEquals(600, speech.pieceS)
        assertEquals(listOf(Segment(600.0, 603.32, "The budget is forty thousand."), Segment(605.2, 608.47, "We agreed on Tuesday.")),
            speech.segments(voice, 600.0))
        val p = parts(http.asked.single().request)
        assertEquals("verbose_json", p.text("response_format"))
        assertEquals("segment", p.text("timestamp_granularities[]"))
        assertEquals("en", p.text("language"))
    }

    @Test fun aModelThatGivesNoTimesIsNeverAskedForThemAgain() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/audio/transcriptions", code = 400, body = fixture("openai_verbose_refused.json"))
        http.on("POST", "/v1/audio/transcriptions", body = fixture("openai_transcription.json"))
        val logged = mutableListOf<String>()
        val speech = Speech.of(provider("openai"), "gpt-4o-transcribe", key, http = http.client, log = { logged += it })
        assertEquals(listOf(Segment(12.0, 13.0, "Set a timer for five minutes.")), speech.segments(voice, 12.0))
        assertFalse(speech.timestamps)
        assertEquals(30, speech.pieceS)
        speech.segments(voice, 13.0)
        assertEquals(listOf("verbose_json", "json", "json"), http.asked.map { parts(it.request).text("response_format") })
        assertTrue(logged.single(), "gives no timestamps" in logged.single())
    }

    /** [pcm] as a recording is kept: a WAV file. */
    private fun recording(pcm: ByteArray): File = tmp.newFile().apply { writeBytes(Speech.wav(pcm)) }

    /** Each piece's offset and length in samples, the file read as a recording is. */
    private fun cuts(file: File, fromS: Double = 0.0, pieceS: Int = 30): List<Pair<Double, Int>> = runBlocking {
        val out = mutableListOf<Pair<Double, Int>>()
        Speech.pieces(file, fromS, pieceS) { at, piece -> out += at to piece.size / 2 }
        out
    }

    @Test fun cutAtTheQuietestFrameAsTheServerCuts() {
        val whole = pcm(70)
        // A silent frame at 27.5 s, in the last five seconds of the first window.
        for (i in 440_000 * 2 until 440_320 * 2) whole[i] = 0
        val file = recording(whole)
        // What the server's transcription.pieces gives for the same samples.
        assertEquals(listOf(0.0 to 440_000, 27.5 to 400_000, 52.5 to 280_000), cuts(file))
        assertEquals(listOf(10.0, 35.0, 60.0), cuts(file, 10.0).map { it.first })
        assertEquals(listOf(0.0 to 1_120_000), cuts(file, pieceS = 600))
        assertEquals(70.0, Speech.seconds(file), 0.0)
    }

    @Test fun anHourIsReadAPieceAtATime() {
        // An hour on disk (115 MB), written in 64 kB frames as the recorder writes, never whole in memory:
        // a tone, with a silent frame every 597 s, which is 2 s into each piece's last five seconds.
        val file = tmp.newFile("hour.wav")
        val total = 3600 * Speech.SAMPLE_RATE
        val every = 597 * Speech.SAMPLE_RATE
        val tone = IntArray(40) { (8000 * sin(2 * PI * it / 40)).toInt() }
        file.outputStream().buffered(64 * 1024).use { out ->
            out.write(Wav.header(total * 2))
            val frame = ByteArray(64 * 1024)
            var i = 0
            while (i < total) {
                val n = minOf(frame.size / 2, total - i)
                for (k in 0 until n) {
                    val at = i + k
                    val v = if (at >= every && at % every < 320) 0 else tone[at % 40]
                    frame[2 * k] = v.toByte()
                    frame[2 * k + 1] = (v shr 8).toByte()
                }
                out.write(frame, 0, n * 2)
                i += n
            }
        }
        val rt = Runtime.getRuntime()
        var most = 0L
        val got = mutableListOf<Pair<Double, Int>>()
        runBlocking {
            Speech.pieces(file, 0.0, 600) { at, piece ->
                got += at to piece.size / 2
                System.gc()
                most = maxOf(most, rt.totalMemory() - rt.freeMemory())
            }
        }
        assertEquals((0..6).map { it * 597.0 }, got.map { it.first })
        assertEquals(total, got.sumOf { it.second })
        assertTrue(got.all { it.second <= 600 * Speech.SAMPLE_RATE })
        // About one piece (19 MB) at a time, nowhere near the tape's 115 MB.
        assertTrue("${most / 1_000_000} MB in use", most < 100_000_000)
    }

    @Test fun aRecordingInPiecesASilentOneSentNowhere() = runBlocking {
        class Heard : CloudSpeech("m", null) {
            var calls = 0
            override suspend fun counted(pcm: ByteArray, language: String?): Pair<String, Counts?> = "piece ${calls++}" to null
        }
        val heard = Heard()
        val ledger = Ledger(null)
        val progress = mutableListOf<Double>()
        // Speech, forty seconds of silence, speech: the middle piece is all silence.
        val got = MeteredSpeech(heard, "x:m", ledger).recording(recording(pcm(70) { it < 20 || it >= 60 })) { _, _, done -> progress += done }
        assertEquals(listOf(Segment(0.0, 25.0, "piece 0"), Segment(50.0, 70.0, "piece 1")), got)
        assertEquals(listOf(25.0 / 70, 50.0 / 70, 1.0), progress)
        assertEquals(listOf(25.0, 20.0), ledger.since(0.0).map { it.counts.secs })
    }

    @Test fun oneRetryAfterAServerErrorAndNoneAfterARefusal() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/audio/transcriptions", code = 503, body = """{"error":{"message":"overloaded"}}""")
        http.on("POST", "/v1/audio/transcriptions", body = fixture("openai_transcription.json"))
        assertEquals("Set a timer for five minutes.", Speech.of(provider("openai"), "whisper-1", key, http = http.client).transcribe(voice))
        assertEquals(2, http.asked.size)

        val refused = FakeHttp()
        refused.on("POST", "/v1/audio/transcriptions", code = 401, body = """{"error":{"message":"Incorrect API key provided: $key."}}""")
        val e = assertThrows(AskError::class.java) { runBlocking { Speech.of(provider("openai"), "whisper-1", key, http = refused.client).transcribe(voice) } }
        assertEquals(401, e.code)
        assertFalse(key in e.message!!)
        assertEquals(1, refused.asked.size)
    }

    @Test fun ollamaHasNoSpeech() {
        assertThrows(IllegalArgumentException::class.java) { Speech.of(provider("ollama"), "whisper", null) }
        Unit
    }
}
