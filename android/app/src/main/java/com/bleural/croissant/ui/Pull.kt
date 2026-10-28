package com.bleural.croissant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel

/**
 * The elastic at the top, everywhere in the app:
 * asking for the item above the first one stretches the content by a quarter of the drag
 * and lights two dots at the top edge, then releases into the step back. The dots are the
 * whole promise: none, one, and at two letting go goes back. Under two it springs back and
 * nothing happens, so a pull that changed its mind is a pull that can be taken back.
 *
 * The wheel gets the same promise rather than a shortcut: every tick adds [TICK] to the
 * pull and holds it for [FLASH], so three ticks light the first dot and six light the
 * second and take the step, and a tick that arrives too late finds the pull sprung back.
 * The step hushes the wheel ([Wheel.hush]), so the rest of the spin is not heard by the
 * page it lands on.
 * A stray tick at the top of a list therefore shows half a dot and stays put, which is
 * what a tick at the top usually means; going back is a spin of the wheel.
 * The step back is whatever the caller says it is: an open panel closes, a sub-screen pops.
 *
 * Three lines in a list, and nothing else:
 *
 * ```kotlin
 * val pull = rememberPull(onBack)
 * WheelTarget { delta -> if (delta < 0 && focus == 0) { pull.stepBack(); return@WheelTarget false } ... }
 * LazyColumn(state = list, modifier = Modifier.fillMaxSize().elasticPull(pull)) { ... }
 * ```
 *
 * Both numbers are written by the finger and read only inside a `graphicsLayer` block and
 * a draw lambda, so a frame of the spring redraws and recomposes nothing. That is the
 * lesson the room already paid for (ui/RoomState.kt): read as
 * composition state, a spring costs a measure and a layout every frame.
 */
class PullState internal constructor(private val unit: Float, private val onStepBack: () -> Unit) {
    /** How far the pull has gone, 0 to 1: which of the two dots are lit, and how far. */
    private var pull by mutableFloatStateOf(0f)

    /** The same, except that a wheel's flash does not move the content. */
    private var stretch by mutableFloatStateOf(0f)

    private var pullTarget = 0f
    private var stretchTarget = 0f
    private var pullV = 0f
    private var stretchV = 0f

    /** What the finger has asked for past the first item, in device pixels. */
    private var drag = 0f

    /** Seconds the wheel's pull is held where it is, and so the window the next tick has. */
    private var flash = 0f

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private fun stir() { wake.trySend(Unit) }

    /** Suspends until the elastic is asked to move again; the loop parks here when settled. */
    internal suspend fun awaitWake() = wake.receive()

    internal val settled: Boolean
        get() = flash <= 0f && pullNear(pull, pullTarget, pullV) && pullNear(stretch, stretchTarget, stretchV)

    /**
     * The wheel asking for the item above the first, exactly as the room's own does
     * (RoomState.tickBack). Each tick adds [TICK] to the pull and holds it there for
     * [FLASH]; the next tick has that long to arrive or the whole thing springs back.
     * So the dots read as the gauge they are: three ticks light the first dot, six light
     * the second and take the step. ~~Two ticks in all~~ until 2026-09-18 (three
     * or four), ~~four~~ until 2026-09-22 (the wheel should have to turn
     * a bit more than a finger pulls), which is a spin rather than a flick of the wheel.
     *
     * A late tick builds on whatever is left rather than starting over, which is the
     * forgiving half of a rubber band. No stretch, the wheel having no finger to follow.
     */
    fun stepBack() {
        // The tick moves the target, never the number: the springs carry the page and the
        // dots there, a tick arriving mid-flight retargets the spring it is already on and
        // keeps its velocity, and a spin reads as one slide instead of four steps
        // (2026-09-18: it was staggered). The same rule the room's own pos keeps.
        pullTarget = (pullTarget + TICK).coerceAtMost(1f)
        if (pullTarget >= DOT_TWO) {
            Wheel.hush()
            // The gauge is finished before the page leaves, so the promise the dots make
            // is kept in the frame the step is taken in.
            pull = DOT_TWO; pullV = 0f
            flash = 0f; pullTarget = 0f; stretchTarget = 0f
            stir()
            onStepBack()
            return
        }
        // The wheel moves the content as a finger does, so the dots are uncovered rather
        // than laid over the first row (2026-09-18).
        stretchTarget = pullTarget
        flash = FLASH
        stir()
    }

