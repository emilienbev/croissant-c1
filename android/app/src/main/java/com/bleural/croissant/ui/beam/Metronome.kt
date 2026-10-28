package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.metronome.Click
import com.bleural.croissant.metronome.Instrument
import com.bleural.croissant.metronome.Metronome
import com.bleural.croissant.metronome.MetronomeState
import com.bleural.croissant.metronome.Subdivision
import com.bleural.croissant.metronome.TimeSig
import com.bleural.croissant.metronome.Tuner
import com.bleural.croissant.metronome.TunerState
import com.bleural.croissant.metronome.noteName
import com.bleural.croissant.metronome.noteOctave
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.practiceStreak
import com.bleural.croissant.ui.practiceTime
import com.bleural.croissant.ui.rememberBeat
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.rememberSecondTick
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Metronome in Beam: the opened page and its paused state (picked 2026-09-26): the tempo as a
 * dial, the bar in its mouth, the settings in its corners. Sound, Practice, the time signature, the
 * tuner and its tuning are made from the kit on the same lines: words in white, values in sub, the
 * hue only on what is live, chosen or in tune, faint for what is not lit. Every number is a page
 * pixel on the 480 x 640 canvas, y from the very top.
 */

/** A time signature's drum, the number the wheel turns. */
private val Drum = BeamType.Big.copy(fontSize = 120f.pxSp, letterSpacing = (-4f).pxSp)

/** Page pixels of finger across the tempo for one bpm. */
private const val PX_PER_BPM = 6f

/** The practice grid's days, Monday first. */
private val DAYS = listOf("m", "t", "w", "t", "f", "s", "s")

/** Draws [block] in page pixels with y from the top of the canvas, for a canvas that starts [top] px down. */
private inline fun DrawScope.fromTop(top: Float, crossinline block: DrawScope.() -> Unit) =
    pageScale { translate(0f, -top) { block() } }

// ---- the router ---------------------------------------------------------------------------

/** The Metronome's pushed pages: its time signature, sound, practice, tuner and tuning. */
@Composable
internal fun MetronomePages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.TimeSignature -> {
            val m by core.metronome.state.collectAsStateWithLifecycle()
            TimeSigPage(core.metronome, m, onBack = nav::pop)
        }
        Screen.MetronomeSound -> {
            val m by core.metronome.state.collectAsStateWithLifecycle()
            MetronomeSoundPage(core.metronome, m, onBack = nav::pop)
        }
        Screen.Practice -> {
            val m by core.metronome.state.collectAsStateWithLifecycle()
            PracticePage(core.metronome, m, onBack = nav::pop)
        }
        Screen.Tuner -> {
            val t by core.tuner.state.collectAsStateWithLifecycle()
            // The microphone goes to the tuner only while its page is up.
            DisposableEffect(Unit) { core.tune(true); onDispose { core.tune(false) } }
            TunerPage(core.tuner, t, onTuning = { nav.push(Screen.Tuning) }, onBack = nav::pop)
        }
        Screen.Tuning -> {
            val t by core.tuner.state.collectAsStateWithLifecycle()
            TuningPage(core.tuner, t, onBack = nav::pop)
        }
        else -> Unit
    }
}

// ---- the metronome ------------------------------------------------------------------------

/** The dial: its centre and radius on the page and the ring's width; it sweeps 270 degrees from lower left. */
private const val CX = 240f
private const val CY = 326f
private const val R = 168f
private const val RING = 26f

/** The markings the ring is cut into, as a metronome's scale prints them (largo to presto): where each starts, then the top. */
private val MARKS = listOf(40, 66, 76, 108, 120, 168, 208)

/** Where [bpm] sits round the sweep, in degrees clockwise from the top. */
private fun angleOf(bpm: Int) = -135f + 270f * (bpm - Metronome.MIN_BPM) / (Metronome.MAX_BPM - Metronome.MIN_BPM)

