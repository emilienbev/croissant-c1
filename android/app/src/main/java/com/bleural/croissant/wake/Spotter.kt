package com.bleural.croissant.wake

import com.bleural.croissant.microfrontend.MicroFrontend
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A microWakeWord streaming model on the mic: 16 kHz samples in, a probability that the phrase
 * was just said out, every [FRAMES_PER_STEP] feature frames (30 ms). The model keeps its own
 * state between calls, so one Spotter follows one stream. Quantizes and dequantizes as
 * microWakeWord's Python `inference.Model` does, so its numbers match the Mac's scoring.
 * Not thread-safe.
 */
class Spotter(model: File) : AutoCloseable {
    private val frontend = MicroFrontend()
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
    private val input = ByteBuffer.allocateDirect(FRAMES_PER_STEP * MicroFrontend.CHANNELS).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    private val inScale: Float
    private val inZero: Int
    private val outZero: Int
    private var frames = 0

    init {
        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        val want = intArrayOf(1, FRAMES_PER_STEP, MicroFrontend.CHANNELS)
        check(inT.shape().contentEquals(want)) { "wake model: input ${inT.shape().toList()}, expected ${want.toList()}" }
        check(outT.numBytes() == 1) { "wake model: output of ${outT.numBytes()} bytes, expected one uint8" }
        inScale = inT.quantizationParams().scale
        inZero = inT.quantizationParams().zeroPoint
        outZero = outT.quantizationParams().zeroPoint
    }

    /** Feeds samples; [onFrame] sees each feature frame, [onProbability] each model output. */
    fun feed(samples: ShortArray, onFrame: (FloatArray) -> Unit = {}, onProbability: (Float) -> Unit) {
        frontend.feed(samples) { f ->
            onFrame(f)
            for (v in f) input.put(quantize(v))
            if (++frames == FRAMES_PER_STEP) {
                input.rewind(); output.rewind()
                interpreter.run(input, output)
                input.rewind()
                frames = 0
                onProbability(((output.get(0).toInt() and 0xff) - outZero) / 255f)
            }
        }
    }

    // As numpy's astype(int8) after v / scale + zero point: truncated, here also held in range.
    private fun quantize(v: Float): Byte = (v / inScale + inZero).toInt().coerceIn(-128, 127).toByte()

    override fun close() {
        interpreter.close()
        frontend.close()
    }

    companion object {
        /** Feature frames per model step: the stride it was trained with (30 ms). */
        const val FRAMES_PER_STEP = 3
        const val STEP_MS = FRAMES_PER_STEP * MicroFrontend.STEP_MS
    }
}
