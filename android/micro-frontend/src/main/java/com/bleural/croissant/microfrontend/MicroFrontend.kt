package com.bleural.croissant.microfrontend

/**
 * The TFLite Micro audio frontend: 16 kHz 16-bit mono in, a frame of [CHANNELS] features every
 * [STEP_MS], each from a [WINDOW_MS] window, after its own noise reduction and gain control.
 * Configured as pymicro-features 2.0.2, which made the features microWakeWord models learn from,
 * so the device hears what the model was trained on. Not thread-safe: one caller at a time.
 */
class MicroFrontend : AutoCloseable {
    private var handle: Long = nativeCreate().also { check(it != 0L) { "micro frontend: could not allocate its state" } }
    private val frame = FloatArray(CHANNELS)

    /**
     * Feeds [samples] in [STEP_MS] slices, as microWakeWord's feature loop does, and hands each
     * finished frame to [onFrame]. The array passed to [onFrame] is reused: copy it to keep it.
     */
    fun feed(samples: ShortArray, onFrame: (FloatArray) -> Unit) {
        check(handle != 0L) { "micro frontend: used after close" }
        var at = 0
        while (at < samples.size) {
            val packed = nativeProcess(handle, samples, at, minOf(SAMPLES_PER_STEP, samples.size - at), frame)
            val read = packed ushr 8
            if (packed and 0xff == CHANNELS) onFrame(frame)
            check(read > 0) { "micro frontend: read no samples at $at of ${samples.size}" }
            at += read
        }
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        const val CHANNELS = 40
        const val STEP_MS = 10
        const val WINDOW_MS = 30
        const val SAMPLES_PER_STEP = 16 * STEP_MS

        init { System.loadLibrary("micro_frontend") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeProcess(handle: Long, samples: ShortArray, offset: Int, count: Int, features: FloatArray): Int
    }
}
