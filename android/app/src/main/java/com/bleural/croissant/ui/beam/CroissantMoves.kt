package com.bleural.croissant.ui.beam

/*
 * What the croissant plays. Idle v3 (2026-10-27) taken apart into the moves it is made of:
 * [Moves.breathe] loops under everything, and [IdleV3.lookAround] darts the eyes, blinks and
 * twitches the ear on v3's 360 frames. Played together they are v3 to the frame, which
 * CroissantMotionTest checks. [AskActs] are what it acts while you ask.
 */

private val SINE = Curve(.37f, 0f, .63f, 1f)       // ease-in-out-sine: extreme to extreme
private val SINE_IN = Curve(.12f, 0f, .39f, 0f)    // its halves, for a loop whose keys
private val SINE_OUT = Curve(.61f, 1f, .88f, 1f)   // sit on the middle crossing
private val FLICK = Curve(.22f, 1f, .36f, 1f)      // ease-out-quint: the ear's flick
private val SOFT = Curve(.65f, 0f, .35f, 1f)       // ease-in-out-cubic: wind-up and settle
private val DART = Curve(.34f, 1.56f, .64f, 1f)    // ease-out-back: an eye dart lands just past and settles
private val SHUT = Curve(.32f, 0f, .67f, 0f)       // ease-in-cubic: a lid speeding up as it closes
private val OPEN = Curve(.33f, 1f, .68f, 1f)       // ease-out-cubic: a lid slowing as it opens

private fun k(frame: Int, value: Float, curve: Curve? = null) = Key(frame.toFloat(), value, curve)
private fun cue(frame: Int, action: Action) = Cue(frame.toFloat(), action)

/** Where v3 sends the eyes, each with the flattening that direction gives them on the face. */
internal object Gazes {
    val UP_LEFT = Gaze(-5f, -4f, .84f, .9f)
    val UP_RIGHT = Gaze(5f, -4f, .84f, .9f)

    /** Down the face: v3's last dart, and the look the opened day holds. */
    val DOWN = Gaze(0f, 5f, 1f, .84f)
}

/** A glance as v3 makes them: [frames] long, landing just past [to] and settling. */
internal fun dart(to: Gaze, frames: Int = 5) = Glance(to, frames.toFloat(), DART)

internal object Moves {
    /** The idle's ground, looped: a 4 px hover every 3 s, the ends rocking 1 and 0.8 degrees every 6 s, the ears swaying every 3 s. */
    val breathe = Move(
        "breathe",
        Lane(Channel.Hover, Track(
            k(0, 0f, SINE_OUT), k(45, -4f, SINE_IN), k(90, 0f, SINE_OUT), k(135, 4f, SINE_IN),
            k(180, 0f, SINE_OUT), k(225, -4f, SINE_IN), k(270, 0f, SINE_OUT), k(315, 4f, SINE_IN), k(360, 0f),
        )),
        Lane(Channel.SideL, Track(k(0, 1f, SINE), k(180, -1f, SINE), k(360, 1f))),
        Lane(Channel.SideR, Track(k(0, .8f, SINE), k(180, -.8f, SINE), k(360, .8f))),
        Lane(Channel.EarL, Track(k(0, 2f, SINE), k(90, -2f, SINE), k(180, 2f, SINE), k(270, -2f, SINE), k(360, 2f))),
        Lane(Channel.EarR, Track(k(0, -1.6f, SINE), k(90, 1.6f, SINE), k(180, -1.6f, SINE), k(270, 1.6f, SINE), k(360, -1.6f))),
    )

    /** The left ear's twitch: a wind-up, a flick back with a stretch, and a settle. */
    val flutterEar = Move(
        "flutterEar",
        Lane(Channel.EarL, Track(k(0, 0f, SOFT), k(6, 1.5f, FLICK), k(11, -7f, SOFT), k(19, 1.8f, SOFT), k(27, -.45f, SOFT), k(34, 0f))),
        Lane(Channel.EarLStretch, Track(k(0, 1f, SOFT), k(6, .97f, FLICK), k(11, 1.05f, SOFT), k(19, .985f, SOFT), k(27, 1f))),
    )

