package com.bleural.croissant.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp

/*
 * How the wheel scrolls a list of rows or a page of words, in every design: one glide per
 * scrollable, which a tick re-aims rather than restarts.
 *
 * Each tick used to start its own 120 ms tween, which cancelled the one in flight, so a spin
 * was a run of short linear moves, each from a standstill. The distance was measured once,
 * as the tick landed, so a row that then grew into its card ended cut off by the bottom
 * edge, and a row not yet laid out was reached by a jump. Now a tick only says where to go;
 * the glide moves the list every frame on a critically damped spring toward wherever that
 * is now, keeping its speed when the aim moves on, and measuring again each frame, so rows
 * that change height on the way are followed.
 */

/** How quickly a glide closes on its aim, per second: nine tenths of a tick's move in about 110 ms. */
private const val RATE = 36f

/** How long a glide keeps watching after it arrives, for a row still growing into place (ModelPick's card, 180 ms). */
private const val WATCH_MS = 250L

private class Glide {
    /** How far the scrollable still has to go, in px, forward positive; asked again every frame. */
    var left: () -> Float = { 0f }
    /** Where a page of words is headed ([glideBy]), so ticks add up rather than start from where it has got to. */
    var to = 0f
    var velocity = 0f
    var running = false
}

/** The glide of each scrollable on screen, gone with it. Only the main thread touches it. */
private val glides = WeakHashMap<ScrollableState, Glide>()

/**
 * Aims this scrollable's glide with [left] and, unless it is already moving, moves it until it
 * has arrived and stayed for [WATCH_MS]. A finger, or any other scroll, takes the scrollable
 * from it, and the glide lets go.
 */
private suspend fun ScrollableState.glide(g: Glide, left: () -> Float) {
    g.left = left
    if (g.running) return
    g.running = true
    try {
        scroll {
            var last = 0L
            var still = 0L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                // The spring's position is where the scrollable is, counted from its aim.
                val x = -g.left()
                if (abs(x) < 0.5f && abs(g.velocity) < 20f) {
                    if (x != 0f) scrollBy(-x)
                    g.velocity = 0f
                    if (still == 0L) still = now
                    if (now - still > WATCH_MS * 1_000_000) break
                    continue
                }
                still = 0L
                val e = exp(-RATE * dt)
                val c = g.velocity + RATE * x
                val step = (x + c * dt) * e - x
                g.velocity = (g.velocity - RATE * c * dt) * e
                // Short of what was asked is an end of the list: there is nothing further to go to.
                if (abs(scrollBy(step) - step) > 1f) break
            }
        }
    } finally {
        g.running = false
        g.velocity = 0f
    }
}

/**
 * Scrolls a list just enough for the focused item to be in view: nothing moves while it
 * already is. [top] is what must be in view above it when moving up, the day line of a
 * group's first row, so a date is never cut off. It glides there ([glide]); a call while the
 * list is still moving re-aims it and returns.
 */
suspend fun LazyListState.keepInView(index: Int, top: Int = index) {
    glide(glides.getOrPut(this) { Glide() }) { leftToShow(index, top) }
}

/**
 * How far this list must scroll for item [index] to be in view with [top] above it, negative
 * upward, 0 when it is. An item not laid out is placed by the average height of those that
 * are, which the next frame corrects as it comes into view. When the two do not fit
 * together, [top] wins.
 */
private fun LazyListState.leftToShow(index: Int, top: Int): Float {
    val info = layoutInfo
    val seen = info.visibleItemsInfo
    if (seen.isEmpty()) return 0f
    val first = seen.first()
    val last = seen.last()
    val gap = info.mainAxisItemSpacing
    val each = (last.offset + last.size + gap - first.offset) / seen.size.toFloat()
    fun startOf(i: Int): Float = seen.firstOrNull { it.index == i }?.offset?.toFloat()
        ?: if (i < first.index) first.offset - (first.index - i) * each
        else last.offset + last.size + gap + (i - last.index - 1) * each
    fun endOf(i: Int): Float = seen.firstOrNull { it.index == i }?.let { (it.offset + it.size).toFloat() }
        ?: (startOf(i) + each - gap)
    val head = startOf(top)
    val foot = endOf(index)
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    return when {
        head < start || foot - head > end - start -> head - start
        foot > end -> foot - end
        else -> 0f
    }
}

/**
 * Scrolls a page of words by [px] (a wheel tick's step), gliding ([glide]). A tick while it is
 * still moving goes on from where it was headed, not from where it has got to, so every tick
 * counts its whole step.
 */
suspend fun ScrollState.glideBy(px: Int) {
    val g = glides.getOrPut(this) { Glide() }
    val from = if (g.running) g.to else value.toFloat()
    g.to = (from + px).coerceIn(0f, maxValue.toFloat())
    glide(g) { g.to.coerceAtMost(maxValue.toFloat()) - value }
}
