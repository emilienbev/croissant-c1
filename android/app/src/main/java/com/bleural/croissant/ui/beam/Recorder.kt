package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import com.bleural.croissant.audio.RecPhase
import com.bleural.croissant.data.Recording
import com.bleural.croissant.data.lengthClock
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.RecorderAction
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.rememberSecondTick
import java.io.File
import kotlin.math.hypot

/**
 * The Recorder opened: what it is doing as a label ("recording" in the hue after a hue dot,
 * "paused" in sub, "saved" in the hue, nothing at rest), the time recorded at 132 Light (dim at
 * rest), the level of the last eighteen seconds as rounded bars (the hue while it records, sub
 * paused, faint and flat at rest), and three round keys: the tapes, record or pause, stop. Record
 * is the filled key whenever it is not recording (it resumes when paused); while it records that
 * key is pause, as a ring, and stop is the filled one. A key that cannot act is dim.
 *
 * While it records the bars slide a place left each second as the newest comes in on the
 * right. On stop the take's row (the tape glyph and its name, focused) stands where the bars
 * were until the next record (a tap opens it), and a dot pops onto the tapes key. The row has
 * no length: the big time already says it.
 *
 * All of it is one canvas: the second's time and the levels are read in the draw, so a second
 * passing redraws it and recomposes nothing.
 */