    /** Both eyes shut in three frames, held one, and open in five. */
    val blink = Move("blink", Lane(Channel.Blink, Track(k(0, 1f, SHUT), k(3, .08f), k(4, .08f, OPEN), k(9, 1f))))
}

/** Idle v3: [Moves.breathe] under [lookAround], both looped. */
internal object IdleV3 {
    const val FRAMES = 360f

    /** Up-left, up-right, a blink on the dart back, down, ahead, the ear, a lone blink. */
    val lookAround = Timeline(
        FRAMES,
        cue(30, dart(Gazes.UP_LEFT)),
        cue(84, dart(Gazes.UP_RIGHT, frames = 6)),
        cue(136, Play(Moves.blink)),
        cue(138, dart(Gaze.AHEAD)),
        cue(186, dart(Gazes.DOWN)),
        cue(246, dart(Gaze.AHEAD)),
        cue(264, Play(Moves.flutterEar)),
        cue(306, Play(Moves.blink)),
    )

    fun rig() = CroissantRig().apply { loop(Moves.breathe); loop(lookAround) }
}

private fun lane(channel: Channel, vararg keys: Key) = Lane(channel, Track(*keys))
private fun track(vararg keys: Key) = Track(*keys)

/**
 * What the croissant acts while you ask (2026-10-28): it listens while you hold, thinks until the
 * answer comes, answers while it comes and nods when it is done. Ear turns count from the
 * aerodynamic ears' rest.
 */
internal object AskActs {
    /**
     * Ears up, the left sunk a little so it stands as tall as the right, eyes wide and up at you;
     * the hover stops. One ear then the other turns as if to a sound. Its ears and a small bob
     * follow your voice as the rig hears it (2026-10-28).
     */
    val listening = CroissantAct(
        "listening",
        listOf(
            lane(Channel.EarL, k(0, 0f, FLICK), k(8, -30f, SOFT), k(15, -27f), k(60, -27f, SOFT), k(64, -30f, SOFT), k(72, -27f), k(135, -27f)),
            lane(Channel.EarLDown, k(0, 0f, FLICK), k(8, 14f), k(135, 14f)),
            lane(Channel.EarR, k(0, 0f, FLICK), k(9, -33f, SOFT), k(15, -30f), k(100, -30f, SOFT), k(104, -33f, SOFT), k(112, -30f), k(135, -30f)),
            lane(Channel.EarLStretch, k(0, 1f, FLICK), k(8, 1.07f, SOFT), k(15, 1.05f), k(135, 1.05f)),
            lane(Channel.EarRStretch, k(0, 1f, FLICK), k(8, 1.07f, SOFT), k(15, 1.05f), k(135, 1.05f)),
            lane(Channel.Squash, k(0, 1f, FLICK), k(8, 1.04f, SOFT), k(15, 1.025f), k(135, 1.025f)),
            lane(Channel.Hover, k(0, 0f, SOFT), k(12, -3f), k(135, -3f)),
            lane(Channel.SideL, k(0, 0f), k(135, 0f)),
            lane(Channel.SideR, k(0, 0f), k(135, 0f)),
            lane(Channel.EyeSize, k(0, 1f, DART), k(6, 1.12f), k(135, 1.12f)),
        ),
        fadeIn = 6f, fadeOut = 14f, loop = 15f..135f,
        takes = setOf(Channel.Hover, Channel.SideL, Channel.SideR, Channel.EarL, Channel.EarR),
        voice = mapOf(Channel.Hover to -3f, Channel.EarL to -4f, Channel.EarR to -3f),
        gaze = GazeTrack(
            track(k(0, 0f, DART), k(6, 2.5f), k(135, 2.5f)),
            track(k(0, 0f, DART), k(6, -4f), k(135, -4f)),
            track(k(0, 1f, DART), k(6, .94f), k(135, .94f)),
            track(k(0, 1f, DART), k(6, .9f), k(135, .9f)),
        ),
    )

