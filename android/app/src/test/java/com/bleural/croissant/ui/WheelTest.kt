package com.bleural.croissant.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The wheel after a step back (ui/Nav.kt, Wheel.hush): the rest of the spin that took the
 * step is not heard by the page it lands on, and a wheel that has been still is.
 */
class WheelTest {
    // Wheel is one object for the app, so the clock is one for the class and only goes forward.
    private companion object { var now = 0L }
    private var heard = 0
    private val handler: (Int) -> Boolean = { heard++; false }

    @Before fun setUp() { now += 60_000; Wheel.clock = { now }; Wheel.push(handler) }
    @After fun tearDown() { Wheel.remove(handler) }

    /** A tick [ms] after the last one. */
    private fun tick(ms: Long) { now += ms; Wheel.tick(-1) }

    @Test fun theTailOfTheSpinIsSwallowedHoweverLongItRuns() {
        tick(1_000)
        assertEquals(1, heard)
        Wheel.hush()
        repeat(20) { tick(120) }        // a long spin, each tick well inside the quiet
        assertEquals(1, heard)
        tick(500)                       // the wheel was still: a new turn is heard
        assertEquals(2, heard)
        tick(120)
        assertEquals(3, heard)
    }

    @Test fun aStillWheelIsHeardStraightAway() {
        Wheel.hush()
        tick(500)
        assertEquals(1, heard)
    }
}
