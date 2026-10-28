package com.bleural.croissant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The room's four numbers and their springs. All of
 * it runs on the Mac: [RoomState] is a plain class, and the frame loop that drives it is
 * one call to [RoomState.advance] a frame, so the whole of what the wheel, the drag and
 * the side button do to the room can be played out here without a device.
 */
class RoomStateTest {
    /** One frame of [RoomScreen]'s loop, at the 60 Hz the panel runs at. */
    private fun RoomState.frame(dt: Float = 1f / 60f) = advance(minOf(dt, LOOP_CLAMP))

    /** Runs the loop until nothing is moving, and says how many frames that took. */
    private fun RoomState.settle(limit: Int = 900): Int {
        var frames = 0
        while (!settled) {
            if (frames++ > limit) fail("the room never settled: pos $pos open $open home $home pull $pull")
            frame()
        }
        return frames
    }

    /** The room, left on its panels rather than on Home, which is where it starts. */
    private fun room(): RoomState = RoomState().apply { leaveHome(); settle() }

    @Test fun springsSettleExactlyOnTheirTargets() {
        val r = room()
        r.goTo(3)
        r.openPanel()
        r.settle()
        assertTrue(r.settled)
        // Exactly, not nearly: advance snaps each number onto its target as it arrives, so
        // a settled room holds still instead of creeping, and the ground stops blending.
        assertEquals(3f, r.pos, 0f)
        assertEquals(1f, r.open, 0f)
        assertEquals(0f, r.home, 0f)
        assertEquals(0f, r.pull, 0f)
    }

    @Test fun goToClampsAtBothEndsAndNeverWraps() {
        val r = room()
        r.goTo(-3)
        assertEquals(0f, r.posTarget, 0f)
        r.goTo(99)
        assertEquals(r.count - 1f, r.posTarget, 0f)
        r.settle()
        r.step(1)
        assertEquals(r.count - 1f, r.posTarget, 0f)   // the last panel nudges and stays
        r.goTo(0)
        r.settle()
        r.step(-1)
        assertEquals(0f, r.posTarget, 0f)   // and the first does not fall off the top
    }

    @Test fun ticksRetargetInsteadOfQueueing() {
        val alone = room().let { it.step(1); it.settle() }
        val r = room()
        r.step(1); r.frame(); r.frame()
        r.step(1); r.frame(); r.frame()
        r.step(1)
        assertEquals(3f, r.posTarget, 0f)   // three ticks are three panels, not three animations
        val hurried = r.settle() + 4
        assertEquals(3f, r.pos, 0f)
        assertTrue(
            "a hurried spin took $hurried frames; three queued moves would take about ${3 * alone}",
            hurried < 3 * alone,
        )
    }

    @Test fun theFingerOwnsThePanelWhileItDrags() {
        val r = room()
        r.dragStart()
        r.dragTo(2f, PANEL_PX)
        r.frame(); r.frame()
        assertEquals(2f, r.pos, 0f)   // the spring is off: the target is still 0 and must not pull it back
        assertFalse(r.settled)        // and a room under a finger is never settled
    }

    @Test fun theDragStretchesAboveTheFirstPanel() {
        val r = room()
        r.dragStart()
        // ~~A flat 2.4 of pull per panel dragged, clamped at 1~~ until 2026-09-18, when
        // the room started asking the same rubber band every list asks (Primitives.kt,
        // backPull). The band stiffens instead of clamping, so the numbers below are what
        // `1 - exp(-px / 440)` gives at a 640 px panel, and the stretch is read off it.
        r.dragTo(-0.2f, PANEL_PX)
        assertEquals(0.252416f, r.pull, 1e-5f)
        assertEquals(-0.043384f, r.pos, 1e-5f)   // the pull's share of LIFT_FULL
        r.dragTo(-1f, PANEL_PX)
        assertEquals(0.766494f, r.pull, 1e-5f)
        assertEquals(-0.131741f, r.pos, 1e-5f)
        // A whole panel of finger is not a whole pull, and nothing the thumb can do makes
        // it one: the band has no end, it only gets harder.
        r.dragTo(-4f, PANEL_PX)
        assertTrue("the band reached ${r.pull}", r.pull < 1f)
    }

    @Test fun aFlingMovesAtMostOnePanel() {
        val r = room()
        r.goTo(3)
        r.settle()
        r.dragStart(); r.dragTo(3.4f, PANEL_PX)
        r.dragEnd(from = 3, velocity = 40f)      // panels a second, harder than a thumb can
        assertEquals(4f, r.posTarget, 0f)
        r.settle()
        r.dragStart(); r.dragTo(3.6f, PANEL_PX)
        r.dragEnd(from = 4, velocity = -40f)
        assertEquals(3f, r.posTarget, 0f)
        r.goTo(0); r.settle()
        r.dragStart(); r.dragTo(0.1f, PANEL_PX)
        r.dragEnd(from = 0, velocity = -40f)
        assertEquals(0f, r.posTarget, 0f)        // and never wraps round to the last
    }

    @Test fun aPullPastAThirdGoesHome() {
        val r = room()
        r.dragStart(); r.dragTo(-0.35f, PANEL_PX)  // pull 0.399, past DOT_TWO
        r.dragEnd(from = 0, velocity = 0f)
        assertEquals(1f, r.homeTarget, 0f)
        r.settle()
        assertEquals(1f, r.home, 0f)
        assertEquals(0f, r.pull, 0f)
    }