/** The tempo a point on the ring means: its angle round the sweep, the gap at the foot split between the ends. */
private fun bpmAt(x: Float, y: Float): Int {
    var a = (atan2(y - CY, x - CX) * 180f / PI.toFloat() - 135f + 360f) % 360f
    if (a > 270f) a = if (a > 315f) 0f else 270f
    return (Metronome.MIN_BPM + a / 270f * (Metronome.MAX_BPM - Metronome.MIN_BPM)).roundToInt()
}

/**
 * The Metronome (picked 2026-09-26): the tempo as a dial. The ring sweeps from 40 at lower left to
 * 208 at lower right, cut into the six markings with a gap between each, lit in the hue up to the
 * tempo and faint past it, a white knob on it at the tempo; the tempo alone in its middle. A finger
 * on the ring puts the tempo where it lands and drags it round (the knob grows and the number goes
 * the hue while it does); the wheel steps it. A tap in the middle, or on the bar in the ring's
 * mouth, is a tap of the tap tempo, on the finger's down. Round the dial in its corners: the time
 * signature top left (opens its page), the note top right (a tap steps the subdivision), the
 * tuner's fork bottom left and the sound bottom right (each opens its page). Under it today's
 * practice in sub (opens its days) and play.
 *
 * Paused is the same page with no beat lit and the key's play. The way back is the finger
 * pulled down from the dial's middle, the elastic's, and the left edge.
 */
@Composable
internal fun MetronomePage(m: Metronome, s: MetronomeState, onTimeSig: () -> Unit, onSound: () -> Unit, onPractice: () -> Unit, onTuner: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    val pull = rememberPull(onBack)
    val latest = rememberUpdatedState(s)
    WheelTarget { delta ->
        val next = (latest.value.bpm - delta).coerceIn(Metronome.MIN_BPM, Metronome.MAX_BPM)
        if (next == latest.value.bpm) return@WheelTarget false
        m.setBpm(next)
        true
    }
    val beat = rememberBeat(m, s.playing)
    val second by rememberSecondTick()
    val today = remember(second, s.playing) { practiceTime(m.practiceMs()) + " today" }
    val text = rememberTextMeasurer(cacheSize = 8)
    val fork = rememberVectorPainter(Glyphs.Fork)
    val sound = rememberVectorPainter(Glyphs.Sound)
    var dragging by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(r.ground)) {
        Header("metronome", r)
        PulledCanvas(
            pull, r, Modifier.padding(top = DRAWN_TOP.px),
            canvas = Modifier.pointerInput(m) {
                awaitEachGesture {
                    val u = size.width / 480f
                    val down = awaitFirstDown()
                    // The left edge is the way back.
                    if (down.position.x < 28.dp.toPx()) return@awaitEachGesture
                    val x = down.position.x / u
                    val y = down.position.y / u + DRAWN_TOP
                    val d = hypot(x - CX, y - CY)
                    // Only a drag from the dial's middle may reach the pull; any other is kept here.
                    var pulls = false
                    when {
                        y > 540f -> if (x < 300f) { down.consume(); onPractice() }
                        // The mouth, where the bar is: the tap tempo.
                        y > CY + 90f && abs(x - CX) < 95f && d < R + 30f -> { down.consume(); m.tap() }
                        d in (R - 56f)..(R + 30f) -> {
                            down.consume()
                            dragging = true
                            m.setBpm(bpmAt(x, y))
                            drag(down.id) { c -> c.consume(); m.setBpm(bpmAt(c.position.x / u, c.position.y / u + DRAWN_TOP)) }
                            dragging = false
                            Cues.play(Sound.Commit)
                        }
                        // The middle: a tap is the tap tempo's; a drag is left to the scroll under it, the pull.
                        d < R - 56f -> { m.tap(); pulls = true }
                        y < CY && x < CX -> { down.consume(); onTimeSig() }
                        y < CY -> { down.consume(); m.nextSub(); Cues.play(Sound.Commit) }
                        x < CX -> { down.consume(); onTuner() }
                        else -> { down.consume(); onSound() }
                    }
                    if (!pulls) drag(down.id) { it.consume() }
                }
            },
        ) {
            fromTop(DRAWN_TOP) {
                val k = angleOf(s.bpm)
                val trim = (RING / 2 + 5f) / R * 180f / PI.toFloat()
                for (i in 0 until MARKS.size - 1) {
                    val a0 = angleOf(MARKS[i]) + if (i > 0) trim else 0f
                    val a1 = angleOf(MARKS[i + 1]) - if (i < MARKS.size - 2) trim else 0f
                    arc(a0, a1, r.deep)
                    if (k > a0) arc(a0, minOf(k, a1), r.hue)
                }
                val t = k * PI.toFloat() / 180f
                val kx = CX + R * sin(t)
                val ky = CY - R * cos(t)
                val kr = if (dragging) 25f else 19f
                dot(kx, ky, kr + 5f, r.ground)
                dot(kx, ky, kr, r.ink)
                text(text, CX, CY + 46f, s.bpm.toString(), BeamType.Big, if (dragging) r.hue else r.ink, Anchor.Middle)
                if (!dragging) bar(s.sig.beats, beat, r)
                text(text, 24f, 156f, s.sig.toString(), BeamType.Select, r.ink)
                scale(0.8f, 0.8f, pivot = Offset(438f, 150f)) { noteValue(s.sub, 438f, 142f, r.ink, text) }
                glyph(fork, 24f, 468f, 36f, r.ink)
                glyph(sound, 420f, 468f, 36f, r.ink)
                text(text, 24f, 600f, fit(text, today, BeamType.Value, 330f), BeamType.Value, r.sub)
            }
        }
        KnobKey(if (s.playing) Glyphs.Pause else Glyphs.Play, r, onClick = { m.toggle(); Cues.play(Sound.Commit) })
    }
}

