package com.bleural.croissant.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseTest {
    @Test fun takesThePhraseOffTheFront() {
        assertEquals("start a timer for 10 minutes.", WakePhrase.strip("Hey Muffin, start a timer for 10 minutes."))
        assertEquals("what's up?", WakePhrase.strip("hey, muffin. what's up?"))
        assertEquals("", WakePhrase.strip("Hey muffin!"))
        assertEquals("", WakePhrase.strip(" Hey, Muffin. "))
    }

    @Test fun leavesEverythingElse() {
        assertEquals("buy muffins", WakePhrase.strip("buy muffins"))
        assertEquals("Hey there", WakePhrase.strip("Hey there"))
    }
}
