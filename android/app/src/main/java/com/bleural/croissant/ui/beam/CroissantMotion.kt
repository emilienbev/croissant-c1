package com.bleural.croissant.ui.beam

/*
 * The croissant's motion as parts that compose. A move is keyframes for a few of its parts; a
 * glance sends the eyes somewhere; a timeline is moves and glances at frames, played once or
 * looped. A [CroissantRig] plays any number of them at once. Keys are at 60 fps, each carrying the
 * cubic-bezier curve to the next. What the croissant plays is in CroissantMoves.kt.
 */

/** Frames a second, the rate every track is keyed at. */
internal const val MOTION_FPS = 60f

/** A cubic-bezier timing curve from (0, 0) to (1, 1), as CSS and Rive key it; y may overshoot. */
internal class Curve(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    /** Progress at [x], 0 to 1 of the time between two keys. */
    fun at(x: Float): Float {
        val cx = 3 * x1; val bx = 3 * (x2 - x1) - cx; val ax = 1 - cx - bx
        val cy = 3 * y1; val by = 3 * (y2 - y1) - cy; val ay = 1 - cy - by
        var lo = 0f; var hi = 1f; var u = x
        repeat(40) {
            val xu = ((ax * u + bx) * u + cx) * u
            if (kotlin.math.abs(xu - x) < 1e-6f) return ((ay * u + by) * u + cy) * u
            if (xu < x) lo = u else hi = u
            u = (lo + hi) / 2
        }
        return ((ay * u + by) * u + cy) * u
    }
}

/** A key: its [frame], its [value], and the [curve] to the next key; none holds the value. */
internal class Key(val frame: Float, val value: Float, val curve: Curve? = null)

/** One value's keys, the first at frame 0; the last one's frame is the track's length. */
internal class Track(private vararg val keys: Key) {
    init {
        require(keys.size >= 2 && keys.first().frame == 0f) { "a track starts at frame 0 and has two keys at least: ${keys.map { it.frame }}" }
        keys.toList().zipWithNext().forEach { (a, b) ->
            require(a.frame < b.frame) { "frame ${b.frame} comes after frame ${a.frame}" }
            require(a.curve != null || a.value == b.value) { "frame ${a.frame} holds ${a.value} but frame ${b.frame} is ${b.value}" }
        }
    }

    val frames get() = keys.last().frame
    val first get() = keys.first().value
    val last get() = keys.last().value

    /** The value at [frame]; past the end it holds the last key. */
    fun at(frame: Float): Float {
        for (i in 0 until keys.size - 1) {
            val a = keys[i]; val b = keys[i + 1]
            if (frame >= a.frame && frame < b.frame) {
                val c = a.curve ?: return a.value
                return a.value + (b.value - a.value) * c.at((frame - a.frame) / (b.frame - a.frame))
            }
        }
        return keys.last().value
    }
}

/**
 * A part of the croissant a move can turn. Turns, the hover and the left ear's drop down its side
 * (degrees, pixels) add up when two moves play on them at once; the stretches, the squash about
 * the foot, the eyes' size and the blink are factors and multiply.
 */
internal enum class Channel(val multiplies: Boolean) {
    Hover(false), SideL(false), SideR(false), EarL(false), EarR(false), EarLDown(false),
    EarLStretch(true), EarRStretch(true), Squash(true), EyeSize(true), Blink(true);

    /** The value with no move on it. */
    val rest get() = if (multiplies) 1f else 0f
}

/** One channel's keys in a move. */
internal class Lane(val channel: Channel, val track: Track)

/** Keyframes for some of the croissant's parts, from frame 0 of the move. */
internal class Move(val name: String, vararg val lanes: Lane) {
    val frames = lanes.maxOf { it.track.frames }

    /** Starts and ends at rest on every lane, so it can play once from any moment without a jump. */
    val rests get() = lanes.all { it.track.first == it.channel.rest && it.track.last == it.channel.rest }

    /** Ends where it starts on every lane, so it can loop without a seam. */
    val seamless get() = lanes.all { it.track.first == it.track.last }
}

/**
 * Where the eyes look, in the face's own units (x along the face, y down it), and how much they
 * flatten along each of those axes looking there: the face is angled, so a pupil turned away
 * from the viewer narrows.
 */
internal data class Gaze(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float) {
    /** [t] of the way from here to [to]; past 1 overshoots. */
    fun toward(to: Gaze, t: Float) = Gaze(x + (to.x - x) * t, y + (to.y - y) * t, scaleX + (to.scaleX - scaleX) * t, scaleY + (to.scaleY - scaleY) * t)

    companion object {
        /** Straight out of the face, round. */
        val AHEAD = Gaze(0f, 0f, 1f, 1f)
    }
}

