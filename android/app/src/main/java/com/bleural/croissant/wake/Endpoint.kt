package com.bleural.croissant.wake

/**
 * When a wake's ask is over, from [SpeechDetector]'s verdict on each window. After the wake
 * it ignores the phrase's tail for [WakeConfig.GRACE_MS], then waits up to
 * [WakeConfig.NO_SPEECH_MS] for speech ([Verdict.Nothing] if none), ends after
 * [WakeConfig.SILENCE_MS] without speech once speech was heard, and at [WakeConfig.MAX_MS]
 * whatever happens.
 */
class Endpoint {
    enum class Verdict { Going, Over, Nothing }

    private var at = -1
    private var spoke = false
    private var quietMs = 0

    /** The wake: from here on, [offer] counts. */
    fun start() { at = 0; spoke = false; quietMs = 0 }

    fun offer(speech: Boolean, ms: Int): Verdict {
        check(at >= 0) { "endpoint: offered before start" }
        at += ms
        if (at >= WakeConfig.MAX_MS) return if (spoke) Verdict.Over else Verdict.Nothing
        if (at <= WakeConfig.GRACE_MS) return Verdict.Going
        if (speech) { spoke = true; quietMs = 0; return Verdict.Going }
        quietMs += ms
        return when {
            spoke && quietMs >= WakeConfig.SILENCE_MS -> Verdict.Over
            !spoke && at >= WakeConfig.NO_SPEECH_MS -> Verdict.Nothing
            else -> Verdict.Going
        }
    }
}
