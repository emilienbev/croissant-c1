package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.bleural.croissant.ui.LocalLayerVisible

/**
 * Plays the croissant: any number of moves and timelines at once, each from the frame it was
 * started, summed into one pose for the drawing. Two moves on one part add up or multiply as its
 * [Channel] says; the eyes go wherever the latest glance sends them. [CroissantAct]s go over
 * all of that, taking the parts they name. A cue starts on its own frame however far one step
 * of the clock jumps, so the pose never depends on the frame rate. Main thread only.
 */
@Stable
internal class CroissantRig {
    /** The level an act's voice follows when it has no level track of its own, 0 to 1: the mic, say. Read in [pose]. */
    var heard: () -> Float = { 0f }

    /** Frames played, counted only while the croissant is in view. Read by [pose], so a step redraws. */
    var frame by mutableDoubleStateOf(0.0); private set

    private class Playing(val move: Move, val from: Double, val loops: Boolean)
    private class Running(val timeline: Timeline, var cycle: Double, val loops: Boolean) { var next = 0 }
    /** An act from frame [from]; [letGo] is when a held one was let go, NaN while it holds. */
    private class Acting(val act: CroissantAct, val from: Double) { var letGo = Double.NaN }

    private val playing = ArrayList<Playing>()
    private val running = ArrayList<Running>()
    private val acting = ArrayList<Acting>()

    // The glance the eyes are on: where it started from, and when.
    private var gazeFrom = Gaze.AHEAD
    private var glance: Glance? = null
    private var glanceStart = 0.0

    /** Plays [move] once, now, on top of whatever plays. */
    fun play(move: Move) = start(Play(move), frame)

    /** Plays [move] over and over from now. */
    fun loop(move: Move) {
        require(move.seamless) { "${move.name} has to end where it starts to loop" }
        playing += Playing(move, frame, loops = true)
    }

    /** Sends the eyes, now, from wherever they are. */
    fun glance(glance: Glance) = start(glance, frame)

    /** Plays [timeline]'s cues once from now. */
    fun play(timeline: Timeline) = run(Running(timeline, frame, loops = false))

    /** Plays [timeline]'s cues over and over from now. */
    fun loop(timeline: Timeline) = run(Running(timeline, frame, loops = true))

    /** Starts [act] now, laid over whatever plays; one already playing starts again from its top rather than twice over. */
    fun perform(act: CroissantAct) {
        acting.removeAll { it.act === act }
        acting += Acting(act, frame)
    }

    /** Lets a held [act] go: it fades out, and the act it is followed by starts. Nothing if it is not holding. */
    fun letGo(act: CroissantAct) {
        val a = acting.find { it.act === act && it.letGo.isNaN() } ?: return
        a.letGo = frame
        act.then?.let(::perform)
    }

    /** Whether [act] is held and not let go. */
    fun holds(act: CroissantAct) = acting.any { it.act === act && it.loops && it.letGo.isNaN() }

    /** Moves the clock on by [frames], starting every cue it passes on that cue's own frame. */
    fun advance(frames: Double) {
        val to = frame + frames
        fire(until = to)
        frame = to
        playing.removeAll { !it.loops && frame - it.from >= it.move.frames }
        acting.removeAll {
            if (it.loops) !it.letGo.isNaN() && frame - it.letGo >= it.act.fadeOut else frame - it.from >= it.act.frames
        }
    }

    /** The pose now, the eyes [weight] of the way to [held] (the day's look down), the blink on top. */
    fun pose(held: Gaze = Gaze.AHEAD, weight: Float = 0f): CroissantPose {
        val now = frame
        val v = FloatArray(CHANNELS.size) { CHANNELS[it].rest }
        for (p in playing) {
            val t = (if (p.loops) (now - p.from) % p.move.frames else now - p.from).toFloat()
            for (lane in p.move.lanes) {
                val i = lane.channel.ordinal
                val x = lane.track.at(t)
                v[i] = if (lane.channel.multiplies) v[i] * x else v[i] + x
            }
        }
        var eyes = gazeAt(now).toward(held, weight)
        // The acts over all of that, oldest first, so the newest one's look wins.
        for (a in acting) {
            val act = a.act
            val f = a.frameAt(now)
            val r = a.lasting(now)
            val w = a.weight(now) * r
            for (lane in act.lanes) {
                val i = lane.channel.ordinal
                val x = lane.track.at(f)
                v[i] = when {
                    lane.channel in act.takes -> v[i] + (x - v[i]) * w
                    lane.channel.multiplies -> v[i] * (1 + (x - 1) * r)
                    else -> v[i] + x * r
                }
            }
            if (act.voice.isNotEmpty()) {
                val level = act.level?.at(f) ?: heard().coerceIn(0f, 1f)
                for ((c, k) in act.voice) v[c.ordinal] = if (c.multiplies) v[c.ordinal] * (1 + k * level * r) else v[c.ordinal] + k * level * r
            }
            act.gaze?.let { eyes = eyes.toward(it.at(f), w) }
        }
        val size = v[Channel.EyeSize.ordinal]
        return CroissantPose(
            hoverY = v[Channel.Hover.ordinal],
            squash = v[Channel.Squash.ordinal],
            sideL = v[Channel.SideL.ordinal],
            sideR = v[Channel.SideR.ordinal],
            earL = v[Channel.EarL.ordinal],
            earLStretch = v[Channel.EarLStretch.ordinal],
            earLDown = v[Channel.EarLDown.ordinal],
            earR = v[Channel.EarR.ordinal],
            earRStretch = v[Channel.EarRStretch.ordinal],
            eyeX = eyes.x,
            eyeY = eyes.y,
            eyeScaleX = eyes.scaleX * size,
            eyeScaleY = eyes.scaleY * size * v[Channel.Blink.ordinal],
        )
    }

