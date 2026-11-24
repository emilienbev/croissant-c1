package com.bleural.croissant.wake

/**
 * When a wake's ask is over, from the loudness of each capture chunk: speech is a chunk
 * [WakeConfig.SPEECH_OVER_FLOOR_DB] over the room's floor. After the wake it ignores the
 * phrase's tail for [WakeConfig.GRACE_MS], then waits up to [WakeConfig.NO_SPEECH_MS] for
 * speech ([Verdict.Nothing] if none), ends after [WakeConfig.SILENCE_MS] of quiet once speech
 * was heard, and at [WakeConfig.MAX_MS] whatever happens.
 *
 * The floor follows the room all the time ([track]), falling to quiet fast and rising to a
 * louder room slowly, so a kitchen's noise is the floor and not speech. Capture thread only.
 */
class Endpoint {
    enum class Verdict { Going, Over, Nothing }

    private var floor = FLOOR_START
    private var at = -1
    private var spoke = false
    private var quietMs = 0

    /** Every chunk while the wake word listens, so the floor is the room's when a wake comes. */
    fun track(db: Float) {
        floor += (db - floor) * (if (db < floor) FLOOR_FALL else FLOOR_RISE)
    }

    /** The wake: from here on, [offer] counts. */
    fun start() { at = 0; spoke = false; quietMs = 0 }

    fun offer(db: Float, ms: Int): Verdict {
        check(at >= 0) { "endpoint: offered before start" }
        track(db)
        at += ms
        if (at >= WakeConfig.MAX_MS) return if (spoke) Verdict.Over else Verdict.Nothing
        if (at <= WakeConfig.GRACE_MS) return Verdict.Going
        val speech = db >= floor + WakeConfig.SPEECH_OVER_FLOOR_DB && db >= WakeConfig.SPEECH_MIN_DB
        if (speech) { spoke = true; quietMs = 0; return Verdict.Going }
        quietMs += ms
        return when {
            spoke && quietMs >= WakeConfig.SILENCE_MS -> Verdict.Over
            !spoke && at >= WakeConfig.NO_SPEECH_MS -> Verdict.Nothing
            else -> Verdict.Going
        }
    }

    companion object {
        /** A chunk's loudness in dBFS (its RMS), [FLOOR_MIN] for digital silence. 16-bit little-endian PCM. */
        fun dbfs(buf: ByteArray, n: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val v = (buf[i].toInt() and 0xff or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                sum += v.toDouble() * v
                i += 2
            }
            val rms = kotlin.math.sqrt(sum / (n / 2).coerceAtLeast(1)) / 32768.0
            return if (rms <= 0.0) FLOOR_MIN else maxOf(FLOOR_MIN, (20 * kotlin.math.log10(rms)).toFloat())
        }

        private const val FLOOR_MIN = -100f
        private const val FLOOR_START = -60f
        /** Per 20 ms chunk: down to quiet within about 0.2 s, up to a louder room over about 40 s. */
        private const val FLOOR_FALL = 0.1f
        private const val FLOOR_RISE = 0.0005f
    }
}
