package com.bleural.croissant.ui.beam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The rig: moves fired on top of the idle, glances, and timelines played once. */
class CroissantRigTest {
    private fun CroissantRig.at(frame: Double) = apply { advance(frame - this.frame) }

    private fun assertPose(want: CroissantPose, got: CroissantPose, what: String) {
        fun list(p: CroissantPose) = listOf(p.hoverY, p.sideL, p.sideR, p.earL, p.earLStretch, p.earR, p.eyeX, p.eyeY, p.eyeScaleX, p.eyeScaleY)
        list(want).zip(list(got)).forEach { (w, g) -> assertEquals("$what: want $want, got $got", w, g, 0.0005f) }
    }

    @Test fun theFrameRateChangesNothing() {
        val steady = IdleV3.rig().at(300.0)
        val uneven = IdleV3.rig()
        var f = 0.0; var i = 0
        while (f < 300.0) { val step = minOf(listOf(.37, 1.0, 2.04)[i++ % 3], 300.0 - f); uneven.advance(step); f += step }
        assertPose(steady.pose(), uneven.pose(), "frame 300 in one step and in uneven ones")
    }

    @Test fun aMoveFiredPlaysOnTopAndEndsAtRest() {
        val fired = IdleV3.rig().at(50.0).apply { flutterEar() }
        val plain = IdleV3.rig().at(61.0)
        fired.at(61.0)
        assertEquals(plain.pose().earL - 7f, fired.pose().earL, 0.001f)            // the flick's -7 on the sway
        assertEquals(1.05f, fired.pose().earLStretch, 0.001f)
        assertPose(IdleV3.rig().at(90.0).pose(), fired.at(90.0).pose(), "after the twitch")
    }

    @Test fun twoBlinksAtOnceMultiply() {
        val rig = IdleV3.rig().at(10.0).apply { blink(); blink() }.at(13.0)
        assertEquals(.08f * .08f, rig.pose().eyeScaleY, 0.0001f)
    }

    @Test fun theLatestGlanceWins() {
        val rig = IdleV3.rig().at(100.0).apply { look(Gazes.DOWN) }.at(102.0)
        val midway = rig.pose()
        rig.look(Gaze.AHEAD)                                                      // turned back mid-dart, from where they are
        assertPose(midway, rig.pose(), "a glance starting")
        rig.at(110.0)
        assertEquals(0f, rig.pose().eyeY, 0.001f)
        rig.at(191.0)                                                             // v3's own dart down at 186 has them again
        assertEquals(5f, rig.pose().eyeY, 0.001f)
    }

    @Test fun aTimelinePlayedOnceStopsAtItsEnd() {
        val rig = CroissantRig().apply { play(IdleV3.lookAround) }
        assertEquals(-5f, rig.at(40.0).pose().eyeX, 0.001f)
        assertPose(CroissantRig().pose(), rig.at(IdleV3.FRAMES + 40.0).pose(), "a loop after it ended")
    }

    @Test fun refusesWhatWouldJump() {
        assertThrows(IllegalArgumentException::class.java) { CroissantRig().play(Moves.breathe) }     // starts rocked
        val lean = Move("lean", Lane(Channel.SideL, Track(Key(0f, 0f, Curve(.37f, 0f, .63f, 1f)), Key(10f, 3f))))
        assertThrows(IllegalArgumentException::class.java) { CroissantRig().loop(lean) }               // ends leaning
    }
}
