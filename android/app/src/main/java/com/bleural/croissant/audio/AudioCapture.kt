package com.bleural.croissant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Always-hot microphone capture.
 *
 * The recorder is opened once and never stopped while the service lives. Starting an
 * AudioRecord costs ~100-200ms of routing and mic warm-up on this hardware, which would
 * clip the first syllable of every utterance if we opened it on button-down.
 *
 * Consequence accepted by design: Android's microphone privacy indicator stays lit for
 * as long as the service runs.
 */
class AudioCapture(
    private val onChunk: (ByteArray, Int) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16_000      // every ASR resamples to 16k; 48k is 3x the bytes for nothing
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_MS = 20
        const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS / 1000
        private const val TAG = "AudioCapture"
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val chunkBytes = CHUNK_MS * BYTES_PER_MS
        // Give the driver generous headroom so a slow consumer cannot cause overrun.
        val bufSize = maxOf(minBuf, chunkBytes * 8)

        val r = AudioRecord(
            // VOICE_RECOGNITION rather than MIC: it disables the AGC/noise pipeline
            // tuned for human listening, which is what ASR models want.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${r.state})")
            r.release()
            return
        }
        record = r
        running = true
        r.startRecording()

        worker = thread(name = "audio-capture", isDaemon = true) {
            val buf = ByteArray(chunkBytes)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    onChunk(buf, n)
                } else if (n < 0) {
                    Log.e(TAG, "AudioRecord.read returned $n")
                    break
                }
            }
        }
        Log.i(TAG, "capture started: ${SAMPLE_RATE}Hz mono pcm16, chunk=${chunkBytes}B buf=${bufSize}B")
    }

    fun stop() {
        if (record == null) return
        running = false
        worker?.join(500)
        worker = null
        record?.run {
            try { if (state == AudioRecord.STATE_INITIALIZED) stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null
        Log.i(TAG, "capture stopped")
    }
}