@Composable
internal fun RecorderDeck(d: PanelDeps) {
    val r = rampOf(Feature.Recorder)
    // Nothing here turns: up is the way back, through the pull as on every page.
    val pull = rememberPull { d.close() }
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    val rec = d.recorder
    val idle = rec.phase == RecPhase.Idle
    val recording = rec.phase == RecPhase.Recording
    val paused = rec.phase == RecPhase.Paused
    val hasTapes = d.recordings.isNotEmpty()

    // The take that just stopped, kept until the next record, and the tape it became.
    var lastFile by remember { mutableStateOf(rec.file) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(rec.file) { rec.file?.let { lastFile = it } }
    val saved = if (idle) savedFile?.let { f -> d.recordings.firstOrNull { it.file == f } } else null

    // The time and the levels, read on the second; each new second slides the bars a place.
    val tick = rememberSecondTick()
    val levelsOf by rememberUpdatedState(d.levels)
    val elapsedOf by rememberUpdatedState(d.elapsed)
    var history by remember { mutableStateOf(FloatArray(BAR_COUNT + 1)) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val slide = remember { Animatable(0f) }
    LaunchedEffect(rec.phase) {
        snapshotFlow { tick.value }.collect {
            val all = levelsOf()
            val next = all.copyOfRange((all.size - BAR_COUNT - 1).coerceAtLeast(0), all.size)
            val moved = recording && !next.contentEquals(history)
            history = next
            elapsed = elapsedOf()
            if (moved) { slide.snapTo(BAR_PITCH); slide.animateTo(0f, tween(SLIDE_MS)) } else slide.snapTo(0f)
        }
    }

    // The saved dot's size on the tapes key.
    val pop = remember { Animatable(0f) }
    var was by remember { mutableStateOf(rec.phase) }
    LaunchedEffect(rec.phase) {
        val before = was
        was = rec.phase
        when {
            rec.phase != RecPhase.Idle -> if (before == RecPhase.Idle) { savedFile = null; pop.snapTo(0f) }
            before != RecPhase.Idle -> {
                savedFile = lastFile
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
            }
        }
    }

    val act by rememberUpdatedState { i: Int ->
        when (i) {
            0 -> if (hasTapes) d.nav.push(Screen.Library)
            1 -> d.onRecorder(
                when {
                    recording -> RecorderAction.Pause
                    paused -> RecorderAction.Resume
                    else -> RecorderAction.Record
                },
            )
            2 -> if (!idle) d.onRecorder(RecorderAction.Stop)
        }
    }
    val openSaved by rememberUpdatedState { saved?.let { d.nav.push(Screen.Recording(it.file.path)) } }

    val m = rememberTextMeasurer()
    val glyphs = RecKeys(
        rememberVectorPainter(Glyphs.Library), rememberVectorPainter(Glyphs.Record), rememberVectorPainter(Glyphs.Pause),
        rememberVectorPainter(Glyphs.Stop), rememberVectorPainter(Glyphs.Tape),
    )
    PageTop("recorder", r) {
        PulledCanvas(
            pull, r,
            canvas = Modifier.pointerInput(Unit) {
                detectTapGestures { p ->
                    val u = size.width / 480f
                    val x = p.x / u
                    val y = p.y / u + DRAWN_TOP
                    if (y in SAVED_TOP..SAVED_TOP + SAVED_H) { openSaved(); return@detectTapGestures }
                    val i = REC_KEY_X.indexOfFirst { hypot(x - it, y - LIFT - REC_KEY_Y) < 52f }
                    if (i >= 0) act(i)
                }
            },
        ) {
            pageScale {
                translate(0f, -DRAWN_TOP) {
                    when {
                        recording -> {
                            dot(34f, 123f, 8f, r.hue)
                            text(m, 52f, 132f, "recording", BeamType.Label, r.hue)
                        }
                        paused -> text(m, 24f, 132f, "paused", BeamType.Label, r.sub)
                        saved != null -> text(m, 24f, 132f, "saved", BeamType.Label, r.hue)
                    }
                    val time = when {
                        saved != null -> saved.durationMs
                        idle -> 0L
                        else -> elapsed
                    }
                    text(m, 14f, 270f, lengthClock(time), BeamType.Big, if (idle && saved == null) r.dim else r.ink)
                    when {
                        saved != null -> savedRow(m, r, glyphs.tape, saved)
                        idle -> levelBars(null, r.deep, 0f)
                        else -> levelBars(history, if (recording) r.hue else r.sub, slide.value)
                    }
                }
                // The keys stay at the foot, where they were under the top bar; the rest is [LIFT] higher.
                translate(0f, -CONTENT_TOP) {
                    recKeys(r, glyphs, idle, recording, paused, hasTapes, if (saved != null) pop.value else 0f)
                }
            }
        }
    }
}

/** The bars: how many, their pitch and width, their centre line, their height flat and at full level. */
private const val BAR_COUNT = 18
private const val BAR_PITCH = 24f
private const val BAR_W = 14f
private const val BAR_Y = 370f
private const val BAR_FLAT = 10f
private const val BAR_TALL = 84f

/** The bars centred across the page (started at x 24 they sit 10 px left of centre). */
private const val BAR_X = 240f - ((BAR_COUNT - 1) * BAR_PITCH + BAR_W) / 2f

/** How long the bars take to slide a place when a second comes in. */
private const val SLIDE_MS = 320

/** The saved row: a glyph row, its top and height, about the bars' line. */
private const val SAVED_TOP = 318f
private const val SAVED_H = 72f

/** The three keys across the page and their centre line. */
private val REC_KEY_X = floatArrayOf(82f, 240f, 398f)
private const val REC_KEY_Y = 560f

/** The page's glyphs. */
private class RecKeys(val tapes: Painter, val record: Painter, val pause: Painter, val stop: Painter, val tape: Painter)

/**
 * The level as [BAR_COUNT] rounded bars [BAR_W] wide, [BAR_PITCH] apart, centred on [BAR_Y], [BAR_FLAT] px
 * plus the rest of [BAR_TALL] by the second's level, in [c]. [levels] holds one more second than
 * shows, oldest first: while the bars [slide] (px, [BAR_PITCH] down to 0) that oldest one leaves on
 * the left as the newest comes in on the right, each fading with its share of the slide. At
 * rest ([levels] null) every bar is flat.
 */
private fun DrawScope.levelBars(levels: FloatArray?, c: Color, slide: Float) {
    val f = (slide / BAR_PITCH).coerceIn(0f, 1f)
    for (i in -1 until BAR_COUNT) {
        val alpha = when (i) {
            -1 -> f
            BAR_COUNT - 1 -> 1f - f
            else -> 1f
        }
        if (alpha <= 0.01f) continue
        val v = (levels?.getOrNull(i + 1) ?: 0f).coerceIn(0f, 1f)
        val h = BAR_FLAT + (BAR_TALL - BAR_FLAT) * v
        rect(BAR_X + i * BAR_PITCH + slide, BAR_Y - h / 2f, BAR_W, h, c.copy(alpha = c.alpha * alpha), BAR_W / 2f)
    }
}

/**
 * The take, a tape now, where the bars were (a focused row with a glyph): the tape glyph 40
 * at x 24 and its name at 84 in RowFocused, both in the hue, cut to the page's right margin.
 */
private fun DrawScope.savedRow(m: TextMeasurer, r: Ramp, tape: Painter, rec: Recording) {
    glyph(tape, 24f, SAVED_TOP + 16f, 40f, r.hue)
    text(m, 84f, SAVED_TOP + 49f, fit(m, rec.title, BeamType.RowFocused, 456f - 84f), BeamType.RowFocused, r.hue)
}

/**
 * The three keys (84 round, on [REC_KEY_Y]): the tapes as a ring in the hue (dim with no tapes),
 * with a dot of [saved] (0 to 1) of r 9 on its shoulder; record filled in the hue with a black
 * glyph, or pause as a ring while [recording]; stop filled while recording, a ring when [paused],
 * dim at rest.
 */
private fun DrawScope.recKeys(r: Ramp, g: RecKeys, idle: Boolean, recording: Boolean, paused: Boolean, hasTapes: Boolean, saved: Float) {
    quietKey(REC_KEY_X[0], g.tapes, if (hasTapes) r.hue else r.dim)
    if (saved > 0.01f) dot(REC_KEY_X[0] + 30f, REC_KEY_Y - 30f, 9f * saved, r.hue)
    if (recording) quietKey(REC_KEY_X[1], g.pause, r.hue) else actKey(r, REC_KEY_X[1], g.record, r.hue)
    when {
        recording -> actKey(r, REC_KEY_X[2], g.stop, r.hue)
        paused -> quietKey(REC_KEY_X[2], g.stop, r.hue)
        else -> quietKey(REC_KEY_X[2], g.stop, r.dim)
    }
}

/** A quiet key: a 3 px ring (r 40.5, so 84 across) and its glyph 40, both in [c]. */
private fun DrawScope.quietKey(cx: Float, p: Painter, c: Color) {
    drawCircle(c, 40.5f, Offset(cx, REC_KEY_Y), style = Stroke(3f))
    glyph(p, cx - 20f, REC_KEY_Y - 20f, 40f, c)
}

/** An act key: filled in [c], r 42, its glyph 40 in the ground. */
private fun DrawScope.actKey(r: Ramp, cx: Float, p: Painter, c: Color) {
    dot(cx, REC_KEY_Y, 42f, c)
    glyph(p, cx - 20f, REC_KEY_Y - 20f, 40f, r.ground)
}
