package com.bleural.croissant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bleural.croissant.audio.Sound
import kotlinx.coroutines.launch

/** How far above the stack its clip reaches, so the camera's live view runs up to the edge. */
const val SCREEN_TOP = 12

private val SLIDE_PX = tween<Float>(120, easing = LinearEasing)

/** A layer's place in the stack and what it shows; the key its state lives under. */
private data class Slot(val index: Int, val screen: Screen)

/**
 * The stack drawn as layers, bottom to top, every screen in it: a screen keeps its
 * state and its wheel position while another sits over it, the page under an edge
 * drag is really there, and going back never rebuilds anything. Each layer is opaque
 * and takes every touch, so nothing reaches a screen underneath. A pushed screen is
 * born a panel's width to the right and slides in; a popped one stays drawn on top
 * while it slides out; the edge drag moves the top screen with the finger and, past a
 * third of the width, finishes the slide before popping, otherwise slides it home.
 * Only the outer 28 dp start a drag, so lists and text keep their own gestures.
 */
@Composable
fun Slides(nav: NavState, content: @Composable (Screen) -> Unit) {
    val stack = nav.stack.toList()
    var width by remember { mutableIntStateOf(0) }
    val xs = remember { HashMap<Slot, Animatable<Float, AnimationVector1D>>() }
    fun xOf(slot: Slot) = xs.getOrPut(slot) {
        Animatable(if (slot.index == 0 || slot.screen == nav.bornInPlace) 0f else width.toFloat())
    }

    // A pop by the chevron or the wheel keeps the popped screen drawn while it leaves;
    // after an edge drag it has already gone and only its state is dropped.
    val previous = remember { arrayOf(stack) }
    var leaving by remember { mutableStateOf<Slot?>(null) }
    val popped = previous[0].takeIf { it.size > stack.size }?.let { Slot(it.lastIndex, it.last()) }
    if (popped != null && !nav.lastPopWasSwipe) leaving = popped
    SideEffect {
        if (popped != null && nav.lastPopWasSwipe) xs.remove(popped)
        // The side button's cut drops every screen at once, and `popped` is one slot, so
        // the ones below it kept an Animatable parked at 0. Pushing that screen again
        // found the stale entry and it was born already home, with nothing to slide.
        if (leaving == null) xs.keys.retainAll { it.index < stack.size && stack.getOrNull(it.index) == it.screen }
        previous[0] = stack
        nav.bornInPlace = null
    }

    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 28.dp.toPx() }
    Box(
        Modifier.fillMaxSize().onSizeChanged { width = it.width }
            // Clipped to the panel's width, not its height: a screen parked or sliding
            // to the right (the menu, a pushed page) must not show through the rail's
            // gaps, while the camera's live view still runs up past [SCREEN_TOP] to the edge.
            .drawWithContent { clipRect(0f, -SCREEN_TOP.px.toPx(), size.width, size.height) { this@drawWithContent.drawContent() } }
            .pointerInput(nav.canPop) {
                if (!nav.canPop) return@pointerInput
                val w = size.width.toFloat()
                val top = { xOf(Slot(nav.stack.lastIndex, nav.current)) }
                // The edge is judged where the finger lands, not where it crosses the slop:
                // a fast flick from the bezel is already past 28 dp by then.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > edge) return@awaitEachGesture
                    val x = top()
                    val move = { dx: Float -> scope.launch { x.snapTo((x.value + dx).coerceIn(0f, w)) } }
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); move(over) }
                        ?: return@awaitEachGesture
                    horizontalDrag(drag.id) { change -> val dx = change.positionChange().x; change.consume(); move(dx) }
                    scope.launch {
                        if (x.value > w / 3f) { x.animateTo(w, SLIDE_PX); nav.pop(Sound.EdgeBack) }
                        else x.animateTo(0f, SLIDE_PX)
                    }
                }
            },
    ) {
        // One loop for every layer, the leaving one included, so a popped screen keeps the
        // composition it had in the stack while it slides out instead of being rebuilt.
        val layers = stack.mapIndexed { i, screen -> Slot(i, screen) } + listOfNotNull(leaving)
        for ((i, slot) in layers.withIndex()) {
            key(slot) {
                val x = xOf(slot)
                val above = layers.getOrNull(i + 1)?.let { xOf(it) }
                val out = slot == leaving
                LaunchedEffect(out) {
                    if (out) { x.animateTo(width.toFloat(), SLIDE_PX); xs.remove(slot); leaving = null }
                    else x.animateTo(0f, SLIDE_PX)
                }
                Layer(x, above) { content(slot.screen) }
            }
        }
    }
}

/** Whether the screen this is read in is in view: false under another screen at rest. */
val LocalLayerVisible = compositionLocalOf { true }

/**
 * One screen of the stack: opaque, moved by [x] without redrawing, and a wall to
 * touches. Wholly under another screen (the one [above] at rest) it stays composed but
 * is not drawn: Home and its parked menu under every feature were costing every frame
 * of the recorder's reels (2026-09-16).
 */
@Composable
private fun Layer(x: Animatable<Float, AnimationVector1D>, above: Animatable<Float, AnimationVector1D>?, content: @Composable () -> Unit) {
    val visible by remember(above) { derivedStateOf { above == null || above.value > 0f } }
    // Under something drawn over the whole stack (Beam's first setup), no layer is in view.
    val under = LocalLayerVisible.current
    CompositionLocalProvider(LocalLayerVisible provides (visible && under)) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { translationX = x.value; alpha = if (visible) 1f else 0f }.background(Ink.Background)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        ) { content() }
    }
}
