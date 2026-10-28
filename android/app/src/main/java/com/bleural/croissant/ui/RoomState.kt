package com.bleural.croissant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The whole shell in four numbers. Nothing else
 * decides what is on screen: the ground's colour, where every object sits, the header
 * block and the tiles are all read off these.
 *
 * - [pos] where the room is, continuous, 0 the first place to the last, Ask at [entry]. It
 *   is not an index: half way between two panels is a real state that the ground and the
 *   objects interpolate.
 * - [open] 0 the panel as it sits in the menu, 1 the feature open on black. Scrubbable,
 *   which is what makes the back gesture a drag of this number rather than a transition.
 * - [home] 1 Home, 0 the room. Home is its own blend, never a flight through the panels.
 * - [pull] the elastic above the first panel, 0 to 1: how far a finger has stretched the
 *   room past Ask, and past a third of it the release lands on Home. It draws nothing.
 *   ~~The two dots every list draws~~ were here too until 2026-09-18, when they came
 *   off this one crossing: Ask to Home is the room's own edge, not a step out of a
 *   page, and it goes up on a single tick of the wheel like any other move between panels.
 *
 * Each runs on its own spring, integrated in one frame loop by [RoomLoop]. Springs, not
 * durations: a tick that arrives mid-flight retargets the spring it is already on and
 * keeps its velocity, so a hurried spin is one continuous motion instead of a queue of
 * animations. The loop suspends entirely once everything has settled, so a still room
 * costs no frames.
 */
