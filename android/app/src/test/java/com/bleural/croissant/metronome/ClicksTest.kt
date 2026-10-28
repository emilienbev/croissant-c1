package com.bleural.croissant.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The recorded tones: every one Clicks lists has its three pulses in the assets, and they read as sound. */
class ClicksTest {
    private val assets = File("src/main/assets/metronome")

    @Test
    fun everyRecordedToneHasItsThreePulses() {
        val listed = Click.entries.mapNotNull { it.asset }.sorted()
        assertEquals(listed, assets.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted())
        for (name in listed) for (p in Pulse.entries) {
            val s = Clicks.pcm16(File(assets, "$name/${p.name.lowercase()}.wav").readBytes())
            val ms = s.size * 1000 / Clicks.RATE
            assertTrue("$name/$p is $ms ms", ms in 20..300)
            val peak = s.maxOf { kotlin.math.abs(it) }
            assertTrue("$name/$p peaks at $peak", peak in 0.8f..0.95f)
        }
    }
}
