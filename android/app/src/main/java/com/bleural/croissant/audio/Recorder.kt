package com.bleural.croissant.audio

import android.util.Log
import com.bleural.croissant.data.Dates
import com.bleural.croissant.data.RecordingMeta
import com.bleural.croissant.data.RecordingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10

enum class RecPhase { Idle, Recording, Paused }

/** What the recorder is doing; [file] and [startedAt] (epoch ms) while it is not idle. */
data class RecorderState(val phase: RecPhase = RecPhase.Idle, val file: File? = null, val startedAt: Long = 0L)

/**
 * The tape recorder: the always-hot capture's 20 ms chunks
 * copied into a WAV as well as into the ring buffer, so a recording costs no second
 * AudioRecord and no new wake lock, and the panel sleeping does not touch it. The
 * capture thread only copies a chunk onto a queue; a writer thread appends it to the
 * file and patches the header every ten seconds, so a slow flash write can never
 * stall the microphone. Pause stops the copying and the counter; nothing is written
 * until resume, and the length does not count the gap. Stop patches the header, closes
 * the file and writes the sidecar: there is no discard.
 */
class Recorder(private val store: RecordingsStore) {
    companion object {
        private const val TAG = "Recorder"
        /** The header is patched this often, so a crash loses at most this much playable audio. */
        private const val PATCH_EVERY_BYTES = 10 * 1000 * AudioCapture.BYTES_PER_MS
        private const val SECOND_BYTES = 1000 * AudioCapture.BYTES_PER_MS
        /** The ruler under the cassette: the level of each of the last sixty seconds. */
        const val HISTORY = 60
        private val STOP = ByteArray(0)
    }

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var accepting = false
    private var writer: Thread? = null
    @Volatile private var written = 0L
    private var sincePatch = 0L

    // The last minute's levels, a ring: the loudest sample of each second, 0 to 1 on
    // the meter's scale. Read by the screen on the second.
    private val levels = FloatArray(HISTORY)
    private var head = 0
    private var secondPeak = 0f
    private var secondBytes = 0L

    /** Milliseconds recorded so far, pauses excluded. */
    fun elapsedMs(): Long = Wav.millis(written)

    /** The last sixty seconds' levels, oldest first; zeros before anything was recorded. */
    fun levels(): FloatArray = synchronized(levels) {
        FloatArray(HISTORY) { i -> levels[(head + i) % HISTORY] }
    }

    /** From the capture thread, every chunk: a copy onto the queue while recording, nothing otherwise. */
    fun feed(buf: ByteArray, n: Int) {
        if (!accepting) return
        queue.offer(buf.copyOf(n))
    }

    @Synchronized
    fun start(now: OffsetDateTime = OffsetDateTime.now()) {
        if (_state.value.phase != RecPhase.Idle) return
        val file = store.newFile(now.toLocalDateTime())
        val raf = RandomAccessFile(file, "rw")
        raf.write(Wav.header(0))
        written = 0L; sincePatch = 0L
        synchronized(levels) { levels.fill(0f); head = 0; secondPeak = 0f; secondBytes = 0L }
        queue.clear()
        // The sidecar says "recording" from the first byte, so a crash is recognised on the next start.
        store.keep(file, RecordingMeta(Dates.stamp(now), 0L, RecordingMeta.RECORDING, number = store.nextNumber()))
        writer = thread(name = "recorder", isDaemon = true) { drain(raf) }
        accepting = true
        _state.value = RecorderState(RecPhase.Recording, file, now.toInstant().toEpochMilli())
        Log.i(TAG, "recording ${file.name}")
    }

    @Synchronized
    fun pause() {
        if (_state.value.phase != RecPhase.Recording) return
        accepting = false
        _state.value = _state.value.copy(phase = RecPhase.Paused)
        Log.i(TAG, "paused at ${elapsedMs()} ms")
    }

    @Synchronized
    fun resume() {
        if (_state.value.phase != RecPhase.Paused) return
        accepting = true
        _state.value = _state.value.copy(phase = RecPhase.Recording)
        Log.i(TAG, "resumed")
    }

    /** Ends and keeps the recording. [ended] is what the sidecar will say; the service passes interrupted on its own death. */
    @Synchronized
    fun stop(ended: String = RecordingMeta.STOP) {
        val s = _state.value
        if (s.phase == RecPhase.Idle) return
        accepting = false
        queue.offer(STOP)
        writer?.join(5_000)
        writer = null
        val file = s.file!!
        val meta = store.list().firstOrNull { it.file == file }?.meta
        val kept = meta ?: RecordingMeta(Dates.stamp(OffsetDateTime.now()), 0L, ended, number = store.nextNumber())
        store.keep(file, kept.copy(durationMs = elapsedMs(), ended = ended))
        _state.value = RecorderState()
        Log.i(TAG, "stopped ${file.name}: ${elapsedMs()} ms, ${file.length() / 1024} kB")
    }

    /** The writer thread: chunks to the file, the header patched on the way, closed on the sentinel. */
    private fun drain(raf: RandomAccessFile) {
        try {
            while (true) {
                val chunk = queue.take()
                if (chunk === STOP) break
                raf.write(chunk)
                written += chunk.size
                sincePatch += chunk.size
                level(chunk)
                if (sincePatch >= PATCH_EVERY_BYTES) { Wav.patch(raf, written); sincePatch = 0L }
            }
            Wav.patch(raf, written)
        } catch (e: Exception) {
            Log.e(TAG, "writer failed", e)
        } finally {
            runCatching { raf.fd.sync() }
            runCatching { raf.close() }
        }
    }

    /** The loudest sample of the chunk, folded into this second's peak; a full second moves the ring on. */
    private fun level(chunk: ByteArray) {
        var peak = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val s = abs((chunk[i].toInt() and 0xff or (chunk[i + 1].toInt() shl 8)).toShort().toInt())
            if (s > peak) peak = s
            i += 2
        }
        val db = 20 * log10(peak / 32768.0 + 1e-9)
        val lin = ((db + 60) / 55).toFloat().coerceIn(0f, 1f)
        synchronized(levels) {
            if (lin > secondPeak) secondPeak = lin
            secondBytes += chunk.size
            if (secondBytes >= SECOND_BYTES) {
                levels[head] = secondPeak
                head = (head + 1) % HISTORY
                secondPeak = 0f
                secondBytes -= SECOND_BYTES
            }
        }
    }
}
