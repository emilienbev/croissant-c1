package com.bleural.croissant.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The elastic at the top (`ui/Pull.kt`, PullState): what a finger past the first
 * item does, and when the step back is taken. The list and the curve are Compose, but the
 * rule is not: the connection takes plain offsets and the springs take a frame at a time,
 * so the whole gesture can be played out here.
 *
 * `unit` is 1 on the R1, where the density override makes one panel pixel one device
 * pixel, so the pixels below are the design's own.
 */
class PullStateTest {
    private var steps = 0

    private fun pull(): PullState = PullState(1f) { steps++ }

    /** The finger, dragging [dy] pixels down past the top, as the list's overscroll reports it. */
    private fun PullState.drag(dy: Float): Offset =
        connection.onPostScroll(Offset.Zero, Offset(0f, dy), NestedScrollSource.UserInput)

    /** The finger pushing back up, which the connection takes before the list scrolls again. */
    private fun PullState.push(dy: Float): Offset =
        connection.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput)

    /** Letting go, which is where a pull is answered. */
    private fun PullState.release(): Velocity = runBlocking { connection.onPreFling(Velocity.Zero) }

    private fun PullState.settle(limit: Int = 900) {
        var frames = 0
        while (!settled) {
            if (frames++ > limit) fail("the elastic never came back to rest")
            advance(1f / 60f)
        }
    }

    @Test fun theContentStretchesLessTheFurtherItIsPulled() {
        // ~~A flat quarter of the drag~~ until 2026-09-18, when the band replaced it
        // (Primitives.kt, backPull): the stretch is `1 - exp(-px / 440)` of LIFT_FULL, so
        // the second hundred pixels of finger buy less than the first.
        val p = pull()
        p.drag(100f)
        assertEquals(22.36262f, p.lift(), 1e-3f)
        p.drag(100f)
        assertEquals(40.17899f, p.lift(), 1e-3f)
    }

    @Test fun theOverscrollIsTakenWholeSoThePlatformsOwnStretchNeverSeesIt() {
        val p = pull()
        assertEquals(Offset(0f, 40f), p.drag(40f))
    }

    @Test fun pushingBackUpGivesTheStretchBack() {
        val p = pull()
        p.drag(100f)
        assertEquals(Offset(0f, -40f), p.push(-40f))
        assertEquals(14.02222f, p.lift(), 1e-3f)   // 60 px of finger left on the band
        // And it gives back no more than it took: the list scrolls with the rest.
        assertEquals(Offset(0f, -60f), p.push(-100f))
        assertEquals(0f, p.lift(), 1e-3f)
    }

    @Test fun theStretchNeverReachesTheFullLift() {
        val p = pull()
        p.drag(2000f)       // the finger runs off the panel and keeps going
        // The band has no end, it only gets harder, so LIFT_FULL is approached and never
        // arrived at. ~~A quarter of the full 266.7 px~~ before the band, 2026-09-18.
        assertEquals(108.83231f, p.lift(), 1e-2f)
        assertTrue("the stretch passed LIFT_FULL at ${p.lift()}", p.lift() < LIFT_FULL)
    }

    @Test fun aDragPastAThirdTakesTheStepBack() {
        val p = pull()
        p.drag(200f)        // a third of a full pull is 176 px of finger on the band
        p.release()
        assertEquals(1, steps)
    }

    @Test fun theThresholdIsAThirdOfAFullPull() {
        // The number tuned by feel on the device, read off the band rather than off the finger:
        // DOT_TWO lands at 176.2 px of travel, twice what it took before the band
        // (Primitives.kt, backPull). ~~88 px is not yet the step back, 90 is.~~
        val under = pull()
        under.drag(176f)
        under.release()
        assertEquals(0, steps)
        steps = 0
        val over = pull()
        over.drag(177f)
        over.release()
        assertEquals(1, steps)
    }

    @Test fun aDragUnderAThirdSpringsBackInstead() {
        val p = pull()
        p.drag(60f)
        p.release()
        assertEquals(0, steps)
        p.settle()
        assertEquals(0f, p.lift(), 0f)
    }

    @Test fun aCompletedPullFiresOnceAndComesBackToRest() {
        val p = pull()
        repeat(5) { p.drag(60f) }   // well past full
        p.release()
        assertEquals(1, steps)
        p.settle()
        assertEquals(0f, p.lift(), 0f)
        assertTrue(p.settled)
    }

    @Test fun lettingGoWithNoPullAnswersNothing() {
        val p = pull()
        p.release()
        assertEquals(0, steps)
        assertTrue(p.settled)
    }

    @Test fun aPullThatWasGivenBackDoesNotFire() {
        val p = pull()
        p.drag(120f)
        p.push(-120f)
        p.release()
        assertEquals(0, steps)
    }

    /**
     * ~~theWheelsFlashTakesTheStepAtOnceAndDecays~~: one tick was the whole step until
     * 2026-09-18, when a spin was wanted rather than a pair of taps. Now a tick
     * adds [TICK] to the pull and [TICKS_BACK] of them reach [DOT_TWO], which is the same
     * gauge the two dots draw, so the wheel has to ask as far as a finger does. The tail
     * of the spin is the wheel's business since 2026-09-22 (WheelTest).
     */
    @Test fun theWheelTakesAWholeSpinToStepBack() {
        val p = pull()
        repeat(TICKS_BACK - 1) { p.stepBack() }
        assertEquals("the step was taken before the gauge was full", 0, steps)
        assertEquals(0f, p.lift(), 0f)   // and no tick has stretched the content yet
        assertFalse(p.settled)           // the dots are up, on their way to the second
        p.stepBack()
        assertEquals(1, steps)
        p.settle()
        assertTrue(p.settled)
    }

    @Test fun onlyAFingerDrivesIt() {
        val p = pull()
        // A fling's own leftover is not a pull: the elastic is a gesture, not an overscroll.
        p.connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.SideEffect)
        assertEquals(0f, p.lift(), 0f)
        p.release()
        assertEquals(0, steps)
    }
}
