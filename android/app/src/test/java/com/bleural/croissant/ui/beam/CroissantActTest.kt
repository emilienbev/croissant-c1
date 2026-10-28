package com.bleural.croissant.ui.beam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ask acts and the alert over idle v3, against reference values for the same frames
 * (2026-10-28): each act started on the idle's frame 0.
 */
class CroissantActTest {
    private fun rig(act: CroissantAct, frames: Int, letGoAt: Int? = null) = IdleV3.rig().apply {
        perform(act)
        if (letGoAt == null) advance(frames.toDouble())
        else { advance(letGoAt.toDouble()); letGo(act); advance((frames - letGoAt).toDouble()) }
    }

    /** The pose in a fixed order: hover, squash, sides, left ear (turn, stretch, drop), right ear (turn, stretch), eyes. */
    private fun values(p: CroissantPose) = listOf(
        p.hoverY, p.squash, p.sideL, p.sideR, p.earL, p.earLStretch, p.earLDown, p.earR, p.earRStretch,
        p.eyeX, p.eyeY, p.eyeScaleX, p.eyeScaleY,
    )

    private fun near(act: CroissantAct, frame: Int, want: List<Float>, letGoAt: Int? = null) {
        val got = values(rig(act, frame, letGoAt).pose())
        want.zip(got).forEachIndexed { i, (w, g) -> assertEquals("${act.name} frame $frame value $i: want $want, got $got", w, g, 0.01f) }
    }

    @Test fun listensWithItsEarsUp() {
        near(AskActs.listening, 3, listOf(-.321f, 1.036f, .499f, .399f, -12.516f, 1.063f, 12.61f, -15.097f, 1.063f, 1.359f, -2.175f, 1.094f, 1.069f))
        near(AskActs.listening, 8, listOf(-2.553f, 1.04f, 0f, 0f, -30f, 1.07f, 14f, -32.993f, 1.07f, 2.5f, -4f, 1.053f, 1.008f))
        near(AskActs.listening, 40, listOf(-3f, 1.025f, 0f, 0f, -27f, 1.05f, 14f, -30f, 1.05f, 2.5f, -4f, 1.053f, 1.008f))
        near(AskActs.listening, 64, listOf(-3f, 1.025f, 0f, 0f, -30f, 1.05f, 14f, -30f, 1.05f, 2.5f, -4f, 1.053f, 1.008f))
        near(AskActs.listening, 200, listOf(-3f, 1.025f, 0f, 0f, -27f, 1.05f, 14f, -30f, 1.05f, 2.5f, -4f, 1.053f, 1.008f)) // round the loop
    }

    @Test fun listeningFadesOutWhenLetGo() {
        near(AskActs.listening, 160, listOf(1.995f, 1.002f, -.848f, -.678f, -1.288f, 1.005f, 1.386f, -4.079f, 1.005f, .248f, -.396f, 1.006f, 1.002f), letGoAt = 150)
        near(AskActs.listening, 170, listOf(1.373f, 1f, -.985f, -.788f, 1.881f, 1f, 0f, -1.505f, 1f, 0f, 0f, 1f, 1f), letGoAt = 150)
    }

    @Test fun thinksLookingSideToSide() {
        near(AskActs.thinking, 2, listOf(-.288f, 1f, .999f, .8f, 1.995f, 1f, 0f, -1.596f, 1f, -1.913f, -1.674f, .943f, .962f))
        near(AskActs.thinking, 30, listOf(-3.445f, 1f, 1.781f, 1.412f, 1.008f, 1f, 0f, -.806f, 1f, -4f, -3.5f, .88f, .92f))
        near(AskActs.thinking, 53, listOf(-3.85f, 1f, 2.001f, 1.581f, -.557f, 1f, 0f, .445f, 1f, 4.773f, -3.5f, .88f, .92f))
        near(AskActs.thinking, 131, listOf(3.97f, 1f, -1.732f, -1.37f, -7.281f, 1.05f, 0f, .225f, 1f, -4f, -3.5f, .88f, .92f))
    }

