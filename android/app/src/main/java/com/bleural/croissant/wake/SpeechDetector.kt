package com.bleural.croissant.wake

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Whether someone is talking, from Silero VAD (voice activity detection) through sherpa-onnx:
 * 16 kHz samples in, a speech or not verdict for every [WINDOW_MS] window out. Judges the
 * sound's shape, not its loudness, so a kitchen's steady noise is not speech. The model keeps
 * its own state between windows, so one detector follows one stream: [reset] it before
 * another. Not thread-safe.
 */
class SpeechDetector(assets: AssetManager) : AutoCloseable {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = WakeConfig.VAD_ASSET, windowSize = WINDOW),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0

    fun reset() {
        vad.reset()
        filled = 0
    }

    /** Feeds samples; [onWindow] sees each full window's verdict. */
    fun feed(samples: ShortArray, onWindow: (speech: Boolean) -> Unit) {
        for (s in samples) {
            window[filled++] = s / 32768f
            if (filled == WINDOW) {
                filled = 0
                onWindow(vad.compute(window) >= WakeConfig.VAD_THRESHOLD)
            }
        }
    }

    override fun close() = vad.release()

    companion object {
        private const val SAMPLE_RATE = 16_000
        /** Silero's window at 16 kHz. */
        const val WINDOW = 512
        const val WINDOW_MS = WINDOW * 1000 / SAMPLE_RATE
    }
}