/** A stretch of the ring, from [a0] to [a1] degrees clockwise from the top, its ends round. */
private fun DrawScope.arc(a0: Float, a1: Float, c: Color) =
    drawArc(c, a0 - 90f, a1 - a0, useCenter = false, topLeft = Offset(CX - R, CY - R), size = Size(2 * R, 2 * R), style = Stroke(RING, cap = StrokeCap.Round))

/**
 * The bar in the dial's mouth: dots 34 apart on y CY + 142, the one [heard] r 11 in the hue, the
 * rest r 8 faint, the first in sub so the one reads while it is not lit. Past eight beats they go
 * on two lines 26 apart, closer as the line fills.
 */
private fun DrawScope.bar(n: Int, heard: Int, r: Ramp) {
    val lines = if (n > 8) 2 else 1
    val per = ceil(n / lines.toFloat()).toInt()
    val pitch = if (per > 1) minOf(34f, 190f / (per - 1)) else 0f
    val y0 = CY + 142f - (lines - 1) * 13f
    for (i in 0 until n) {
        val lit = i == heard
        val cx = CX + (i % per - (per - 1) / 2f) * pitch
        dot(cx, y0 + i / per * 26f, if (lit) 11f else 8f, when { lit -> r.hue; i == 0 -> r.sub; else -> r.deep })
    }
}

/** A note value drawn flat, centred on [cx]: heads, stems, beams, a 3 over a triplet, 20 between heads. */
private fun DrawScope.noteValue(sub: Subdivision, cx: Float, cy: Float, c: Color, text: TextMeasurer) {
    val heads = sub.per
    val gap = 20f
    val x0 = cx - (heads - 1) * gap / 2 - 4f
    val stemTop = cy - 34f
    val headY = cy + 8f
    for (i in 0 until heads) {
        val hx = x0 + i * gap
        rotate(-22f, Offset(hx, headY)) { drawOval(c, Offset(hx - 8.5f, headY - 6.5f), Size(17f, 13f)) }
        rect(hx + 5.5f, stemTop, 3f, headY - stemTop - 2f, c)
    }
    if (heads > 1) {
        val bx = x0 + 5.5f
        val bw = (heads - 1) * gap + 3f
        rect(bx, stemTop, bw, 6f, c)
        if (sub == Subdivision.Sixteenths) rect(bx, stemTop + 11f, bw, 6f, c)
        if (sub == Subdivision.Triplets) text(text, cx + 1f, stemTop - 8f, "3", BeamType.Label, c, Anchor.Middle)
    }
}

