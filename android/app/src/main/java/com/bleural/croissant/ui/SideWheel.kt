package com.bleural.croissant.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The side wheel's geometry (picked 2026-09-29): the features round the rim of a wheel
 * rising from the right edge, where the R1's own is, the one in hand at nine o'clock and the
 * later ones below it, so a scroll down brings the next one up into the slot. Geometry only:
 * page pixels on the 480 x 640 canvas, y from the very top, and angles in degrees on the
 * screen (y down, 180 is nine o'clock). No drawing, no colours.
 *
 * A design reuses it unchanged and draws its own discs, words and colours on it: it reads
 * [spot] for every feature at the room's `pos` (plus any offset of its own, such as a turn in
 * from Home), sizes and fades by [Spot.d], and passes itself to `roomGestures(wheel = ...)` with
 * [at] in its `menuAt`, so the wheel ticks, the drags and the springs all turn it.
 */
class SideWheel(
    val count: Int = Feature.entries.size,
    val cx: Float = 612f,
    val cy: Float = 322f,
    val radius: Float = 300f,
    /** Degrees between two features on the rim. */
    val step: Float = 17f,
) {
    /** A feature on the rim: its centre, its angle, and [d], how many features below the slot it is (above when negative). */
    class Spot(val x: Float, val y: Float, val angle: Float, val d: Float)

    /** Where feature [i] sits when the wheel is turned to [pos] (the room's `pos`: [i] is in the slot at `pos == i`). */
    fun spot(i: Int, pos: Float): Spot {
        val d = i - pos
        val angle = 180f - d * step
        val t = rad(angle)
        return Spot(cx + radius * cos(t), cy + radius * sin(t), angle, d)
    }

    /** How far the rim travels for one feature, in page pixels: a drag this long turns the wheel by one. */
    val arc: Float get() = radius * rad(step)

    /** The feature whose centre is nearest ([x], [y]) and within [reach] of it at [pos], or null; [n] for a room whose count changes. */
    fun at(pos: Float, x: Float, y: Float, reach: Float = 44f, n: Int = count): Int? =
        (0 until n).map { it to spot(it, pos) }
            .map { (i, s) -> i to hypot(s.x - x, s.y - y) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }?.first

    private fun rad(deg: Float) = deg * PI.toFloat() / 180f
}