    /** The content's stretch in device pixels, for the layer block and nowhere else. */
    internal fun lift(): Float = stretch * LIFT_FULL * unit

    /** The finger, in device pixels past the top. The springs are off while it holds. */
    private fun hold(d: Float) {
        // A finger owns the dots outright: whatever the wheel had lit is forgotten.
        flash = 0f
        drag = d.coerceAtLeast(0f)
        val p = backPull(drag / unit)
        pull = p; pullV = 0f; pullTarget = p
        stretch = p; stretchV = 0f; stretchTarget = p
        stir()
    }

    /** Let go: with both dots lit the step is taken, and either way the elastic snaps back. */
    private fun release() {
        val take = pull >= DOT_TWO
        drag = 0f
        pullTarget = 0f; stretchTarget = 0f
        stir()
        if (take) onStepBack()
    }

    /**
     * The list's overscroll at its first item, taken whole so the platform's own stretch
     * never sees it and the two elastics do not fight.
     */
    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushing back up gives the stretch back before the list scrolls again.
            if (source != NestedScrollSource.UserInput || drag <= 0f || available.y >= 0f) return Offset.Zero
            val take = maxOf(available.y, -drag)
            hold(drag + take)
            return Offset(0f, take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            hold(drag + available.y)
            return Offset(0f, available.y)
        }

        // Every drag ends here, fling or not, which is where the pull is answered.
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (drag <= 0f) return Velocity.Zero
            release()
            return available
        }
    }

    /** The two dots at the top edge; see [drawBackDots]. */
    internal fun dots(scope: DrawScope, ink: Color) = scope.drawBackDots(pull, ink)

    /** One frame of both springs. [dt] is seconds, clamped by the loop. */
    internal fun advance(dt: Float) {
        // The wheel's dot is held lit and then let go, not sprung down from the start: the
        // window is the rule a finger-less tick is judged by, so it is timed, not tuned.
        if (flash > 0f) { flash -= dt; if (flash <= 0f) { flash = 0f; pullTarget = 0f; stretchTarget = 0f } }
        pullV = pullSpring(pull, pullTarget, pullV, dt).also { pull += it * dt }
        stretchV = pullSpring(stretch, stretchTarget, stretchV, dt).also { stretch += it * dt }
        if (pullNear(pull, pullTarget, pullV)) { pull = pullTarget; pullV = 0f }
        if (pullNear(stretch, stretchTarget, stretchV)) { stretch = stretchTarget; stretchV = 0f }
    }
}

/**
 * The rubber band: how far the step back has been asked for, 0 to 1, from the finger's
 * travel past the first item in panel pixels. It stiffens as it goes, so every further
 * pixel gives less than the last and the content comes to a stop rather than following
 * the finger for ever.
 *
 * ~~Linear, a full pull at 266.7 px, so the second dot arrived at 88 px~~ (until
 * 2026-09-18, when it wanted more resistance): the first dot is now whole at
 * 77 px and the second at 176 px, twice the travel it used to take to arm, and the
 * stiffening is what makes the first dot cheap and the second a decision.
 */
internal fun backPull(dragPx: Float): Float = 1f - exp(-dragPx.coerceAtLeast(0f) / PULL_SCALE)

/** The travel that gives the band its stiffness: the first dot at 0.17 of it, the second at 0.40. */
private const val PULL_SCALE = 440f

/**
 * The stretch the content reaches at a whole pull, in panel pixels; it never goes past
 * this. ~~80~~ 110 from 2026-09-18, so that the gap has uncovered each dot by the time
 * that dot is whole; at 110 the band's first pixels also give back the 0.25 the pull had
 * before it was made to stiffen, so it starts as it always did and hardens later.
 */
internal const val LIFT_FULL = 110f

/**
 * The two dots: the gauge of the step back, centred, lying **behind** the content and
 * uncovered as the pull slides it down (2026-09-18, in place of drawing them
 * over the first row). ~~A curve 180 px wide~~ until earlier the same day: a curve says
 * only that something is happening, two dots say how much further there is to go and
 * when letting go will act.
 *
 * The first dot is whole at [DOT_ONE], the second at [DOT_TWO], and [DOT_TWO] is the
 * release threshold itself, so the drawing never promises more than the gesture does.
 * Each sits where the content has cleared it by the time it is whole: the lift is
 * `p * LIFT_FULL`, so 18 px at [DOT_ONE] against the first dot's 13, and 36 px at
 * [DOT_TWO] against the second's 28. Before that a dot is a sliver under the edge,
 * which is the honest picture of a pull that has not arrived yet.
 */