// ---- its pages ------------------------------------------------------------------------------

/**
 * The metronome's sound: its sounds as the kit's rows under their label, the one in use ticked, the
 * focused one in the hue. A pick stays on the list and is heard at once, so they can be tried one
 * after another; the key plays and pauses the metronome to hear it in time.
 */
@Composable
internal fun MetronomeSoundPage(m: Metronome, s: MetronomeState, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    val list = Click.entries
    // Lazy item 0 is the label, so row i is item i + 1 and the label stays above row 0.
    val rows = rememberRows(list.size, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    LaunchedEffect(Unit) { rows.focus = s.click.ordinal }
    PageTop("sound", r) {
        ShelfList(rows, r) {
            item { DotLabel("metronome", r, count = list.size, top = LABEL_FIRST) }
            items(list.size) { i ->
                ShelfRow(
                    list[i].word.lowercase(), r, focused = rows.focus == i,
                    onClick = { rows.focus = i; m.setClick(list[i]); m.audition(list[i]) },
                    mark = if (list[i] == s.click) Glyphs.Check else null,
                    edge = shelfEdge(i, list.size),
                )
            }
            item { Spacer(Modifier.height(112.px)) }
        }
        KnobKey(if (s.playing) Glyphs.Pause else Glyphs.Play, r, onClick = { m.toggle(); Cues.play(Sound.Commit) })
    }
}

/** A practice time as the page's number and its unit: "18" and "min", or "3:05" and "h" past the hour. */
private fun practiceParts(ms: Long): Pair<String, String> {
    val min = ms / 60_000
    return if (min < 60) "$min" to "min" else "${min / 60}:${(min % 60).toString().padStart(2, '0')}" to "h"
}

/**
 * Practice: the time played over the five weeks shown big, its unit in sub after it; under it in
 * sub the weeks it covers and, right, the days played in a row; then the days as a grid of
 * segments, a week a line, Monday first under their letters, each faint when nothing was played and
 * lit in the hue by its minutes (four steps), today ringed in white. The wheel down goes back a
 * week at a time, up comes forward again, and up at this week is the way back, as on any page.
 */
@Composable
internal fun PracticePage(m: Metronome, s: MetronomeState, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    val second by rememberSecondTick()
    val log = remember(second / 30, s.playing) { m.practiceLog() }
    val today = remember(second / 60) { LocalDate.now() }
    var back by remember { mutableIntStateOf(0) }
    val oldest = log.keys.firstOrNull()
    val pull = rememberPull(onBack)
    WheelTarget { delta ->
        // Down is earlier, never before the week of the first day played.
        if (delta < 0 && back == 0) { pull.stepBack(); return@WheelTarget false }
        val next = (back + delta).coerceAtLeast(0)
        if (next == back) return@WheelTarget false
        val firstShown = today.minusWeeks(back.toLong()).with(DayOfWeek.SUNDAY).minusDays(34)
        if (next > back && (oldest == null || !oldest.isBefore(firstShown))) return@WheelTarget false
        back = next
        true
    }
    val end = today.minusWeeks(back.toLong()).with(DayOfWeek.SUNDAY)
    val start = end.minusDays(34)
    val days = (0 until 35).map { start.plusDays(it.toLong()) }
    val shown = days.filter { !it.isAfter(today) }.sumOf { log[it] ?: 0L }
    val streak = practiceStreak(log, today)
    val text = rememberTextMeasurer(cacheSize = 16)
    val span = if (back == 0) "last 5 weeks" else "5 weeks to " + end.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)).lowercase()
    PageTop("practice", r) {
        PulledCanvas(pull, r) {
            fromTop(DRAWN_TOP) {
                val (number, unit) = practiceParts(shown)
                val w = text(text, 14f, 226f, number, BeamType.Big, r.ink)
                text(text, 14f + w + 32f, 226f, unit, BeamType.Row, r.sub)
                val room = if (streak > 0) 456f - 24f - text.measure("$streak in a row", BeamType.Value).size.width - 20f else 432f
                text(text, 24f, 272f, fit(text, span, BeamType.Value, room), BeamType.Value, r.sub)
                if (streak > 0) text(text, 456f, 272f, "$streak in a row", BeamType.Value, r.sub, Anchor.End)
                // Seven segments 50 x 42 across 24 to 456, 12 apart down the weeks.
                val cw = 50f
                val ch = 42f
                val gx = (432f - cw * 7) / 6
                val gy = 12f
                DAYS.forEachIndexed { i, d -> text(text, 24f + i * (cw + gx) + cw / 2, 320f, d, BeamType.Label, r.sub, Anchor.Middle) }
                days.forEachIndexed { i, d ->
                    val x = 24f + (i % 7) * (cw + gx)
                    val y = 338f + (i / 7) * (ch + gy)
                    if (d.isAfter(today)) return@forEachIndexed
                    val min = (log[d] ?: 0L) / 60_000
                    val c = when {
                        min < 1 -> r.deep
                        min < 15 -> r.hue.copy(alpha = 0.35f)
                        min < 30 -> r.hue.copy(alpha = 0.6f)
                        min < 45 -> r.hue.copy(alpha = 0.8f)
                        else -> r.hue
                    }
                    rect(x, y, cw, ch, c, 10f)
                    if (d == today) drawRoundRect(r.ink, Offset(x - 4f, y - 4f), Size(cw + 8f, ch + 8f), CornerRadius(14f), style = Stroke(3f))
                }
            }
        }
    }
}

