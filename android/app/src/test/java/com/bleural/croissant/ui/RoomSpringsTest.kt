package com.bleural.croissant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The grammar of the room's motion, not its constants.
 * The four springs are the feel of the whole shell and get retuned by hand on
 * the panel, so nothing here pins a stiffness or a damping. What it pins is what the tuning
 * is for: a tick is quick and arrives with a little life, opening is quick and dead still,
 * Home is slower than either and never bounces. A retune that keeps the grammar passes;
 * one that turns opening into a wobble or Home into a snap does not.
 */
class RoomSpringsTest {
    private fun room(): RoomState = RoomState().apply {
        leaveHome()
        var f = 0
        while (!settled && f++ < 900) advance(FRAME)
    }

    /** The last frame on which [read] was still further than [near] from [target]. */
    private fun RoomState.framesToHold(near: Float, target: Float, read: (RoomState) -> Float): Int {
        var last = 0
        for (f in 1..LIMIT) {
            advance(FRAME)
            if (abs(read(this) - target) >= near) last = f
        }
        return last + 1
    }

    @Test fun aWheelTickIsQuickAndArrivesWithALittleLife() {
        val r = room()
        r.step(1)
        var peak = 0f
        var last = 0
        for (f in 1..LIMIT) {
            r.advance(FRAME)
            peak = maxOf(peak, r.pos)
            if (abs(r.pos - 1f) >= 0.01f) last = f
        }
        val ms = (last + 1) * 1000 / 60
        assertTrue("a tick took $ms ms to come to rest, which is not one tick one panel", ms in 180..450)
        // The overshoot is the beat that makes a tick feel like a detent rather than a
        // slide. It has to be there, and it has to stay small enough not to read as a bounce.
        assertTrue("the tick landed dead, with no overshoot at all", peak > 1f)
        assertTrue("the tick overshot by ${peak - 1f} of a panel, which is a bounce", peak - 1f < 0.05f)
    }

    @Test fun openingIsQuickAndDeadStill() {
        val r = room()
        r.openPanel()
        var readable = -1
        var peak = 0f
        for (f in 1..LIMIT) {
            r.advance(FRAME)
            peak = maxOf(peak, r.open)
            // The moment the opened screen is wholly there, which is what the eye calls
            // open: the ground is dark enough by then for the panel to be read on.
            if (readable < 0 && panelFade(r.open) >= 0.999f) readable = f
        }
        val ms = readable * 1000 / 60
        assertTrue("the opened screen took $ms ms to arrive", readable > 0 && ms in 120..330)
        // Critically damped on purpose: a wobble here takes the ground past black and back,
        // which on this panel reads as a flash behind the screen that just arrived.
        assertTrue("open overshot to $peak", peak <= 1f + 1e-6f)
    }

    @Test fun homeIsASlowerBlendThanAPanelAndNeverGoesPastIt() {
        val panel = room().let { it.step(1); it.framesToHold(0.01f, 1f) { r -> r.pos } }
        val r = RoomState()          // on Home, where the app starts
        r.leaveHome()
        var low = 1f
        var last = 0
        for (f in 1..LIMIT) {
            r.advance(FRAME)
            low = minOf(low, r.home)
            if (abs(r.home) >= 0.01f) last = f
        }
        // Home is a change of place, not a change of selection, so it must not feel like a
        // tick: slower, and with none of the tick's beat.
        assertTrue("Home took $last frames and a panel took $panel; Home should be the slower", last > panel)
        assertTrue("Home went past the room to $low and came back", low >= -1e-6f)
    }

    @Test fun theElasticSnapsBackWithoutBouncingPastTheEdge() {
        val r = RoomState()
        r.leaveHome()
        // Stretched by a finger and let go, which is the only thing that makes a pull now
        // that the wheel's flash is gone (RoomStateTest).
        r.dragStart(); r.dragTo(-0.2f, PANEL_PX); r.dragEnd(from = 0, velocity = 0f)
        var low = 1f
        for (f in 1..LIMIT) {
            r.advance(FRAME)
            low = minOf(low, r.pull)
        }
        // A negative pull would draw the curve inside out, bulging up off the top edge.
        assertTrue("the elastic bounced to $low", low >= 0f)
        assertTrue(r.settled)
    }

    /**
     * The headers flash through a spin unless it reads as one, from its second tick; a single
     * tick must not, and the name a spin lands on must be up as soon as a single tick's is.
     */
    @Test fun oneTickIsCalmAndASpinIsARushThatEndsAsItLands() {
        val one = room()
        one.step(1)
        var calm = 0f
        var oneUp = -1
        for (f in 1..LIMIT) {
            one.advance(FRAME)
            calm = maxOf(calm, one.rush)
            if (oneUp < 0 && one.headAlpha(1) >= 0.95f && abs(one.pos - 1f) < 0.02f) oneUp = f
        }
        assertTrue("one tick read as a rush of $calm", calm == 0f)
        assertTrue("the room never parked after one tick", one.settled)

        val spin = room()
        var rushed = 1f
        var spinUp = -1
        for (f in 1..LIMIT) {
            if (f <= 97 && f % 12 == 1) spin.step(1)
            spin.advance(FRAME)
            if (f in 30..97) rushed = minOf(rushed, spin.rush)
            if (f > 97 && spinUp < 0 && spin.headAlpha(9) >= 0.95f && abs(spin.pos - 9f) < 0.02f) spinUp = f - 97
        }
        assertTrue("five ticks a second let the rush fall to $rushed mid spin", rushed > 0.95f)
        assertTrue("the landed name was up ${spinUp * 1000 / 60} ms after the last tick, a single tick's in ${oneUp * 1000 / 60}", spinUp in 1..oneUp + 3)
        assertTrue("the rush never wore off, so the room never parks", spin.rush == 0f && spin.settled)
    }

    @Test fun theOpenedScreenArrivesInTheBackHalfOfTheOpening() {
        assertEquals(0f, panelFade(0f), 0f)
        assertEquals(0f, panelFade(0.35f), 0f)      // nothing until the ground is dark enough to read on
        assertEquals(0.5f, panelFade(0.6f), 1e-3f)
        assertEquals(1f, panelFade(0.85f), 0f)      // and wholly there before the opening ends
        assertEquals(1f, panelFade(1f), 0f)
        var last = -1f
        var x = 0f
        while (x <= 1f) {
            val v = panelFade(x)
            assertTrue("panelFade went backwards at $x", v >= last)
            last = v
            x += 0.01f
        }
    }

    @Test fun theEaseIsFlatAtBothEnds() {
        assertEquals(0f, smooth(0f), 0f)
        assertEquals(1f, smooth(1f), 0f)
        assertEquals(0.5f, smooth(0.5f), 1e-6f)
        // Flat at the ends is what stops a tile setting off and arriving with a jerk.
        assertTrue(smooth(0.05f) < 0.05f)
        assertTrue(smooth(0.95f) > 0.95f)
        var x = 0f
        while (x <= 1f) {
            assertTrue("the ease left 0..1 at $x", smooth(x) in 0f..1f)
            x += 0.01f
        }
    }

    private companion object {
        const val FRAME = 1f / 60f
        const val LIMIT = 400
    }
}