internal fun DrawScope.drawBackDots(pull: Float, ink: Color) {
    if (pull <= DOT_NONE) return
    val u = 1.px.toPx()
    val cx = size.width / 2f
    for (i in 0..1) {
        val from = if (i == 0) DOT_NONE else DOT_ONE
        val to = if (i == 0) DOT_ONE else DOT_TWO
        val a = ((pull - from) / (to - from)).coerceIn(0f, 1f)
        if (a <= 0f) continue
        drawCircle(ink.copy(alpha = 0.55f + 0.45f * a), radius = DOT_R * u, center = Offset(cx, (9f + i * 15f) * u))
    }
}

/** A dot's radius in panel pixels; the pair spans y 5 to 28 from the content's top edge. */
private const val DOT_R = 4f

/** Under this much pull there is nothing to show at all. */
private const val DOT_NONE = 0.02f

/** The pull at which the first dot is whole. */
internal const val DOT_ONE = 0.16f

/** The pull at which the second dot is whole, which is the release threshold itself. */
internal const val DOT_TWO = 0.33f

/** Seconds a tick's pull is held before it springs back: the next tick has that long to arrive. */
internal const val FLASH = 0.35f

/** Wheel ticks it takes to step back, all inside each other's [FLASH]: three light the first dot, six the second. */
internal const val TICKS_BACK = 6

/** What one tick adds to the pull, so that [TICKS_BACK] of them reach [DOT_TWO] exactly. */
internal const val TICK = DOT_TWO / TICKS_BACK

/** The elastic snaps back hard, so the dots read as a rubber band letting go (plan, Motion). */
private const val PULL_K = 300f

private fun pullSpring(x: Float, target: Float, v: Float, dt: Float): Float {
    val c = 2f * sqrt(PULL_K)
    return v + ((target - x) * PULL_K - c * v) * dt
}

private fun pullNear(x: Float, t: Float, v: Float) = abs(t - x) < 0.0005f && abs(v) < 0.001f

/**
 * The elastic for one list, with what a completed pull does. The callback is the same
 * back the wheel and the chevron already use: [PanelDeps.close] on an open panel,
 * `nav::pop` on a sub-screen.
 */
@Composable
fun rememberPull(onStepBack: () -> Unit): PullState {
    val latest = rememberUpdatedState(onStepBack)
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    val state = remember(unit) { PullState(unit) { latest.value() } }
    // One frame loop, parked the moment the elastic has settled, so a list at rest costs
    // no frames at all, as RoomLoop does for the room.
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            if (state.settled) { state.awaitWake(); last = 0L }
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.032f)
                last = now
                if (dt > 0f) state.advance(dt)
            }
        }
    }
    return state
}

/**
 * Puts [pull] on a scrolling node: its overscroll at the first item becomes the stretch
 * and the dots. Goes on the list itself, so the dots are uncovered at the list's own top
 * edge and the header above it stays where it is. [ink] is the current ink, F2F2F3 on an
 * open panel's black, and [ground] what the page carries down over them.
 *
 * On a `Modifier.verticalScroll` node this must come **before** the scroll in the chain
 * (`Modifier.elasticPull(pull).verticalScroll(scroll)`): after it, it is the scroll's
 * child rather than its parent and never hears a thing. A `LazyColumn` takes it anywhere
 * in the modifier it is passed, its own scrollable being further in either way.
 */
fun Modifier.elasticPull(pull: PullState, ink: Color = Ink.OnBackground, ground: Color = Ink.Background): Modifier = this
    // The dots first and the content over them, outside the layer that moves the content:
    // they are behind the page, and the pull uncovers them rather than laying them on top.
    .drawWithContent { pull.dots(this, ink); drawContent() }
    .graphicsLayer { translationY = pull.lift() }
    // Inside the layer, so the page carries its own ground down with it and really does
    // cover the dots. Without it the rows are transparent and the dots show through them.
    .background(ground)
    .nestedScroll(pull.connection)
