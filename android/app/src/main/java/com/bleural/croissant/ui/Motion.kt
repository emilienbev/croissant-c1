package com.bleural.croissant.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * How Home and the feature panels move, whatever a design draws on them: the motion every
 * design shares, driven by [RoomState]'s four springs. A design decides what a panel, its
 * header and its menu of features look like; how far they travel, how they fade, how the
 * wheel and the finger drive them and how a feature opens are all here, so every design
 * moves the same way (2026-09-21).
 *
 * All the numbers are panel pixels on the 480 x 640 canvas.
 */

/** How far a panel's header travels per panel: short, so it lags and fades rather than slides. */
const val HEAD_TRAVEL = 70f

/** Where panel [i]'s header is against where it rests. */
fun RoomState.headDy(i: Int): Float = (i - pos + home) * HEAD_TRAVEL

/**
 * Panel [i]'s header: eased, and faint rather than gone half way, so two words are never
 * legible at once yet the ground is never left empty between them. In a [RoomState.rush]
 * it holds dim and a panel wide, where two smoothsteps sum to one, so a spin passes the names
 * at one even brightness instead of a flash each.
 * ~~Linear to nothing 0.4 of a panel off~~ until 2026-09-29: every step went name, black,
 * name, and a spin strobed.
 */
fun RoomState.headAlpha(i: Int): Float {
    val reach = HEAD_REACH + (HEAD_REACH_RUSHED - HEAD_REACH) * rush
    val peak = 1f - (1f - HEAD_RUSHED) * rush
    return peak * smooth((1f - abs(i - pos) / reach).coerceIn(0f, 1f)) * (1f - home)
}

/** How far off the slot a header is gone, calm and in a rush, in panels. */
private const val HEAD_REACH = 0.7f
private const val HEAD_REACH_RUSHED = 1f
/** How bright a header is at most in a rush. */
private const val HEAD_RUSHED = 0.35f

/** How near panel [i] is to being the one on screen, 1 on it and 0 a panel away. */
fun RoomState.near(i: Int): Float = (1f - abs(i - pos)).coerceIn(0f, 1f)

fun smooth(t: Float) = t * t * (3f - 2f * t)

/**
 * The opened screen arrives in the back half of the opening, after the ground has changed
 * enough to read it on, and leaves in the same place on the way out.
 */
fun panelFade(open: Float): Float = smooth(((open - 0.35f) / 0.5f).coerceIn(0f, 1f))

/**
 * One frame loop for every spring, parked when nothing is moving. A room at rest costs no
 * frames at all, which matters on a device that is a launcher and is always on screen; a
 * room in motion advances all four numbers on one clock so they cannot drift apart.
 */
@Composable
fun RoomLoop(state: RoomState) {
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            if (state.settled) { state.awaitWake(); last = 0L }
            withFrameNanos { now ->
                // The first frame after parking has no previous time, so it advances nothing.
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.032f)
                last = now
                if (dt > 0f) state.advance(dt)
            }
        }
    }
}

/**
 * The wheel on Home and the panels. Only a step between panels is the wheel moving a
 * selection; everything else here is navigation and fires its own sound (Wheel, Nav.kt).
 * Under everything a feature registers ([WheelTarget], floor).
 */
@Composable
fun RoomWheel(state: RoomState) {
    WheelTarget(floor = true) { delta ->
        // Read the targets, not where the springs have got to: a second tick arriving
        // while Home is still fading out is a step between panels, not a second request
        // to leave, or a hurried spin loses its first few ticks.
        when {
            state.openTarget > 0.5f -> { if (delta < 0) { state.closePanel(); Wheel.hush(); Cues.play(Sound.ScreenOut) }; false }
            state.homeTarget > 0.5f -> { if (delta > 0) { state.depart(); Cues.play(Sound.MenuIn) }; false }
            // Asking for the panel above the first one is the request to go back, and here
            // one tick is the whole of it (2026-09-18): Ask to Home is the room's
            // own edge, a move between places like any other tick.
            delta < 0 && state.posTarget < 0.5f -> { state.goHome(); Wheel.hush(); Cues.play(Sound.MenuOut); false }
            else -> { val was = state.posTarget; state.step(delta); state.posTarget != was }
        }
    }
}

/**
 * Home's own content, riding over the panels and fading with `home`. Read in the layer
 * block, so the blend recomposes nothing. Out of the tree once it has faded: at alpha 0 it
 * is invisible but would still take taps.
 */