/** The eyes keyed over time: where they look and how they flatten, one track each. */
internal class GazeTrack(x: Track, y: Track, scaleX: Track, scaleY: Track) {
    val tracks = listOf(x, y, scaleX, scaleY)
    val frames get() = tracks.maxOf { it.frames }

    fun at(frame: Float) = Gaze(tracks[0].at(frame), tracks[1].at(frame), tracks[2].at(frame), tracks[3].at(frame))
}

/**
 * A move that can take parts of the croissant over. The channels in [takes] are the act's alone
 * while it plays, and its [gaze], when it has one, has the eyes; its other lanes add or multiply
 * onto whatever else plays. It fades in over [fadeIn] frames. Held (it has a [loop]), it plays up
 * to the loop once, repeats the loop until let go and fades out over [fadeOut] frames after; once,
 * it runs its lanes and fades out over their last [fadeOut]. A [voice] moves channels so much per
 * unit of level: the act's own [level] track, or without one what the rig hears
 * ([CroissantRig.heard]). [then] starts when a held act is let go.
 */
internal class CroissantAct(
    val name: String,
    val lanes: List<Lane>,
    val fadeIn: Float,
    val fadeOut: Float,
    val loop: ClosedFloatingPointRange<Float>? = null,
    val takes: Set<Channel> = emptySet(),
    val gaze: GazeTrack? = null,
    val voice: Map<Channel, Float> = emptyMap(),
    val level: Track? = null,
    val then: CroissantAct? = null,
) {
    /** A held act's loop end, a once act's length: where every track ends. */
    val frames: Float = loop?.endInclusive ?: (lanes.map { it.track.frames } + listOfNotNull(gaze?.frames)).max()

    init {
        require(fadeOut > 0f) { "$name: fades out over a frame at least, not $fadeOut" }
        require(level == null || voice.isNotEmpty()) { "$name: a level track moves nothing without a voice" }
        for (c in takes) require(lanes.any { it.channel == c }) { "$name takes $c but has no lane for it" }
        val tracks = lanes.map { it.channel.name to it.track } + listOfNotNull(level?.let { "level" to it }) +
            gaze?.tracks.orEmpty().map { "gaze" to it }
        for ((what, t) in tracks) require(t.frames == frames) { "$name.$what: runs to ${t.frames}, not $frames" }
        for (lane in lanes) {
            if (lane.channel in takes) continue
            require(lane.track.first == lane.channel.rest) { "$name.${lane.channel}: starts at ${lane.track.first}, not at rest" }
            if (loop == null) require(lane.track.last == lane.channel.rest) { "$name.${lane.channel}: once, it ends at rest, not at ${lane.track.last}" }
        }
        loop?.let { l ->
            for ((what, t) in tracks) require(t.at(l.start) == t.last) { "$name.$what: the loop starts at ${t.at(l.start)} and ends at ${t.last}" }
        }
    }
}

/** What a timeline does at one of its frames. */
internal sealed interface Action

/** Plays [move] once. */
internal class Play(val move: Move) : Action {
    init { require(move.rests) { "${move.name} has to start and end at rest to play once" } }
}

/** Sends the eyes from wherever they are to [to] over [frames], along [curve]. */
internal class Glance(val to: Gaze, val frames: Float, val curve: Curve) : Action {
    init { require(frames > 0f) { "a glance takes a frame at least: $frames" } }

    /** The eyes [t] frames into the glance, having started at [from]. */
    fun at(from: Gaze, t: Float) = if (t >= frames) to else from.toward(to, curve.at(t / frames))
}

/** [action] at [frame] of a timeline. */
internal class Cue(val frame: Float, val action: Action)

/** Cues over [frames], in order; looped, it starts again at [frames]. */
internal class Timeline(val frames: Float, vararg val cues: Cue) {
    init {
        require(cues.isNotEmpty()) { "a timeline has a cue at least" }
        require(cues.all { it.frame >= 0f && it.frame < frames }) { "every cue falls in 0 until $frames: ${cues.map { it.frame }}" }
        require(cues.toList().zipWithNext().all { (a, b) -> a.frame <= b.frame }) { "cues in order: ${cues.map { it.frame }}" }
    }
}

/**
 * Where every part of the croissant is at one moment, as the drawing takes it. Rotations in
 * degrees, clockwise; the hover and the ear's drop in the drawing's pixels; the squash a factor
 * on the body's height about its foot; the eyes as a [Gaze], with their size in both scales and
 * the blink in [eyeScaleY].
 */
internal data class CroissantPose(
    val hoverY: Float,
    val squash: Float,
    val sideL: Float,
    val sideR: Float,
    val earL: Float,
    val earLStretch: Float,
    val earLDown: Float,
    val earR: Float,
    val earRStretch: Float,
    val eyeX: Float,
    val eyeY: Float,
    val eyeScaleX: Float,
    val eyeScaleY: Float,
)