/**
 * The time signature: beats and the note as two drums side by side about a slash in sub, so the
 * page reads as the signature itself; each drum the value big with its neighbours above and below
 * in sub and its word under them, the one the wheel turns in the hue (a tap picks which). Under
 * them the common ones as words in two lines of five under their label, the one in use in the hue
 * and underlined, as a selector's choice. It applies as it changes (the metronome keeps playing
 * under it). The wheel is the drums'; a finger pulled down is the way back.
 */
@Composable
internal fun TimeSigPage(m: Metronome, s: MetronomeState, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    var focus by remember { mutableIntStateOf(0) }
    val latest = rememberUpdatedState(s.sig)
    WheelTarget { delta ->
        val sig = latest.value
        val next = if (focus == 0) sig.copy(beats = (sig.beats + delta).coerceIn(TimeSig.BEATS))
            else sig.copy(note = TimeSig.NOTES[(TimeSig.NOTES.indexOf(sig.note) + delta).coerceIn(0, TimeSig.NOTES.lastIndex)])
        if (next == sig) return@WheelTarget false
        m.setSig(next)
        true
    }
    val text = rememberTextMeasurer(cacheSize = 32)
    val pull = rememberPull(onBack)
    PageTop("time signature", r) {
        PulledCanvas(
            pull, r,
            canvas = Modifier.pointerInput(Unit) {
                detectTapGestures { p ->
                    val u = size.width / 480f
                    val x = p.x / u
                    val y = p.y / u + DRAWN_TOP
                    when {
                        y in 110f..396f -> { focus = if (x < 240f) 0 else 1; Cues.play(Sound.Commit) }
                        y in 452f..586f -> {
                            val row = if (y < 520f) 0 else 1
                            val col = ((x - 24f) / 86.4f).toInt().coerceIn(0, 4)
                            TimeSig.COMMON.getOrNull(row * 5 + col)?.let { m.setSig(it); Cues.play(Sound.Commit) }
                        }
                    }
                }
            },
        ) {
            fromTop(DRAWN_TOP) {
                val sig = s.sig
                val ni = TimeSig.NOTES.indexOf(sig.note)
                val drums = listOf(
                    Triple(listOf((sig.beats - 1).takeIf { it >= TimeSig.BEATS.first }, sig.beats, (sig.beats + 1).takeIf { it <= TimeSig.BEATS.last }), "beats", 132f),
                    Triple(listOf(TimeSig.NOTES.getOrNull(ni - 1), sig.note, TimeSig.NOTES.getOrNull(ni + 1)), "note", 348f),
                )
                drums.forEachIndexed { i, (v, word, cx) ->
                    val on = i == focus
                    v[0]?.let { text(text, cx, 172f, it.toString(), BeamType.Second, r.sub, Anchor.Middle) }
                    text(text, cx, 286f, v[1].toString(), Drum, if (on) r.hue else r.ink, Anchor.Middle)
                    v[2]?.let { text(text, cx, 344f, it.toString(), BeamType.Second, r.sub, Anchor.Middle) }
                    text(text, cx, 384f, word, BeamType.Label, if (on) r.hue else r.sub, Anchor.Middle)
                }
                text(text, 240f, 286f, "/", Drum, r.sub, Anchor.Middle)
                // Its label 40 under the drums' words, the two lines of five 60 apart.
                text(text, 24f, 436f, "common", BeamType.Label, r.sub)
                TimeSig.COMMON.forEachIndexed { i, c ->
                    val cx = 24f + (i % 5) * 86.4f + 43.2f
                    val base = 490f + (i / 5) * 60f
                    val sel = c == sig
                    val w = text(text, cx, base, c.toString(), if (sel) BeamType.BodyMedium else BeamType.Body, if (sel) r.hue else r.ink, Anchor.Middle)
                    if (sel) rect(cx - w / 2, base + 12f, w, 4f, r.hue, 2f)
                }
            }
        }
    }
}