    /** The eyes up to one side, then darting to the other every 0.8 s, the ends rocking slowly, the left ear twitching. */
    val thinking = CroissantAct(
        "thinking",
        listOf(
            lane(Channel.EarL, k(0, 0f), k(120, 0f, SOFT), k(126, 1.5f, FLICK), k(131, -7f, SOFT), k(139, 1.8f, SOFT), k(147, -.45f, SOFT), k(154, 0f), k(200, 0f)),
            lane(Channel.EarLStretch, k(0, 1f), k(120, 1f, SOFT), k(126, .97f, FLICK), k(131, 1.05f, SOFT), k(139, .985f, SOFT), k(147, 1f), k(200, 1f)),
            lane(Channel.SideL, k(0, 0f), k(8, 0f, SINE_OUT), k(56, 1.4f, SINE_IN), k(104, 0f, SINE_OUT), k(152, -1.4f, SINE_IN), k(200, 0f)),
            lane(Channel.SideR, k(0, 0f), k(8, 0f, SINE_OUT), k(56, 1.1f, SINE_IN), k(104, 0f, SINE_OUT), k(152, -1.1f, SINE_IN), k(200, 0f)),
        ),
        fadeIn = 4f, fadeOut = 10f, loop = 8f..200f,
        gaze = GazeTrack(
            track(k(0, 0f, DART), k(6, -4f), k(50, -4f, DART), k(55, 4f), k(98, 4f, DART), k(103, -4f), k(146, -4f, DART), k(151, 4f),
                k(194, 4f, DART), k(199, -4f), k(200, -4f)),
            track(k(0, 0f, DART), k(6, -3.5f), k(200, -3.5f)),
            track(k(0, 1f, DART), k(6, .88f), k(200, .88f)),
            track(k(0, 1f, DART), k(6, .92f), k(200, .92f)),
        ),
    )

    /** The end of an answer: a small nod, the eyes dipping and coming back ahead. */
    val nod = CroissantAct(
        "nod",
        listOf(
            lane(Channel.Hover, k(0, 0f, SOFT), k(9, 5f, SOFT), k(18, -1.5f, SOFT), k(26, 0f), k(34, 0f)),
            lane(Channel.Squash, k(0, 1f, SOFT), k(9, .95f, SOFT), k(18, 1.02f, SOFT), k(26, 1f), k(34, 1f)),
            lane(Channel.EarL, k(0, 0f, SOFT), k(9, 5f, SOFT), k(20, -1f, SOFT), k(28, 0f), k(34, 0f)),
            lane(Channel.EarR, k(0, 0f, SOFT), k(10, 4f, SOFT), k(21, -1f, SOFT), k(29, 0f), k(34, 0f)),
        ),
        fadeIn = 0f, fadeOut = 10f,
        gaze = GazeTrack(
            track(k(0, 1.5f, SOFT), k(12, 0f), k(34, 0f)),
            track(k(0, -2f, SOFT), k(9, 3f, SOFT), k(20, 0f), k(34, 0f)),
            track(k(0, .96f, SOFT), k(12, 1f), k(34, 1f)),
            track(k(0, .95f, SOFT), k(9, .9f, SOFT), k(20, 1f), k(34, 1f)),
        ),
    )

    /**
     * While the answer comes: the body bobs and the ears lift with a voice, the eyes on you, then
     * the nod. The C1 does not speak its answers, so the voice is a keyed level of its own.
     */
    val answering = CroissantAct(
        "answering",
        emptyList(),
        fadeIn = 8f, fadeOut = 12f, loop = 8f..188f,
        gaze = GazeTrack(
            track(k(0, 0f, SOFT), k(8, 1.5f), k(188, 1.5f)),
            track(k(0, 0f, SOFT), k(8, -2f), k(188, -2f)),
            track(k(0, 1f, SOFT), k(8, .96f), k(188, .96f)),
            track(k(0, 1f, SOFT), k(8, .95f), k(188, .95f)),
        ),
        voice = mapOf(Channel.Hover to -4f, Channel.Squash to .035f, Channel.EarL to -5f, Channel.EarR to -4f),
        level = track(
            k(0, 0f), k(8, 0f, SOFT), k(11, .8f, SOFT), k(17, .2f, SOFT), k(20, .6f, SOFT), k(27, .1f, SOFT), k(31, 1f, SOFT),
            k(38, .3f, SOFT), k(41, .7f, SOFT), k(49, 0f), k(60, 0f, SOFT), k(63, .9f, SOFT), k(69, .3f, SOFT), k(72, .5f, SOFT),
            k(78, .2f, SOFT), k(82, 1f, SOFT), k(90, .4f, SOFT), k(94, .8f, SOFT), k(102, .1f, SOFT), k(106, .6f, SOFT), k(114, 0f),
            k(128, 0f, SOFT), k(131, .7f, SOFT), k(137, .2f, SOFT), k(141, 1f, SOFT), k(148, .3f, SOFT), k(152, .6f, SOFT),
            k(160, .1f, SOFT), k(164, .8f, SOFT), k(172, .2f, SOFT), k(176, .4f, SOFT), k(184, 0f), k(188, 0f),
        ),
        then = nod,
    )
}

