package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.feature

/**
 * Beam's colours: one black ground,
 * white words, a grey for what is secondary, and one neon per feature, shown only where it
 * matters. A [Ramp] keeps an earlier design's fields so every page reads its colours by the same names;
 * in Beam most of them are the same for every feature and only [hue] (with [fill],
 * [knob] and [cream], which are it) changes.
 */
@Immutable
class Ramp internal constructor(
    /** The feature's neon: the focused row's words, glyphs, meters, keys, the lit part. */
    val hue: Color,
    /** The page, and nothing else: black. */
    val ground: Color,
    /** The group container: black too, since nothing is drawn round a group. */
    val shelf: Color,
    /** A track, an unlit segment, an off toggle (faint). */
    val deep: Color,
    /** The lit part of a meter or a level: the hue. */
    val fill: Color,
    /** Labels, counts, times, values, units. */
    val sub: Color,
    /** Every word that carries meaning: white. */
    val ink: Color,
    /** The one control on the page (a key's fill): the hue. Black ([ground]) goes on it. */
    val knob: Color,
) {
    /** What is focused, on or chosen: the hue. */
    val cream: Color get() = hue
    /** A destructive action and nothing else. */
    val danger: Color get() = DANGER
    /** A key that cannot act. */
    val dim: Color get() = DIM
}

/** The ground: pure black, as if nothing were rendering. */
val GROUND = Color(0xFF000000)
/** Words. */
val INK = Color(0xFFFFFFFF)
/** Labels, counts, times, values, units. */
val SUB = Color(0xFF8C8C8C)
/** A track, an unlit segment, an off toggle. */
val FAINT = Color(0xFF2A2A2A)
/** A key that cannot act. */
val DIM = Color(0xFF4A4A4A)
/** Destructive only. */
val DANGER = Color(0xFFFF3B30)

/** The ramp of [hue]: everything but the hue is the device's own black, white and greys. */
fun ramp(hue: Color): Ramp = Ramp(
    hue = hue,
    ground = GROUND,
    shelf = GROUND,
    deep = FAINT,
    fill = hue,
    sub = SUB,
    ink = INK,
    knob = hue,
)

/** Whether [c] is light: what sits on it is black when this is true, white when not. */
fun light(c: Color): Boolean = c.toArgb().let { 0.299 * ((it shr 16) and 0xFF) + 0.587 * ((it shr 8) and 0xFF) + 0.114 * (it and 0xFF) > 150 }

/**
 * Each feature's neon (the page's `NT_HUE`), from the rabbitOS 2 cards. Every one is light
 * enough to carry black words, as the stock cards do.
 */
fun hueOf(f: Feature): Color = when (f) {
    Feature.Ask -> Color(0xFFFF4F00)
    Feature.Recorder -> Color(0xFFFF2B2B)
    Feature.Reminders -> Color(0xFF1FA2FF)
    Feature.Timers -> Color(0xFF8F8AFF)
    Feature.Camera -> Color(0xFFFF2E8F)
    Feature.Calendar -> Color(0xFFD08CFF)
    // Home's weather hue (the page's `NT_WX.hue`), the sky.
    Feature.Weather -> Color(0xFF7DD3FC)
    Feature.Photos -> Color(0xFF2CE5E8)
    Feature.Metronome -> Color(0xFFFFE01A)
    Feature.Translator -> Color(0xFF62F23A)
    Feature.Settings -> Color(0xFFFFA41B)
    Feature.Atelier -> Color(0xFFFF8F80)
    // The page's PI_HUE, a mint none of the others has (provisional, 2026-10-21).
    Feature.Agents -> Color(0xFF4CF2B8)
}

/** Something waiting on you to answer (the page's `PI_ASK`): yellow, out of every feature's hue. */
internal val ASKING = Color(0xFFFFD60A)

/** Every feature's ramp, in room order. */
val RAMPS: List<Ramp> = Feature.entries.map { ramp(hueOf(it)) }

fun rampOf(f: Feature): Ramp = RAMPS[f.ordinal]

/** Home's and Today's: the white ramp (its hue is the ink); each group on them takes its feature's hue. */
val HOME: Ramp = ramp(INK)

/** The ramp a pushed page is drawn in: its feature's, every Settings page the Settings one, Home and Today the white one. */
fun rampOf(s: Screen): Ramp = s.feature?.let(::rampOf) ?: HOME