    @Test fun answersWithTheVoice() {
        near(AskActs.answering, 4, listOf(-.57f, 1f, .998f, .798f, 1.981f, 1f, 0f, -1.585f, 1f, .375f, -.5f, .99f, .988f))
        near(AskActs.answering, 31, listOf(-7.516f, 1.035f, .859f, .687f, -4.053f, 1f, 0f, -4.757f, 1f, 1.5f, -2f, .96f, .95f))
        near(AskActs.answering, 82, listOf(-5.112f, 1.035f, .141f, .112f, -6.924f, 1f, 0f, -2.461f, 1f, 1.5f, -2f, .96f, .95f))
    }

    @Test fun nodsWhenTheAnswerIsDone() {
        near(AskActs.answering, 205, listOf(-.208f, .971f, -.908f, -.726f, 4.013f, 1f, 0f, .538f, 1f, 1.072f, 1.254f, .971f, .917f), letGoAt = 200)
        near(AskActs.answering, 215, listOf(-4.289f, 1.01f, -.822f, -.657f, 1.938f, 1f, 0f, 1.575f, 1f, 0f, 1.124f, 1f, .963f), letGoAt = 200)
        near(AskActs.answering, 240, listOf(-3.445f, 1f, -.504f, -.403f, -1.008f, 1f, 0f, .806f, 1f, 0f, 5f, 1f, .84f), letGoAt = 200)
    }

    @Test fun ringsJoltingWithItsEarsUp() {
        near(AlertActs.ringing, 3, listOf(-.43f, 1f, .999f, .799f, -23.979f, 1.058f, 13.459f, -26.912f, 1.058f, 0f, 0f, 1.326f, 1.326f))
        near(AlertActs.ringing, 9, listOf(-11.08f, 1.027f, 2.988f, -.81f, -27.447f, 1.06f, 14f, -29.553f, 1.06f, 0f, 0f, 1.3f, 1.3f))
        near(AlertActs.ringing, 11, listOf(-11.5f, 1.06f, .004f, 1.568f, -30f, 1.06f, 14f, -27f, 1.06f, 0f, 0f, 1.3f, 1.3f))
        near(AlertActs.ringing, 40, listOf(-3.949f, 1f, .769f, .615f, -27f, 1.06f, 14f, -30f, 1.06f, 0f, 0f, 1.3f, 1.3f))
        near(AlertActs.ringing, 62, listOf(-3.291f, 1f, .473f, .379f, -27f, 1.06f, 14f, -30f, 1.06f, 0f, 0f, 1.3f, .943f)) // the double blink
        near(AlertActs.ringing, 129, listOf(-5.915f, 1.027f, 1.367f, -2.107f, -27.447f, 1.06f, 14f, -29.553f, 1.06f, 0f, 0f, 1.3f, 1.3f)) // the next jolt
        near(AlertActs.ringing, 250, listOf(-12.53f, 1.055f, 1.133f, -1.459f, -29.553f, 1.06f, 14f, -27.447f, 1.06f, 0f, 0f, 1.3f, 1.3f))
    }

    @Test fun ringingFadesOutWhenLetGo() {
        near(AlertActs.ringing, 160, listOf(2.544f, 1f, -.941f, -.753f, -8.426f, 1.021f, 4.888f, -11.275f, 1.021f, 0f, 0f, 1.105f, 1.105f), letGoAt = 150)
        near(AlertActs.ringing, 170, listOf(1.373f, 1f, -.985f, -.788f, 1.881f, 1f, 0f, -1.505f, 1f, 0f, 0f, 1f, 1f), letGoAt = 150)
    }

    @Test fun holdsUntilLetGoThenEnds() {
        val r = IdleV3.rig()
        r.perform(AskActs.listening)
        r.advance(500.0)
        assertTrue(r.holds(AskActs.listening))
        r.letGo(AskActs.listening)
        assertFalse(r.holds(AskActs.listening))
        r.advance(AskActs.listening.fadeOut.toDouble())
        assertEquals(IdleV3.rig().also { it.advance(r.frame) }.pose(), r.pose()) // nothing of it left
    }
}