/** What it acts when something wants you now (2026-10-28): a timer gone off. */
internal object AlertActs {
    /**
     * The ears shoot up and quiver, the left sunk a little so it stands as tall as the right, the
     * eyes go wide and stare ahead, and the body jolts up and lands; again every 2 s, with a quick
     * double blink between, until it is let go. The rig has no lids to hold open.
     */
    val ringing = CroissantAct(
        "ringing",
        listOf(
            lane(Channel.Hover, k(0, 0f), k(6, 0f, FLICK), k(11, -10f, SOFT), k(20, 0f, SOFT), k(25, -2f, SOFT), k(30, 0f), k(126, 0f)),
            lane(Channel.Squash, k(0, 1f), k(6, 1f, SOFT), k(8, .92f, FLICK), k(12, 1.06f, SOFT), k(20, .95f, SOFT), k(26, 1f), k(126, 1f)),
            lane(Channel.EarL, k(0, 0f, FLICK), k(6, -27f), k(8, -27f, SOFT), k(11, -30f, SOFT), k(14, -24f, SOFT), k(17, -30f, SOFT),
                k(20, -24f, SOFT), k(23, -28f, SOFT), k(26, -26f, SOFT), k(29, -27f), k(126, -27f)),
            lane(Channel.EarLDown, k(0, 0f, FLICK), k(6, 14f), k(126, 14f)),
            lane(Channel.EarR, k(0, 0f, FLICK), k(6, -30f), k(8, -30f, SOFT), k(11, -27f, SOFT), k(14, -33f, SOFT), k(17, -27f, SOFT),
                k(20, -33f, SOFT), k(23, -29f, SOFT), k(26, -31f, SOFT), k(29, -30f), k(126, -30f)),
            lane(Channel.EarLStretch, k(0, 1f, FLICK), k(6, 1.06f), k(126, 1.06f)),
            lane(Channel.EarRStretch, k(0, 1f, FLICK), k(6, 1.06f), k(126, 1.06f)),
            lane(Channel.SideL, k(0, 0f), k(6, 0f, SOFT), k(9, 2f, SOFT), k(12, -1.5f, SOFT), k(15, 1f, SOFT), k(18, -.5f, SOFT), k(21, 0f), k(126, 0f)),
            lane(Channel.SideR, k(0, 0f), k(6, 0f, SOFT), k(9, -1.6f, SOFT), k(12, 1.2f, SOFT), k(15, -.8f, SOFT), k(18, .4f, SOFT), k(21, 0f), k(126, 0f)),
            lane(Channel.EyeSize, k(0, 1f, DART), k(6, 1.3f), k(126, 1.3f)),
            lane(Channel.Blink, k(0, 1f), k(60, 1f, SHUT), k(63, .08f), k(64, .08f, OPEN), k(68, 1f, SHUT), k(71, .08f), k(72, .08f, OPEN), k(77, 1f), k(126, 1f)),
        ),
        fadeIn = 4f, fadeOut = 18f, loop = 6f..126f,
        takes = setOf(Channel.EarL, Channel.EarR, Channel.Blink),
        gaze = GazeTrack(track(k(0, 0f), k(126, 0f)), track(k(0, 0f), k(126, 0f)), track(k(0, 1f), k(126, 1f)), track(k(0, 1f), k(126, 1f))),
    )
}

// The croissant's moves by name, for anything that wants one now, on top of whatever plays.
internal fun CroissantRig.flutterEar() = play(Moves.flutterEar)
internal fun CroissantRig.blink() = play(Moves.blink)
internal fun CroissantRig.look(to: Gaze, frames: Int = 5) = glance(dart(to, frames))
