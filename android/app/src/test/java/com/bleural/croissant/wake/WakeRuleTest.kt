package com.bleural.croissant.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeRuleTest {
    private fun wakes(probs: List<Float>, cutoff: Float = 0.9f) =
        WakeRule(cutoff, window = 5, refractoryMs = 2000, stepMs = 30).let { r -> probs.indices.filter { r.offer(probs[it]) } }

    @Test fun needsAFullWindowOverTheCutoff() {
        assertEquals(emptyList<Int>(), wakes(List(4) { 1f }))
        assertEquals(listOf(4), wakes(List(5) { 1f }))
        assertEquals(emptyList<Int>(), wakes(listOf(1f, 1f, 1f, 1f, 0.4f)))
    }

    @Test fun waitsOutTheRefractoryTime() {
        // 2000 ms at 30 ms a step: the next wake is 67 steps after the first.
        val w = wakes(List(200) { 1f })
        assertEquals(listOf(4, 71, 138), w.take(3))
    }
}
