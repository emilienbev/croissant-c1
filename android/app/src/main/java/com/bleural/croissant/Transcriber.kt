package com.bleural.croissant

import android.util.Log
import com.bleural.croissant.audio.AudioCapture
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.audio.Wav
import com.bleural.croissant.data.RecordingMeta
import com.bleural.croissant.data.RecordingsStore
import com.bleural.croissant.data.Segment
import com.bleural.croissant.net.Protocol
import com.bleural.croissant.net.SessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

enum class TxPhase { Sending, Transcribing }

/**
 * A recording on its way to the server: [phase] and [progress] (the upload by bytes,
 * then the audio transcribed) while it runs; [error] once it has failed, the state
 * kept so the page can say so until the next attempt or a delete.
 */
data class TranscribeState(val file: File, val phase: TxPhase, val progress: Float, val error: String? = null)

/**
 * Sends a recording for transcription and keeps what comes back. Only ever on a tap of Transcribe: nothing is sent on
 * its own. One at a time. Lives in the service, so leaving the page or sleeping does
 * not end it; a delete does. The file's PCM goes as 64 kB frames at the socket's pace
 * (OkHttp queues at most 16 MiB), the server answers with progress and segments as it
 * goes, and every batch of segments lands in the sidecar at once, so a dropped link
 * keeps what was transcribed and the next attempt resumes from the last segment's end.
 *
 * With a cloud speech model picked ([speech]) no socket opens: the C1 sends the file to the
 * provider itself, a piece at a time (ask/Speech.kt), each piece's segments kept as they
 * come, so a failure keeps what was heard and the next attempt goes on from there too.
 */
class Transcriber(private val store: RecordingsStore, private val scope: CoroutineScope,
                  private val speech: () -> HoldSpeech = { HoldSpeech.Mac(null) }, private val url: () -> String) {
    companion object {
        private const val TAG = "Transcriber"
        private const val FRAME = 64 * 1024
        /** The upload waits while this much is queued: enough to keep the socket busy, little enough that progress is honest. */
        private const val HIGH_WATER = 1024 * 1024L
    }

    private val _state = MutableStateFlow<TranscribeState?>(null)
    val state: StateFlow<TranscribeState?> = _state

    private var client: SessionClient? = null
    private var upload: Job? = null

    val busy: Boolean get() = _state.value?.let { it.error == null } == true

    @Synchronized
    fun start(file: File) {
        if (busy) { Log.w(TAG, "busy, ignoring ${file.name}"); return }
        val meta = store.list().firstOrNull { it.file == file }?.meta
        val from = meta?.transcript?.lastOrNull()?.end ?: 0.0
        val segments = ArrayList(meta?.transcript ?: emptyList())
        val total = (file.length() - Wav.HEADER_BYTES).coerceAtLeast(0)
        val stt = when (val s = speech()) {
            is HoldSpeech.Cloud -> return here(file, s, from, segments)
            is HoldSpeech.NoKey -> {
                _state.value = TranscribeState(file, TxPhase.Transcribing, 0f)
                return finish("no key for ${s.provider}")
            }
            is HoldSpeech.Mac -> s.pick
        }
        _state.value = TranscribeState(file, TxPhase.Sending, 0f)
        val c = SessionClient(url())
        client = c
        c.connect(object : SessionClient.Listener {
            override fun onOpen() = Unit
            override fun onMessage(msg: Protocol.Incoming) {
                when (msg) {
                    is Protocol.Incoming.Progress -> _state.update { it?.copy(phase = TxPhase.Transcribing, progress = msg.done) }
                    is Protocol.Incoming.Segments -> {
                        segments += msg.items
                        keep(file, segments, sent = null)
                    }
                    is Protocol.Incoming.Done -> {
                        keep(file, segments, sent = msg.id.ifEmpty { file.nameWithoutExtension })
                        finish(null)
                        Cues.play(Sound.AnswerArrived)
                    }
                    is Protocol.Incoming.Failure -> finish(msg.message)
                    else -> Log.w(TAG, "unhandled frame: $msg")
                }
            }
            override fun onClosed() { if (busy) finish("Connection closed early") }
            override fun onFailure(kind: SessionClient.FailureKind, message: String) { finish(message) }
        })
        c.sendText(Protocol.startTranscribe(AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS, file.nameWithoutExtension, meta?.startedAt ?: "", total, from, stt))
        upload = scope.launch(Dispatchers.IO) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(Wav.HEADER_BYTES.toLong())
                    val buf = ByteArray(FRAME)
                    var sent = 0L
                    while (isActive) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        while (isActive && c.queued() > HIGH_WATER) delay(20)
                        if (!c.sendAudio(buf, n)) { finish("Connection lost"); return@launch }
                        sent += n
                        val done = ((sent - c.queued()).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                        _state.update { s -> if (s?.phase == TxPhase.Sending) s.copy(progress = done) else s }
                    }
                }
                if (isActive) c.sendText(Protocol.end())
                Log.i(TAG, "sent ${file.name}: ${total / 1024} kB, from ${from}s")
            } catch (e: Exception) {
                Log.e(TAG, "upload failed", e)
                finish(e.message ?: "Upload failed")
            }
        }
    }

    /**
     * [file] heard by a cloud model from here, from [from] on: a piece at a time, each one's
     * [segments] kept as they come, its progress through the whole tape.
     */
    private fun here(file: File, s: HoldSpeech.Cloud, from: Double, segments: MutableList<Segment>) {
        _state.value = TranscribeState(file, TxPhase.Transcribing, 0f)
        upload = scope.launch {
            try {
                s.speech.recording(file, from) { _, found, done ->
                    if (found.isNotEmpty()) {
                        segments += found
                        keep(file, segments, sent = null)
                    }
                    _state.update { it?.copy(progress = done.toFloat()) }
                }
                keep(file, segments, sent = file.nameWithoutExtension)
                Log.i(TAG, "${file.name} heard by ${s.entry.id}, from ${from}s")
                finish(null)
                Cues.play(Sound.AnswerArrived)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${file.name} not heard by ${s.entry.id}: ${e.message}")
                finish(whyFailed(e, s.entry.provider, null))
            }
        }
    }

    /** A delete, or a new start over a failed one: the socket goes, the state with it. */
    @Synchronized
    fun cancel(file: File) {
        val s = _state.value ?: return
        if (s.file != file) return
        upload?.cancel(); upload = null
        client?.abort(); client = null
        _state.value = null
    }

    private fun keep(file: File, segments: List<Segment>, sent: String?) {
        val meta = store.list().firstOrNull { it.file == file }?.meta ?: return
        store.keep(file, meta.copy(transcript = segments.toList(), sent = sent ?: meta.sent))
    }

    @Synchronized
    private fun finish(error: String?) {
        val s = _state.value ?: return
        if (s.error != null) return  // the first failure is the one that counts; abort() below re-enters here
        upload?.cancel(); upload = null
        client?.let { if (error == null) it.close() else it.abort() }; client = null
        _state.value = if (error == null) null else s.copy(error = error)
        if (error != null) { Log.w(TAG, "${s.file.name}: $error"); Cues.play(Sound.ExchangeFailed) }
        else Log.i(TAG, "${s.file.name} transcribed")
    }
}