class RoomState(
    features: List<Feature> = Feature.room,
    /**
     * Leaving Home by the wheel or the drag up lands on Ask ([entry]) whatever was left
     * (Beam's, 2026-10-17); off, on the place last left, as every other room does.
     */
    private val leavesToEntry: Boolean = false,
) {
    /**
     * The room's places, in order: one per feature it was made with, until a design lays it
     * out again ([lay]); Beam's has its recents above Ask and its pinned apps before Settings.
     */
    var places: List<Place> by mutableStateOf(features.map { Place.Of(it) }); private set

    /** How many places the room has. */
    val count: Int get() = places.size

    /** Where Ask is: the first place but for Beam's recents above it, which come first. */
    var entry by mutableIntStateOf(0); private set

    var pos by mutableFloatStateOf(0f); private set
    var open by mutableFloatStateOf(0f); private set
    var home by mutableFloatStateOf(1f); private set
    var pull by mutableFloatStateOf(0f); private set

    // The targets are snapshot state as well as the values. Anything that asks where the
    // room is going, rather than where it is, has to be told when the answer changes:
    // read as plain fields they were invisible to a derivedStateOf and to a snapshotFlow,
    // so the panel that had been asked for and the object's own `open` both went stale.
    var posTarget by mutableFloatStateOf(0f); private set
    var openTarget by mutableFloatStateOf(0f); private set
    var homeTarget by mutableFloatStateOf(1f); private set
    private var pullTarget = 0f

    /**
     * How hurried the room is, 0 a single tick or a slow hand, 1 a spin. The headers read it
     * to stop flashing through a spin (Motion.kt, headAlpha). A spin is a tick inside HURRY
     * of the one before, so it counts from the second tick; it lasts a little over the gap
     * between them and until the room is within LANDING of where it is going, so the name
     * it lands on comes up as it arrives. A finger reads its own speed instead. ~~Speed
     * smoothed over a quarter second~~ until 2026-09-29: the first names of a spin still
     * flashed while it built, and the landed name sat dim half a second after.
     */
    var rush by mutableFloatStateOf(0f); private set
    private var hurried = false
    private var sinceStep = HURRY
    private var hold = 0f
    private var speed = 0f
    private var lastPos = 0f

    private var posV = 0f
    private var openV = 0f
    private var homeV = 0f
    private var pullV = 0f

    /** True while a finger owns [pos]: the spring is off and the drag writes it directly. */
    var dragging = false; private set

    /** The panel the room would rest on, which is what the header block and the tiles call current. */
    val focused: Feature get() = focusedPlace.feature
    val focusedPlace: Place get() = places[pos.roundToInt().coerceIn(0, count - 1)]

    /**
     * The panel last opened, or being gone to; an Atelier app's place is Atelier's. ~~So
     * leaving Home lands where it was left (2026-09-16)~~: still so in every room but Beam's,
     * whose Home leads to Ask (2026-10-17, [depart]).
     */
    val last: Feature get() = lastPlace.feature
    val lastPlace: Place get() = places[posTarget.roundToInt().coerceIn(0, count - 1)]

    /**
     * Where [f] is in this room, or -1 when it has none: its own place, from Ask down, never
     * its copy among the recents above it.
     */
    fun placeOf(f: Feature): Int = indexFrom(places, Place.Of(f), entry)

    /**
     * A place that opens as something other than its panel: given the place being opened,
     * true when it took the open itself. Beam's apps on the wheel push their page, as a tap on
     * a peg does (2026-10-17).
     */
    var opens: ((Place) -> Boolean)? = null

    /**
     * The room laid out again (Beam's, 2026-10-17): [to] its places, Ask at [ask]. On Home,
     * arrived, the room is put on Ask, so leaving lands there; anywhere else the place it is on
     * keeps it, found again in the new list (one among the recents keeps its distance above
     * Ask), so nothing on screen moves.
     */
    fun lay(to: List<Place>, ask: Int) {
        val here = posTarget.roundToInt().coerceIn(0, count - 1)
        val was = places[here]
        val now = when {
            homeTarget > 0.5f && home > 0.99f -> ask
            here >= entry -> indexFrom(to, was, ask).takeIf { it >= 0 } ?: (here - entry + ask)
            else -> here - entry + ask
        }.coerceIn(0, maxOf(0, to.size - 1))
        places = to
        entry = ask.coerceIn(0, maxOf(0, to.size - 1))
        val shift = (now - here).toFloat()
        if (shift != 0f) { pos += shift; posTarget += shift; lastPos += shift }
        stir()
    }

    private fun indexFrom(list: List<Place>, p: Place, from: Int): Int {
        for (i in from until list.size) if (list[i] == p) return i
        return -1
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private fun stir() { wake.trySend(Unit) }

    /** Suspends until something asks the room to move again; the loop parks here when settled. */
    internal suspend fun awaitWake() = wake.receive()

    internal val settled: Boolean
        get() = !dragging && rush == 0f && sinceStep >= HURRY &&
            near(pos, posTarget, posV) && near(open, openTarget, openV) &&
            near(home, homeTarget, homeV) && near(pull, pullTarget, pullV)

    private fun near(x: Float, t: Float, v: Float) = abs(t - x) < 0.0005f && abs(v) < 0.001f

    // Intents. Each sets a target and stirs the loop; none of them animates anything itself.

    fun goTo(panel: Int) { posTarget = panel.toFloat().coerceIn(0f, count - 1f); stir() }

    /** One wheel tick, or one fling. Retargets from where the spring is now, never queues. */
    fun step(delta: Int) {
        if (sinceStep < HURRY) { hurried = true; hold = minOf(sinceStep * 1.25f, HURRY) }
        sinceStep = 0f
        goTo(posTarget.roundToInt() + delta)
    }

    /**
     * How open panel [i] is: only the panel being opened has an opened pose, its
     * neighbours stay at rest whatever the number is doing.
     */
    fun openOf(i: Int): Float {
        // Both numbers are read whatever the answer, so a reader that tracks this call
        // depends on both. Read inside the branch, a panel that was not the open one
        // depended on nothing at all and never heard that it had become the open one.
        val here = posTarget.roundToInt().coerceIn(0, count - 1)
        val o = open
        return if (here == i) o else 0f
    }

    fun openPanel() {
        if (opens?.invoke(lastPlace) == true) return
        openTarget = 1f; stir()
    }
    fun closePanel() { openTarget = 0f; stir() }

    /** Used by the back drag, which writes [open] straight from the finger. */
    fun scrubOpen(v: Float) { open = v.coerceIn(0f, 1f); openV = 0f; openTarget = open; stir() }

    fun goHome() { homeTarget = 1f; openTarget = 0f; stir() }
    fun leaveHome() { homeTarget = 0f; stir() }

    /**
     * Leaving Home by the wheel, as a tap on a place does not: in Beam's room onto Ask
     * whatever was left (2026-10-17). It is there already once Home has arrived ([lay]); this
     * is for a tick that comes before.
     */
    fun depart() {
        if (leavesToEntry) posTarget = entry.toFloat()
        leaveHome()
    }

    /**
     * Home under a finger: the drag up out of Home, and the drag in from the left edge
     * back to it. Like [scrubOpen] it writes the number and parks the spring on it, so
     * releasing is the only thing that picks a destination.
     */
    fun dragHome(v: Float) { home = v.coerceIn(0f, 1f); homeV = 0f; homeTarget = home; stir() }
    fun settleHome(toHome: Boolean) {
        // The drag up out of Home lets go into the room: as [depart], Beam's onto Ask.
        if (!toHome && leavesToEntry) posTarget = entry.toFloat()
        homeTarget = if (toHome) 1f else 0f; stir()
    }

    /**
     * The left edge from panel [from] back to Home: Home is one panel above Ask, so the room
     * rides up (or, from a recent above Ask, down) to Ask with it, the whole way at [v] 1.
     * ~~Home alone, the room left where it was~~ until 2026-09-22: at rest on Home the panel
     * above [from] then sat in full view on it.
     */
    fun dragHomeFrom(from: Float, v: Float) {
        dragHome(v)
        pos = entry + (from - entry) * (1f - home); posV = 0f; posTarget = pos
    }

    /** Releasing that drag: on to Home and Ask under it, or back to the panel it left. */
    fun settleHomeFrom(from: Float, toHome: Boolean) {
        posTarget = if (toHome) entry.toFloat() else from.roundToInt().coerceIn(0, count - 1).toFloat()
        homeTarget = if (toHome) 1f else 0f; stir()
    }

    fun releasePull() { pullTarget = 0f; stir() }

    /** The side button: Ask, open, from anywhere, cutting whatever is running (plan, Motion). */
    fun cutToAsk() {
        posTarget = entry.toFloat(); homeTarget = 0f; openTarget = 1f
        snap()
    }

    /** Sleep snaps what is running to its end, so waking replays nothing. */
    fun snap() {
        // A finger still on the panel loses the room. Without this the drag loop goes on
        // writing `pos` after the cut, because a gesture decides what it is at touch-down
        // and never looks again: hold the side button with a thumb down and Ask opens,
        // then the thumb walks the room out from under it while the microphone is live.
        dragging = false
        pos = posTarget; open = openTarget; home = homeTarget; pull = pullTarget
        posV = 0f; openV = 0f; homeV = 0f; pullV = 0f
        // A cut is not a spin: the jump must not read as speed.
        lastPos = pos; speed = 0f; rush = 0f; hurried = false; sinceStep = HURRY
        stir()
    }

    // The drag owns pos directly, with the elastic above the first panel.

    fun dragStart() { dragging = true; posV = 0f; stir() }

    /**
     * [raw] is where the finger says the room is; above the first panel it stretches
     * instead. [panelPx] is the panel's own height in panel pixels, which is what turns
     * [raw] back into the finger's travel: the room asks the same rubber band every list
     * in the app asks (ui/Pull.kt, backPull), so the dots need the same pull here
     * as they do at the top of a list. ~~A flat `-raw * 2.4`~~ until 2026-09-18, when
     * the band replaced it and the room's own stretch came to be read off the band too,
     * so it stiffens and settles instead of following the finger a quarter of the way.
     */
    fun dragTo(raw: Float, panelPx: Float) {
        if (raw < 0f) {
            pull = backPull(-raw * panelPx)
            pos = -pull * LIFT_FULL / panelPx
        } else {
            pull = 0f
            pos = raw.coerceAtMost(count - 1f)
        }
        // The finger owns the pull while it is down, as it owns `pos`. Left to spring at a
        // target of zero it ate the whole stretch in about a tenth of a second, so holding
        // still at the top of the room lost the dots and the release then went nowhere.
        pullV = 0f
        pullTarget = pull
        stir()
    }

    /** [velocity] is panels a second; a fling moves at most one panel, and never wraps. */
    fun dragEnd(from: Int, velocity: Float) {
        dragging = false
        // A hurried finger lands as a spin does, dim until the room is nearly there.
        hurried = rush > 0.5f; hold = 0f
        if (pull >= DOT_TWO) { goHome(); releasePull(); return }
        releasePull()
        val flung = (pos + velocity * 0.12f).roundToInt()
        posTarget = flung.coerceIn(from - 1, from + 1).coerceIn(0, count - 1).toFloat()
        posV = velocity
        stir()
    }

    /** One frame of every spring. [dt] is seconds, clamped by the loop. */
    internal fun advance(dt: Float) {
        if (!dragging) {
            posV = spring(pos, posTarget, posV, K_POS, D_POS, dt).also { pos += it * dt }
        }
        openV = spring(open, openTarget, openV, K_OPEN, 1f, dt).also { open += it * dt }
        homeV = spring(home, homeTarget, homeV, K_HOME, D_HOME, dt).also { home += it * dt }
        pullV = spring(pull, pullTarget, pullV, K_PULL, 1f, dt).also { pull += it * dt }
        if (!dragging && near(pos, posTarget, posV)) { pos = posTarget; posV = 0f }
        if (near(open, openTarget, openV)) { open = openTarget; openV = 0f }
        if (near(home, homeTarget, homeV)) { home = homeTarget; homeV = 0f }
        if (near(pull, pullTarget, pullV)) { pull = pullTarget; pullV = 0f }
        // Measured off pos itself, so a finger dragging the room counts as the springs do.
        sinceStep = (sinceStep + dt).coerceAtMost(HURRY)
        val moved = (abs(pos - lastPos) / dt).coerceAtMost(RUSH_CAP)
        lastPos = pos
        speed = if (dragging) speed + (moved - speed) * (1f - exp(-dt / RUSH_TAU)) else 0f
        val want = when {
            dragging -> smooth(((speed - RUSH_FROM) / (RUSH_TO - RUSH_FROM)).coerceIn(0f, 1f))
            hurried && (sinceStep < hold || abs(posTarget - pos) > LANDING) -> 1f
            else -> { hurried = false; 0f }
        }
        rush += (want - rush) * (1f - exp(-dt / RUSH_FOLLOW))
        if (want == 0f && rush < 0.01f) rush = 0f
    }

    private fun spring(x: Float, target: Float, v: Float, k: Float, damping: Float, dt: Float): Float {
        val c = 2f * sqrt(k) * damping
        return v + ((target - x) * k - c * v) * dt
    }

    private companion object {
        /** One tick is one panel, settling in about 280 ms with a slight overshoot. */
        const val K_POS = 170f
        const val D_POS = 0.78f
        /** [rush]: ticks closer than HURRY seconds are a spin; it eases in and out over RUSH_FOLLOW. */
        const val HURRY = 0.35f
        const val LANDING = 0.5f
        const val RUSH_FOLLOW = 0.04f
        /** A finger's rush: panels a second smoothed over RUSH_TAU, calm below FROM, all of it at TO. */
        const val RUSH_TAU = 0.25f
        const val RUSH_FROM = 2.8f
        const val RUSH_TO = 4.8f
        const val RUSH_CAP = 15f
        /** Ground to black in about 200 ms, critically damped: opening must not wobble. */
        const val K_OPEN = 240f
        /** Home is a slower, softer blend; it is a change of place, not of selection. */
        const val K_HOME = 110f
        const val D_HOME = 0.95f
        /** The elastic snaps back hard, so a stretch that is let go reads as a rubber band. */
        const val K_PULL = 300f
    }
}
