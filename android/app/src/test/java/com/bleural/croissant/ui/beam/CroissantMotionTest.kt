package com.bleural.croissant.ui.beam

import org.junit.Assert.assertEquals
import org.junit.Test

/** Idle v3, played from its moves, against reference values for the same frames (2026-10-27). */
class CroissantMotionTest {
    private fun at(frame: Float) = IdleV3.rig().also { it.advance(frame.toDouble()) }
    private fun pose(frame: Float) = at(frame).pose()
    private fun eyes(frame: Float, down: Float = 0f) = at(frame).pose(Gazes.DOWN, down).let { listOf(it.eyeX, it.eyeY, it.eyeScaleX, it.eyeScaleY) }

    private fun near(want: List<Float>, got: List<Float>, frame: Float) =
        want.zip(got).forEach { (w, g) -> assertEquals("frame $frame: want $want, got $got", w, g, 0.01f) }

    @Test fun eyesDartAndOvershoot() {
        near(listOf(-5.15f, -4.12f, .835f, .897f), eyes(32f), 32f)   // up-left, just past
        near(listOf(-5f, -4f, .84f, .9f), eyes(40f), 40f)            // up-left, settled
        assertEquals(5.87f, pose(87f).eyeX, 0.01f)                   // up-right, just past
        near(listOf(0f, 5f, 1f, .84f), eyes(191f), 191f)             // down
    }

    @Test fun blinksCloseInThreeFrames() {
        near(listOf(1.48f, -1.19f, .952f, .078f), eyes(139f), 139f)  // shut while darting back
        assertEquals(.08f, pose(140f).eyeScaleY, 0.01f)
        assertEquals(.964f, pose(307f).eyeScaleY, 0.01f)             // the lone blink starting
        assertEquals(.08f, pose(309f).eyeScaleY, 0.01f)
    }

    @Test fun hoversAndRocks() {
        assertEquals(-4f, pose(45f).hoverY, 0.001f)
        assertEquals(4f, pose(135f).hoverY, 0.001f)
        assertEquals(1f, pose(0f).sideL, 0.001f)
        assertEquals(-1f, pose(180f).sideL, 0.001f)
        assertEquals(-7f - 2f, pose(275f).earL, 0.05f)              // the twitch on the sway's open end
    }

    @Test fun loopsWithoutASeam() {
        assertEquals(pose(0f), pose(IdleV3.FRAMES))
        assertEquals(pose(32f), pose(IdleV3.FRAMES * 3 + 32f))       // the second loop and on are the first
        val a = pose(359.9f); val b = pose(0f)
        assertEquals(b.hoverY, a.hoverY, 0.05f)
        assertEquals(b.earL, a.earL, 0.01f)
    }

    @Test fun looksDownForTheDay() {
        near(listOf(0f, 5f, 1f, .84f), eyes(60f, down = 1f), 60f)    // the loop has them up-left there
        near(listOf(0f, 5f, 1f, .84f * .08f), eyes(140f, down = 1f), 140f) // and still blinks
    }
}
