package com.bleural.croissant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound

/**
 * Registers [handler] as the wheel target for as long as this composable is on
 * screen. It returns whether the tick moved a selection, which is what [Wheel]
 * sounds the tick for; see there. A screen whose wheel scrolls freely, or backs
 * out, or does nothing, returns false.
 *
 * [floor] is for the room alone: it goes under every other target rather than on top of
 * the ones already there, because it is the only one that gives the wheel up and asks for
 * it back while something it drew itself is still registered ([Wheel.pushFloor]).
 */
@Composable
fun WheelTarget(floor: Boolean = false, handler: (Int) -> Boolean) {
    val latest = rememberUpdatedState(handler)
    // Only what can be seen takes the wheel. A screen sliding out of the stack, and a
    // panel that has faded but is not yet out of the tree, both stayed registered and
    // stayed last, so a tick landing in those few hundred milliseconds went to the thing
    // that was leaving: a second back from a popped page, or the selection of an
    // invisible panel moving instead of the room paging.
    val visible = LocalLayerVisible.current
    DisposableEffect(visible) {
        if (!visible) return@DisposableEffect onDispose { }
        val fn: (Int) -> Boolean = { latest.value(it) }
        if (floor) Wheel.pushFloor(fn) else Wheel.push(fn)
        onDispose { Wheel.remove(fn) }
    }
}

/**
 * Move a selection by one wheel tick, clamped to 0..[last], and say whether it
 * actually went anywhere. The answer is false at either end of a list, which is
 * what keeps the tick honest: pressing on past the last row selects nothing new
 * and so sounds nothing.
 */
inline fun moved(from: Int, delta: Int, last: Int, set: (Int) -> Unit): Boolean {
    val to = (from + delta).coerceIn(0, maxOf(0, last))
    if (to == from) return false
    set(to)
    return true
}

/** Registers [handler] as the side button's short-press shutter for as long as this composable is on screen. */
@Composable
fun ShutterTarget(handler: () -> Unit) {
    val latest = rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val fn: () -> Unit = { latest.value() }
        ShutterKey.handler = fn
        onDispose { if (ShutterKey.handler === fn) ShutterKey.handler = null }
    }
}

/** A click with no ripple and no sound; the sounds belong to what a tap does, not to the tap. Nothing when [onClick] is null. */
/**
 * A tap, and a press and hold that means something else: the control centre's tiles
 * and the shelves' selection ([PhotoTile]). Without [onLongClick] it is [tappable].
 */
@Composable
fun Modifier.held(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) tappable(onClick) else combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = onClick, onLongClick = { Cues.play(Sound.LongPress); onLongClick() },
    )

fun Modifier.tappable(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else composed { clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) }
