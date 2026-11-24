package com.bleural.croissant.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointTest {
    private val window = SpeechDetector.WINDOW_MS

    /** The verdict, and when, after runs of windows with or without speech. */
    private fun run(vararg spans: Pair<Boolean, Int>): Pair<Endpoint.Verdict, Int> {
        val e = Endpoint()
        e.start()
        var ms = 0
        for ((speech, len) in spans) repeat(len / window) {
            ms += window
            val v = e.offer(speech, window)
            if (v != Endpoint.Verdict.Going) return v to ms
        }
        return Endpoint.Verdict.Going to ms
    }

    /** The first whole window at or past [ms]. */
    private fun at(ms: Int) = (ms + window - 1) / window * window

    @Test fun endsAfterTheQuietThatFollowsTheAsk() {
        val talk = at(1500)
        assertEquals(Endpoint.Verdict.Over to talk + at(WakeConfig.SILENCE_MS), run(true to talk, false to 2000))
    }

    @Test fun nothingSaidAfterThePhraseGoesBackQuietly() {
        assertEquals(Endpoint.Verdict.Nothing to at(WakeConfig.NO_SPEECH_MS), run(false to 5000))
    }

    @Test fun thePhraseTailIsNotTheAsk() {
        val tail = WakeConfig.GRACE_MS / window * window
        assertEquals(Endpoint.Verdict.Nothing to at(WakeConfig.NO_SPEECH_MS), run(true to tail, false to 5000))
    }

    @Test fun aPauseBeforeTheAskIsFine() {
        val pause = at(1000)
        val talk = at(1600)
        assertEquals(Endpoint.Verdict.Over to pause + talk + at(WakeConfig.SILENCE_MS), run(false to pause, true to talk, false to 2000))
    }

    @Test fun aShortPauseMidAskDoesNotEndIt() {
        val talk = at(1000)
        val gap = at(WakeConfig.SILENCE_MS / 2)
        assertEquals(
            Endpoint.Verdict.Over to talk + gap + talk + at(WakeConfig.SILENCE_MS),
            run(true to talk, false to gap, true to talk, false to 2000),
        )
    }

    @Test fun aLongAskIsCut() {
        assertEquals(Endpoint.Verdict.Over to at(WakeConfig.MAX_MS), run(true to 20_000))
    }
}