@Composable
fun HomeLayer(state: RoomState, content: @Composable () -> Unit) {
    val show by remember { derivedStateOf { state.home > 0.02f } }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = state.home; translationY = (1f - state.home) * 40f * 1.75f }) {
        if (show) content()
    }
}

/**
 * The opened feature, over everything, arriving as the panel under it goes away. Out of the
 * tree until it is asked for and again once it has gone. The panel that was asked for, not
 * whichever the room is passing: a jump and an open in the same breath would otherwise
 * compose every panel swept over, the camera's among them, which binds a lens as it is
 * composed. Once it has faded it takes no taps, so a tap meant for the panel under it is not
 * a tap on a row of the page that has just closed.
 */
@Composable
fun OpenedLayer(state: RoomState, content: @Composable (Feature) -> Unit) {
    val opened by remember { derivedStateOf { if (state.open > 0.02f) state.last else null } }
    opened?.let { f ->
        val lit by remember { derivedStateOf { panelFade(state.open) > 0.01f } }
        // In view only while the stack's screen it sits in is: under a pushed page it gave the
        // wheel up no more than its own fade said, so the Metronome's dial kept every tick
        // meant for the tuner over it (2026-09-22).
        val under = LocalLayerVisible.current
        CompositionLocalProvider(LocalLayerVisible provides (lit && under)) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = panelFade(state.open)
                        translationY = (1f - state.open) * 24f * 1.75f
                    }
                    .then(if (lit) Modifier else Modifier.swallowTouches()),
            ) { content(f) }
        }
    }
}

/**
 * Every gesture Home and the panels have, dispatched once on touch-down by where the room is.
 *
 * On Home a vertical drag drives Home itself: half a panel of upward drag takes it away.
 * In the menu a drag from the left edge blends back to Home, carrying the room up to Ask
 * under it as it comes (Home sits above the first panel), and anywhere else moves the
 * room 1:1 with the finger, stretching above the first panel, with a fling of at most one
 * panel. With a feature open the left edge scrubs it back to its panel.
 *
 * A tap on a feature in the menu ([menuAt], given the tap in panel pixels from the top of
 * this layout) goes to it, from Home and from the panels alike; a tap anywhere else on a
 * panel opens it. A tap on Home that hits no feature is [onHomeTap]'s, given the uptime of
 * its touch-down, for a design that counts taps there.
 *
 * A room drawn as a [wheel] (ui/SideWheel.kt) turns by the finger's travel along its rim
 * rather than by the panel's height, and a tap on a feature in it opens that feature.
 */
fun Modifier.roomGestures(
    state: RoomState,
    onHomeTap: (downAt: Long) -> Unit = {},
    wheel: SideWheel? = null,
    menuAt: (x: Float, y: Float) -> Int?,
): Modifier =
    pointerInput(state, wheel) {
        // The same 28 dp the stack's edge drag uses, so back feels the same everywhere.
        val edge = 28.dp.toPx()
        val u = 1.px.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val panel = size.height.toFloat()
            // A drag this long moves the room one panel: the panel itself, or the wheel's arc.
            val travel = wheel?.let { it.arc * u } ?: panel
            val onHome = state.homeTarget > 0.5f
            val opened = state.openTarget > 0.5f
            val fromEdge = down.position.x < edge
            val mode = when {
                opened -> if (fromEdge) MODE_BACK else MODE_NONE
                onHome -> MODE_HOME_UP
                fromEdge -> MODE_HOME_EDGE
                else -> MODE_PAGE
            }
            // An open feature owns its own touches.
            if (mode == MODE_NONE) return@awaitEachGesture
            // Where the room is, not where it was going: a finger landing on a room in
            // flight catches it, as every scroller does.
            val startPos = state.pos
            val startOpen = state.open
            val tracker = VelocityTracker()
            var moved = 0f
            var began = false
            tracker.addPosition(down.uptimeMillis, down.position)

            drag(down.id) { change: PointerInputChange ->
                tracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                moved = maxOf(moved, abs(dx), abs(dy))
                if (!began && moved < TOUCH_SLOP) return@drag
                if (!began) {
                    began = true
                    if (mode == MODE_PAGE) state.dragStart()
                }
                change.consume()
                when (mode) {
                    MODE_BACK -> state.scrubOpen(startOpen - dx / (size.width * 0.7f))
                    MODE_PAGE -> state.dragTo(startPos - dy / travel, travel / u)
                    MODE_HOME_UP -> state.dragHome(1f + dy / (panel * 0.5f))
                    MODE_HOME_EDGE -> state.dragHomeFrom(startPos, dx / (size.width * 0.7f))
                }
            }

            if (!began) {
                val hit = if (opened) null else menuAt(down.position.x / u, down.position.y / u)
                when {
                    hit != null -> {
                        val was = state.posTarget
                        state.goTo(hit)
                        // Leaving Home is the departure and speaks for the whole tap; in the
                        // menu it is a selection, and one that moves nothing says nothing.
                        if (onHome) { state.leaveHome(); Cues.play(Sound.MenuIn) }
                        else if (wheel != null) { state.openPanel(); Cues.play(Sound.ScreenIn) }
                        else if (state.posTarget != was) Cues.play(Sound.WheelMoved)
                    }
                    !onHome && !opened -> { state.openPanel(); Cues.play(Sound.ScreenIn) }
                    onHome -> onHomeTap(down.uptimeMillis)
                }
                return@awaitEachGesture
            }
            val v = tracker.calculateVelocity()
            when (mode) {
                MODE_BACK -> if (state.open < 0.66f) { state.closePanel(); Cues.play(Sound.EdgeBack) } else state.openPanel()
                MODE_PAGE -> {
                    // Paging is silent, the motion is the feedback; a pull past the first
                    // panel ends in Home, which is a departure and sounds like one.
                    val wasHome = state.homeTarget > 0.5f
                    // A wheel turns as far as the finger took it; a fling adds one from there.
                    state.dragEnd((if (wheel != null) state.pos else startPos).roundToInt(), -v.y / travel)
                    if (!wasHome && state.homeTarget > 0.5f) Cues.play(Sound.MenuOut)
                }
                MODE_HOME_UP -> {
                    val stay = state.home >= 0.67f
                    state.settleHome(stay)
                    if (!stay) Cues.play(Sound.MenuIn)
                }
                MODE_HOME_EDGE -> {
                    val go = state.home > 0.33f
                    state.settleHomeFrom(startPos, go)
                    if (go) Cues.play(Sound.MenuOut)
                }
            }
        }
    }