    @Test fun aPullUnderAThirdSpringsBack() {
        val r = room()
        r.dragStart(); r.dragTo(-0.1f, PANEL_PX)   // pull 0.135, short of it
        r.dragEnd(from = 0, velocity = 0f)
        assertEquals(0f, r.homeTarget, 0f)
        r.settle()
        assertEquals(0f, r.pos, 0f)
        assertEquals(0f, r.pull, 0f)
    }

    @Test fun theSideButtonCutsRatherThanQueues() {
        val r = RoomState()
        r.leaveHome(); r.goTo(6); r.openPanel()
        r.frame(); r.frame()
        assertFalse(r.settled)
        r.cutToAsk()
        // Cut, never queue: the numbers are where they were going, on the frame of the hold.
        assertTrue(r.settled)
        assertEquals(0f, r.pos, 0f)
        assertEquals(1f, r.open, 0f)
        assertEquals(0f, r.home, 0f)
        assertEquals(0f, r.pull, 0f)
    }

    @Test fun aDragPlacesTheRoomWhereItIsToldAbsolutely() {
        val r = room()
        r.goTo(3)
        repeat(3) { r.frame() }
        val flying = r.pos
        assertTrue("the room should still be well short of 3 here, it was at $flying", flying < 1f)
        r.dragStart()
        // What RoomScreen's gesture passes on the first moved frame: its `startPos` is the
        // target it read at touch-down, not where the room had got to.
        r.dragTo(r.posTarget, PANEL_PX)
        assertEquals(3f, r.pos, 0f)
    }

    @Test fun theCutReleasesAFingerThatWasDragging() {
        val r = room()
        r.dragStart()
        r.dragTo(2.5f, PANEL_PX)       // a finger is moving the room
        r.cutToAsk()         // and the side button is held while it is down
        assertEquals(0f, r.pos, 0f)
        assertEquals(1f, r.open, 0f)
        // The finger must not still own the room: the drag under way goes on writing pos
        // behind the opened Ask panel, and the release retargets it somewhere else again.
        assertFalse("the drag survived the cut", r.dragging)
        assertTrue(r.settled)
    }

    @Test fun leavingHomeLandsOnThePanelItWasLeftOn() {
        val r = room()
        r.goTo(5)
        r.settle()
        r.goHome()
        r.settle()
        assertEquals(0f, r.open, 0f)          // Home closes whatever was open
        assertEquals(Feature.entries[5], r.last)
        r.leaveHome()
        r.settle()
        assertEquals(5f, r.pos, 0f)
    }

    @Test fun sleepSnapsWhateverIsRunning() {
        val r = RoomState()
        r.leaveHome()
        // A stretch that has been let go, so the band is on its way back when sleep lands.
        r.dragStart(); r.dragTo(-0.1f, PANEL_PX); r.dragEnd(from = 0, velocity = 0f)
        r.goTo(4); r.openPanel()
        repeat(3) { r.frame() }
        assertFalse(r.settled)
        r.snap()
        assertTrue(r.settled)
        assertEquals(4f, r.pos, 0f)
        assertEquals(1f, r.open, 0f)
        assertEquals(0f, r.home, 0f)
        assertEquals(0f, r.pull, 0f)   // the band was on its way back, so back is where it lands
    }

    @Test fun onlyTheFocusedPanelHasAnOpenedPose() {
        val r = room()
        r.goTo(2); r.settle()
        r.openPanel(); r.settle()
        assertEquals(1f, r.openOf(2), 0f)
        assertEquals(0f, r.openOf(1), 0f)
        assertEquals(0f, r.openOf(3), 0f)
    }

    // ~~theWheelsFlashDecays~~ removed 2026-09-18 with `flashPull` itself: the wheel used
    // to flash the two dots on the way out of Ask, and the dots came off that one
    // crossing, so a tick back at the first panel goes Home outright (RoomState's own
    // doc). Nothing flashes any more.

    @Test fun scrubbingBackParksTheSpring() {
        val r = room()
        r.openPanel(); r.settle()
        r.scrubOpen(0.4f)
        assertEquals(0.4f, r.open, 0f)
        assertTrue(r.settled)   // the finger owns the number: nothing springs while it holds
        r.frame()
        assertEquals(0.4f, r.open, 0f)
    }

    @Test fun focusedIsWhereTheRoomIsAndLastWhereItIsGoing() {
        val r = room()
        r.goTo(5)
        assertEquals(Feature.entries[5], r.last)      // the tick lands on the target at once
        assertEquals(Feature.entries[0], r.focused)   // while the room is still on its way
        r.settle()
        assertEquals(Feature.entries[5], r.focused)
    }

    @Test fun theWorstFrameTheLoopAllowsStillConverges() {
        // The loop clamps dt at 32 ms, so that is the largest step the springs ever
        // integrate. Explicit integration can fly apart on a step that size; this says the
        // room's stiffest spring does not.
        val r = room()
        r.goTo(7)
        repeat(20) { r.advance(LOOP_CLAMP) }
        r.settle()
        assertEquals(7f, r.pos, 0f)
    }

    private companion object {
        /** RoomScreen's own clamp on a frame, in seconds. */
        const val LOOP_CLAMP = 0.032f
    }
}

/**
 * The panel's own height in panel pixels, which is what [RoomState.dragTo] needs to turn
 * a fraction of a panel back into the finger's travel: `size.height / u` in RoomScreen's
 * gesture, and the canvas is 480x640 (AGENTS.md).
 */
internal const val PANEL_PX = 640f
