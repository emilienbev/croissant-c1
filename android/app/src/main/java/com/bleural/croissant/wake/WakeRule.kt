package com.bleural.croissant.wake

/**
 * When a stream of [Spotter] probabilities is a wake: the mean of the last [window] at or over
 * [cutoff], then none for [refractoryMs]. ESPHome's micro_wake_word rule, and the one the Mac's
 * scoring uses, so both count the same wakes.
 */
class WakeRule(
    private val cutoff: Float,
    private val window: Int = WINDOW,
    private val refractoryMs: Int = REFRACTORY_MS,
    private val stepMs: Int = Spotter.STEP_MS,
) {
    private val last = FloatArray(window)
    private var n = 0L
    private var lastWakeMs = Long.MIN_VALUE / 2

    /** True when this probability completes a wake. */
    fun offer(p: Float): Boolean {
        last[(n % window).toInt()] = p
        n++
        if (n < window) return false
        val atMs = n * stepMs
        if (last.average() < cutoff || atMs - lastWakeMs < refractoryMs) return false
        lastWakeMs = atMs
        return true
    }

    companion object {
        const val WINDOW = 5
        const val REFRACTORY_MS = 2000
    }
}
