package com.bleural.croissant.offline

import kotlin.math.log10

/**
 * Whether a hold has speech in it at all, before Parakeet is asked what was said: the
 * server's gate (server/README.md, "Whisper hallucinates on silence"; `measure` in
 * server/croissant_server/capture.py), with its numbers (`stt_gate_*` in config.py).
 *
 * Parakeet needs it as much as Whisper does: digital zeros come back empty, but the
 * ring's pre-roll alone came back as "Yeah.", "Okay." or "Mm-hmm." in 15 of 30 real R1
 * takes. Speech must reach [MIN_ACTIVE_DBFS] in its
 * loudest fifth of 20 ms frames and sit [MIN_SNR_DB] above the quietest fifth. Real R1
 * speech measures about -32 / +37 dB, the pre-roll alone about -64 / +6 dB.
 */
object SpeechGate {
    const val MIN_ACTIVE_DBFS = -50.0
    const val MIN_SNR_DB = 15.0

    private const val FRAME = 320 // 20 ms at 16 kHz
    private const val TAIL = 0.2

    /** The loudest and quietest fifths of [pcm]'s frames, in dBFS; -inf for digital silence. */
    data class Levels(val active: Double, val floor: Double) {
        val speech: Boolean get() = active >= MIN_ACTIVE_DBFS && active - floor >= MIN_SNR_DB
    }

    fun levels(pcm: ShortArray): Levels = levels(pcm.size) { pcm[it] }

    /** [levels] of 16-bit little-endian PCM as it is (an odd last byte left out), with no copy of it. */
    fun levels(pcm: ByteArray): Levels = levels(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }

    fun speech(pcm: ShortArray): Boolean = levels(pcm).speech

    fun speech(pcm: ByteArray): Boolean = levels(pcm).speech

    private inline fun levels(size: Int, sample: (Int) -> Short): Levels {
        if (size == 0) return Levels(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val frames = size / FRAME
        if (frames < 5) {
            // Too short for tails to mean anything; the whole clip for both, as the server does.
            val p = power(0, size, sample)
            return Levels(dbfs(p), dbfs(p))
        }
        val powers = DoubleArray(frames) { power(it * FRAME, FRAME, sample) }.apply { sort() }
        val k = maxOf(1, (frames * TAIL).toInt())
        return Levels(dbfs(powers.takeLast(k).average()), dbfs(powers.take(k).average()))
    }

    private inline fun power(from: Int, n: Int, sample: (Int) -> Short): Double {
        var sum = 0.0
        for (i in from until from + n) {
            val x = sample(i) / 32768.0
            sum += x * x
        }
        return sum / n
    }

    private fun dbfs(power: Double) = if (power > 0) 10 * log10(power) else Double.NEGATIVE_INFINITY
}