/**
 * The tuner: the instrument as a selector; the note of the string heard (or held) big with its
 * octave in sub, the cents off, the word and the hz in sub at the right; the scale as a meter's
 * segments, faint, the centre one taller in sub, and the marker at the cents, white, lit in the hue
 * with the centre once in tune (within 3 cents); the tuning as a row that opens its list; and the
 * strings as circles, faint as the beats are, the one heard ringed in the hue, the one held (a tap
 * holds it, a second lets go) filled in the hue with its name black. Nothing heard, nothing said.
 * The wheel has nothing to turn: up is the way back, as on any page.
 */
@Composable
internal fun TunerPage(t: Tuner, s: TunerState, onTuning: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    val text = rememberTextMeasurer(cacheSize = 24)
    val strings = s.tuning.strings
    val reading = s.reading
    val string = s.locked ?: reading?.string
    val inTune = reading != null && abs(reading.cents) <= 3f
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    PageTop("tuner", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            // y 100 to 164.
            Box(Modifier.fillMaxWidth().height(64.px)) {
                Selector(Instrument.entries.map { it.word }, s.instrument.ordinal, r, Modifier.padding(start = 24.px, top = 8.px)) {
                    t.setInstrument(Instrument.entries[it]); Cues.play(Sound.Commit)
                }
            }
            // The note and the scale, y 164 to 440.
            Canvas(Modifier.fillMaxWidth().height(276.px)) {
                fromTop(164f) {
                    string?.let {
                        val midi = strings[it]
                        val w = text(text, 14f, 300f, noteName(midi), BeamType.Big, r.ink)
                        text(text, 14f + w + 8f, 300f, noteOctave(midi).toString(), BeamType.Second, r.sub)
                    }
                    reading?.let {
                        val c = it.cents.roundToInt()
                        text(text, 456f, 226f, (if (c > 0) "+" else if (c < 0) "−" else "") + abs(c), BeamType.Second, if (inTune) r.hue else r.ink, Anchor.End)
                        text(text, 456f, 262f, "cents", BeamType.Label, r.sub, Anchor.End)
                        text(text, 456f, 298f, "%.1f hz".format(it.hz), BeamType.Label, r.sub, Anchor.End)
                    }
                    // The scale: 17 segments 14 wide, 24 apart, centred on 240; 50 cents either way at the ends.
                    val count = 17
                    val x0 = 240f - (count - 1) * 12f - 7f
                    for (i in 0 until count) {
                        val centre = i == count / 2
                        val c = when { centre && inTune -> r.hue; centre -> r.sub; else -> r.deep }
                        rect(x0 + i * 24f, if (centre) 330f else 338f, 14f, if (centre) 52f else 36f, c, 7f)
                    }
                    reading?.let {
                        val mx = 240f + it.cents.coerceIn(-50f, 50f) / 50f * (count / 2) * 24f
                        rect(mx - 5f, 322f, 10f, 68f, if (inTune) r.hue else r.ink, 5f)
                    }
                    text(text, 24f, 424f, "flat", BeamType.Label, r.sub)
                    text(text, 456f, 424f, "sharp", BeamType.Label, r.sub, Anchor.End)
                }
            }
            // y 440 to 504.
            ShelfRow("tuning", r, onClick = onTuning, value = s.tuning.name, chevron = true)
            // The strings, y 504 to 640.
            Canvas(
                Modifier.fillMaxWidth().height(136.px).pointerInput(strings.size) {
                    detectTapGestures { pt ->
                        val u = size.width / 480f
                        val i = ((pt.x / u - 22f) / (436f / strings.size)).toInt()
                        if (i in strings.indices) { t.lock(i); Cues.play(Sound.Commit) }
                    }
                },
            ) {
                fromTop(504f) {
                    val pitch = 436f / strings.size
                    val rad = minOf(34f, pitch * 0.42f)
                    strings.forEachIndexed { i, midi ->
                        val cx = 22f + pitch * (i + 0.5f)
                        val held = i == s.locked
                        val heard = !held && i == string
                        when {
                            held -> dot(cx, 572f, rad, r.hue)
                            heard -> drawCircle(r.hue, rad - 1.5f, Offset(cx, 572f), style = Stroke(3f))
                            else -> dot(cx, 572f, rad, r.deep)
                        }
                        val c = when { held -> r.ground; heard -> r.hue; else -> r.ink }
                        text(text, cx, 583f, noteName(midi), BeamType.BodyMedium, c, Anchor.Middle)
                    }
                }
            }
        }
    }
}

/**
 * The tuner's tuning: the instrument's tunings as rows under its name, each with its strings low to
 * high in sub, the one in use ticked, the focused one in the hue. A pick goes back to the tuner.
 */
@Composable
internal fun TuningPage(t: Tuner, s: TunerState, onBack: () -> Unit) {
    val r = rampOf(Feature.Metronome)
    val list = s.instrument.tunings
    // Lazy item 0 is the label, so row i is item i + 1 and the label stays above row 0.
    val rows = rememberRows(list.size, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    LaunchedEffect(Unit) { rows.focus = list.indexOf(s.tuning).coerceAtLeast(0) }
    PageTop("tuning", r) {
        ShelfList(rows, r) {
            item { DotLabel(s.instrument.word, r, count = list.size, top = LABEL_FIRST) }
            items(list.size) { i ->
                ShelfRow(
                    list[i].name, r, focused = rows.focus == i,
                    onClick = { rows.focus = i; t.setTuning(i); onBack() },
                    value = list[i].strings.joinToString(" ") { noteName(it) },
                    mark = if (list[i] == s.tuning) Glyphs.Check else null,
                    edge = shelfEdge(i, list.size),
                )
            }
        }
    }
}
