package com.bleural.croissant.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointTest {
    private val quiet = -70f
    private val voice = -30f

    /** The verdict after a run of 20 ms chunks at these levels, the room quiet before the wake. */
    private fun run(vararg spans: Pair<Float, Int>): Pair<Endpoint.Verdict, Int> {
        val e = Endpoint()
        repeat(100) { e.track(quiet) }
        e.start()
        var ms = 0
        for ((db, len) in spans) repeat(len / 20) {
            ms += 20
            val v = e.offer(db, 20)
            if (v != Endpoint.Verdict.Going) return v to ms
        }
        return Endpoint.Verdict.Going to ms
    }

    @Test fun endsAfterTheQuietThatFollowsTheAsk() {
        assertEquals(Endpoint.Verdict.Over to 2300, run(voice to 1500, quiet to 2000))
    }

    @Test fun nothingSaidAfterThePhraseGoesBackQuietly() {
        assertEquals(Endpoint.Verdict.Nothing to WakeConfig.NO_SPEECH_MS, run(quiet to 5000))
    }

    @Test fun thePhraseTailIsNotTheAsk() {
        assertEquals(Endpoint.Verdict.Nothing to WakeConfig.NO_SPEECH_MS, run(voice to WakeConfig.GRACE_MS, quiet to 5000))
    }

    @Test fun aPauseBeforeTheAskIsFine() {
        assertEquals(Endpoint.Verdict.Over to 3400, run(quiet to 1000, voice to 1600, quiet to 2000))
    }

    @Test fun aLongAskIsCut() {
        assertEquals(Endpoint.Verdict.Over to WakeConfig.MAX_MS, run(voice to 20_000))
    }

    @Test fun aSteadyLoudRoomIsTheFloorNotSpeech() {
        val e = Endpoint()
        repeat(4000) { e.track(-40f) }
        e.start()
        var v = Endpoint.Verdict.Going
        var ms = 0
        while (v == Endpoint.Verdict.Going) { v = e.offer(-40f, 20); ms += 20 }
        assertEquals(Endpoint.Verdict.Nothing to WakeConfig.NO_SPEECH_MS, v to ms)
    }
}
