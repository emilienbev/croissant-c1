package com.bleural.croissant.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpeechGateTest {
    private val rate = 16000

    /** [seconds] of a tone at [amp] (full scale 1.0) over noise at [noise]. */
    private fun clip(seconds: Double, amp: Double, noise: Double, toneFrom: Double = 0.0, toneTo: Double = seconds): ShortArray {
        val r = Random(7)
        return ShortArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            val tone = if (t in toneFrom..toneTo) amp * sin(2 * PI * 220 * t) else 0.0
            ((tone + noise * (r.nextDouble() * 2 - 1)) * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test fun digitalSilenceIsNotSpeech() {
        val l = SpeechGate.levels(ShortArray(32000))
        assertEquals(Double.NEGATIVE_INFINITY, l.active, 0.0)
        assertFalse(l.speech)
    }

    @Test fun roomToneAloneIsNotSpeech() {
        // About -64 dBFS of noise throughout, as the ring's pre-roll measures.
        assertFalse(SpeechGate.speech(clip(2.0, 0.0, 0.001)))
    }

    @Test fun wordsOverRoomToneAreSpeech() {
        // A second of "voice" at about -23 dBFS inside three seconds of room tone.
        val l = SpeechGate.levels(clip(3.0, 0.1, 0.001, toneFrom = 1.0, toneTo = 2.0))
        assertTrue(l.active > -30 && l.active - l.floor > 30)
        assertTrue(l.speech)
    }

    @Test fun loudNoiseThroughoutIsNotSpeech() {
        // Loud enough, but no quieter frames to stand out from: a fan, not a voice.
        assertFalse(SpeechGate.speech(clip(2.0, 0.0, 0.1)))
    }

    @Test fun aWhisperUnderTheFloorIsNotSpeech() {
        assertFalse(SpeechGate.speech(clip(3.0, 0.002, 0.0002, toneFrom = 1.0, toneTo = 2.0)))
    }

    @Test fun tooShortToSplitUsesTheWholeClip() {
        val l = SpeechGate.levels(clip(0.05, 0.1, 0.0))
        assertEquals(l.active, l.floor, 0.0)
        assertFalse(l.speech)
    }
}