    private val Acting.loops get() = act.loop != null

    /** The act's own frame at [now]: a held act past its loop's end goes round the loop. */
    private fun Acting.frameAt(now: Double): Float {
        val f = (now - from).toFloat()
        val l = act.loop ?: return f
        return if (f >= l.endInclusive) l.start + (f - l.start) % (l.endInclusive - l.start) else f
    }

    /** How much of the act's channels it has taken: in over its fade in, and a once act out over its last frames. */
    private fun Acting.weight(now: Double): Float {
        val f = (now - from).toFloat()
        var w = if (act.fadeIn > 0f) fade(f / act.fadeIn) else 1f
        if (!loops) w *= 1 - fade((f - (act.frames - act.fadeOut)) / act.fadeOut)
        return w
    }

    /** How much of a held act still counts after it was let go, 1 until then; a once act's is always 1. */
    private fun Acting.lasting(now: Double): Float =
        if (letGo.isNaN()) 1f else 1 - fade(((now - letGo) / act.fadeOut).toFloat())

    private fun run(r: Running) {
        running += r
        fire(until = frame)   // a cue on the timeline's frame 0 starts with it
    }

    private fun start(action: Action, at: Double) {
        when (action) {
            is Play -> playing += Playing(action.move, at, loops = false)
            is Glance -> { gazeFrom = gazeAt(at); glance = action; glanceStart = at }
        }
    }

    private fun gazeAt(at: Double) = glance?.at(gazeFrom, (at - glanceStart).toFloat()) ?: gazeFrom

    /** Starts every cue due by [until], earliest first, so a glance starts from where the one before had the eyes. */
    private fun fire(until: Double) {
        val due = ArrayList<Pair<Double, Action>>()
        for (r in running) {
            val cues = r.timeline.cues
            while (true) {
                if (r.next == cues.size) {
                    if (!r.loops) break
                    r.cycle += r.timeline.frames
                    r.next = 0
                }
                val at = r.cycle + cues[r.next].frame
                if (at > until) break
                due += at to cues[r.next].action
                r.next++
            }
        }
        running.removeAll { !it.loops && it.next == it.timeline.cues.size }
        due.sortBy { it.first }
        due.forEach { (at, action) -> start(action, at) }
    }
}

private val CHANNELS = Channel.entries

/** An act's fade in and out, ease-in-out-cubic over 0 to 1. */
private val FADE = Curve(.65f, 0f, .35f, 1f)
private fun fade(x: Float) = FADE.at(x.coerceIn(0f, 1f))

/**
 * Plays [rig] on the frame clock while the screen it is drawn on is in view, held where it was
 * otherwise, as the second tick is. Read its pose in a draw lambda only, so a step redraws the
 * croissant and recomposes nothing. A moving croissant costs a composite of the whole window
 * every frame (PERFORMANCE.md).
 */
@Composable
internal fun PlayCroissant(rig: CroissantRig) {
    val visible = LocalLayerVisible.current
    LaunchedEffect(rig, visible) {
        if (!visible) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // A frame clock that paused (the screen off) resumes where it was rather than leaping.
                if (last != 0L) rig.advance(((now - last) / 1e9).coerceAtMost(MAX_STEP_S) * MOTION_FPS)
                last = now
            }
        }
    }
}

/** The longest step one frame may take, in seconds: two frames at 60. */
private const val MAX_STEP_S = 0.034