private const val MODE_NONE = 0
private const val MODE_PAGE = 1
private const val MODE_BACK = 2
private const val MODE_HOME_UP = 3
private const val MODE_HOME_EDGE = 4

private const val TOUCH_SLOP = 12f

/**
 * Eats every pointer event before its children see it, without claiming it: consumed in
 * the initial pass, so the room's own gesture, which asks for the down whether or not it
 * was consumed, still hears it and acts. For a panel that is on its way out.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/**
 * A horizontal swipe between the pages of one feature (Weather's places), as a scroller's
 * pages move: a finger going sideways drags [x] (page pixels of the layout, read by the page
 * as its content's translation), and let go past a fifth of the width, or flung, it steps
 * [step] (+1 the next, -1 the one before) while the page leaves that way and the next comes
 * in from the other side; otherwise it springs back. At either end it drags a third as far
 * and never steps ([can]). Silent, as the room's paging is: the motion is the feedback. The
 * left edge is not heard: it is the way back, as everywhere. A vertical drag is left to the
 * scroll under it.
 */
fun Modifier.pageSwipe(x: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, can: (Int) -> Boolean, step: (Int) -> Unit): Modifier = composed {
    // The gesture outlives a recomposition; what it may step to is read fresh (a place added while it ran).
    val canNow by rememberUpdatedState(can)
    val stepNow by rememberUpdatedState(step)
    pointerInput(x) {
        val can = { d: Int -> canNow(d) }
        val step = { d: Int -> stepNow(d) }
        val edge = 28.dp.toPx()
        kotlinx.coroutines.coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x < edge) return@awaitEachGesture
                val w = size.width.toFloat()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f
                fun follow(by: Float) {
                    dx += by
                    val dir = if (dx < 0) 1 else -1
                    val to = if (can(dir)) dx else dx / 3f
                    launch { x.snapTo(to) }
                }
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { c, over ->
                    c.consume(); follow(over)
                } ?: return@awaitEachGesture
                horizontalDrag(start.id) { c ->
                    tracker.addPosition(c.uptimeMillis, c.position)
                    follow(c.position.x - c.previousPosition.x)
                    c.consume()
                }
                val v = tracker.calculateVelocity().x
                val dir = when {
                    dx < -w / 5f || v < -1200f -> 1
                    dx > w / 5f || v > 1200f -> -1
                    else -> 0
                }
                launch {
                    if (dir != 0 && can(dir)) {
                        x.animateTo(-dir * w, androidx.compose.animation.core.tween(160))
                        step(dir)
                        x.snapTo(dir * w)
                        x.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f))
                    } else x.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 700f))
                }
            }
        }
    }
}
