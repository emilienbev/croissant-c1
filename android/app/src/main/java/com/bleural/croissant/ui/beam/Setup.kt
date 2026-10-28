package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.setup.Gesture
import com.bleural.croissant.setup.SetupUi
import com.bleural.croissant.setup.Step
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin

/*
 * The first setup in Beam: hello, wi-fi, tailscale, server, time, one page each, over everything
 * while [Core.setup] is open. Between two steps, the flood (2026-09-29): the next step's hue comes
 * in from the right edge, the button's side, fills the screen with the step's glyph and name in
 * black, then sinks into the dots at the foot. Every page steps back through the elastic pull, a
 * tick up at the top or a finger pulled down; the edge drag goes back too.
 */

/** The step's hue: hello Ask's orange (it is what the button does), the network steps Settings', the time the Calendar's. */
internal fun stepHue(s: Step): Color = when (s) {
    Step.Hello -> hueOf(Feature.Ask)
    Step.Time -> hueOf(Feature.Calendar)
    else -> hueOf(Feature.Settings)
}

private fun stepWord(s: Step) = when (s) {
    Step.Hello -> "hello"
    Step.Wifi -> "wi-fi"
    Step.Tailscale -> "tailscale"
    Step.Server -> "server"
    Step.Time -> "time"
}

private fun stepGlyph(s: Step): ImageVector = when (s) {
    Step.Hello -> Glyphs.Ask
    Step.Wifi -> Glyphs.Wifi
    Step.Tailscale -> Glyphs.Tailnet
    Step.Server -> Glyphs.Server
    Step.Time -> Glyphs.Clock
}

internal fun stepRamp(s: Step): Ramp = ramp(stepHue(s))

/** Opens the setup at a step: Ask with no server, and Settings > Device. */
internal val LocalOpenSetup = staticCompositionLocalOf<(Step) -> Unit> { {} }

/**
 * What goes back instead of the setup's own step back while a step has something of its own
 * open (a password, the viewfinder, a typed address): the edge drag asks the newest first.
 */
@Stable
internal class SetupBacks {
    val list = mutableStateListOf<() -> Unit>()
}

private val LocalSetupBacks = staticCompositionLocalOf { SetupBacks() }

/** While this is composed, the edge drag calls [onBack] rather than stepping out of the step. */
@Composable
internal fun SetupBackTarget(onBack: () -> Unit) {
    val backs = LocalSetupBacks.current
    val latest = rememberUpdatedState(onBack)
    DisposableEffect(backs) {
        val fn = { latest.value() }
        backs.list.add(fn)
        onDispose { backs.list.remove(fn) }
    }
}

/** The Hello lesson's last gesture is the edge drag itself: while it listens, the drag is its, not a step back. */
private val LocalEdgeLesson = staticCompositionLocalOf<MutableState<(() -> Unit)?>> { mutableStateOf(null) }

/** The flood between two steps: [p] 0 to 1 coming in, 1 to 2 sinking; [to] whose glyph and name it shows (null: the end, the tick and "all set"). */
@Stable
private class Flood {
    val p = Animatable(0f)
    var hue by mutableStateOf(Color.Black)
    var to by mutableStateOf<Step?>(null)
    var at by mutableStateOf(Step.Hello)
    var skipped by mutableStateOf(emptySet<Step>())
    /** A tap on the flood: it stops holding the name and sinks straight away. */
    var hurried by mutableStateOf(false)
}

/**
 * The setup over everything, or nothing once it is closed and the last flood has sunk.
 * [covering] tells the shell the pages under it are hidden, so they give up the wheel.
 */
@Composable
internal fun BeamSetup(core: Core, device: DeviceStatus, covering: MutableState<Boolean>) {
    val setup by core.setup.collectAsStateWithLifecycle()
    val firstOpen = remember { setup.open }
    var shown by remember { mutableStateOf(if (setup.open) setup.step else null) }
    val flood = remember { Flood() }
    val backs = remember { SetupBacks() }
    val edgeLesson = remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(setup.open, setup.step) {
        val target = if (setup.open) setup.step else null
        if (target == shown) return@LaunchedEffect
        if (shown == null && firstOpen && target == setup.step && flood.p.value == 0f && !covering.value) {
            // The first start: the setup is simply there, under the boot animation.
            shown = target
            return@LaunchedEffect
        }
        flood.hue = if (target != null) stepHue(target) else hueOf(Feature.Ask)
        flood.to = target
        flood.at = target ?: Step.Time
        flood.skipped = setup.skipped
        Cues.play(Sound.ScreenIn)
        flood.hurried = false
        flood.p.snapTo(0f)
        flood.p.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        shown = target
        // The step's name, or all set at the end, held a second and a half (2026-09-29).
        // A tap cuts the hold short (2026-09-29); the sink is the same either way.
        withTimeoutOrNull(1_520) { snapshotFlow { flood.hurried }.first { it } }
        flood.p.animateTo(2f, tween(560, easing = FastOutSlowInEasing))
        flood.p.snapTo(0f)
    }
    val covers = shown != null
    SideEffect { covering.value = covers }
    val step = shown
    if (step == null && flood.p.value == 0f) return

    Box(
        Modifier.fillMaxSize().edgeBack {
            edgeLesson.value?.let { it(); return@edgeBack }
            backs.list.lastOrNull()?.let { it(); return@edgeBack }
            if (setup.open) { Cues.play(Sound.EdgeBack); core.setupBack() }
        },
    ) {
        if (step != null) {
            CompositionLocalProvider(LocalSetupBacks provides backs, LocalEdgeLesson provides edgeLesson) {
                StepPage(step, core, device, setup)
            }
        }
        if (flood.p.value > 0f) FloodCanvas(flood)
    }
}

/** One step's page, its foot drawn by the page itself. */
@Composable
private fun StepPage(step: Step, core: Core, device: DeviceStatus, setup: SetupUi) {
    when (step) {
        Step.Hello -> HelloStep(core, setup)
        Step.Wifi -> WifiStep(core, device, setup)
        Step.Tailscale -> TailscaleStep(core, setup)
        Step.Server -> ServerStep(core, setup)
        Step.Time -> TimeStep(core, setup)
    }
}

/**
 * A drag from the left edge (28 dp, as the stack's), heard before the page under it, taken
 * once it has gone 60 dp right. The page keeps every other touch.
 */
private fun Modifier.edgeBack(onBack: () -> Unit): Modifier = pointerInput(Unit) {
    val edge = 28f * density
    val slop = 16f * density
    val far = 60f * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edge) return@awaitEachGesture
        var dx = 0f
        var taken = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            dx += c.positionChange().x
            if (dx > slop) c.consume()
            if (!taken && dx > far) { taken = true; onBack() }
        }
    }
}

// ---- the flood ----------------------------------------------------------------------------

@Composable
private fun FloodCanvas(flood: Flood) {
    val m = rememberTextMeasurer(cacheSize = 4)
    val painter = rememberVectorPainter(flood.to?.let(::stepGlyph) ?: Glyphs.Check)
    // It takes every touch while it is up, so nothing under it is tapped; a tap hurries it.
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume(); flood.hurried = true } }) {
        pageScale {
            val p = flood.p.value
            if (p <= 1f) {
                // In from the right edge, its leading edge round, until the whole screen is the hue.
                val left = 480f * (1f - p) - 70f * p
                drawRoundRect(flood.hue, Offset(left, 0f), Size(480f - left + 200f, 640f), CornerRadius(70f))
            } else {
                // Sinking: its top comes down to the foot, round, then it fades off the dots.
                val q = p - 1f
                val top = 548f * q
                val a = if (q < 0.82f) 1f else (1f - (q - 0.82f) / 0.18f).coerceIn(0f, 1f)
                drawRoundRect(flood.hue.copy(alpha = a), Offset(0f, top), Size(480f, 640f - top + 120f), CornerRadius(if (q > 0.05f) 60f else 0f))
            }
            // The step's glyph and name, black on the hue, while it covers the screen; at the end the tick and all set.
            val named = when {
                p < 0.6f -> 0f
                p <= 1f -> (p - 0.6f) / 0.4f
                else -> (1f - (p - 1f) / 0.3f).coerceIn(0f, 1f)
            }
            if (named > 0f) {
                glyph(painter, 196f, 198f, 88f, GROUND.copy(alpha = named))
                text(m, 240f, 390f, flood.to?.let(::stepWord) ?: "all set", BeamType.Name.copy(fontSize = 60f.pxSp, letterSpacing = (-1.6f).pxSp), GROUND.copy(alpha = named), Anchor.Middle)
            }
            // The steps' dots, black on the hue, where the page's own will be.
            val dots = when {
                p < 0.7f -> 0f
                p <= 1f -> (p - 0.7f) / 0.3f
                else -> (1f - (p - 1.82f) / 0.18f).coerceIn(0f, 1f)
            }
            if (dots > 0f && flood.to != null) Step.entries.forEach { s ->
                val x = 168f + s.ordinal * 36f
                val c = GROUND.copy(alpha = dots)
                when {
                    s == flood.at -> dot(x, 602f, 9f, c)
                    s in flood.skipped || s.ordinal > flood.at.ordinal -> drawCircle(c, 6f, Offset(x, 602f), style = Stroke(2.5f))
                    else -> dot(x, 602f, 7f, c)
                }
            }
        }
    }
}

// ---- the foot and the beat ----------------------------------------------------------------

/**
 * The foot: the five steps as dots on y 602, done in their hue, this one white and larger, skipped
 * a ring in sub, ahead faint; and a word at x 24 on 612: skip in sub while the step has not been
 * done, next in ink when it already is (a setup run again).
 */
@Composable
private fun BoxScope.Foot(at: Step, setup: SetupUi, word: String?, onWord: () -> Unit, fade: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        pageScale {
            // A list under it fades out above the foot rather than running under the dots.
            if (fade) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to GROUND, startY = 528f, endY = 566f), Offset(0f, 528f), Size(480f, 38f))
            if (fade) drawRect(GROUND, Offset(0f, 566f), Size(480f, 74f))
            Step.entries.forEach { s ->
                val x = 168f + s.ordinal * 36f
                when {
                    s == at -> dot(x, 602f, 9f, INK)
                    s in setup.skipped -> drawCircle(SUB, 6f, Offset(x, 602f), style = Stroke(2.5f))
                    s.ordinal < at.ordinal -> dot(x, 602f, 7f, stepHue(s))
                    else -> dot(x, 602f, 7f, FAINT)
                }
            }
        }
    }
    // The whole corner takes the tap, not just the word: 148 across (clear of the first dot at
    // 159) from y 566 to the foot, the word on 612 inside it (2026-09-29: too small).
    if (word != null) Box(
        Modifier.align(Alignment.TopStart).padding(top = FOOT_TOP.px).size(148.px, (640f - FOOT_TOP).px).tappable(onWord),
    ) {
        Text(word, style = BeamType.Value, color = if (word == "skip") SUB else INK,
            modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = (612f - FOOT_TOP).px))
    }
}

/** Where the foot's tap target starts. */
private const val FOOT_TOP = 566f

/** A step's page: its header word, [content] from y 66, and the foot over it. */
@Composable
internal fun SetupStepPage(step: Step, setup: SetupUi, word: String?, onWord: () -> Unit, over: (@Composable BoxScope.() -> Unit)? = null, list: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val r = stepRamp(step)
    Box(Modifier.fillMaxSize()) {
        PageTop(stepWord(step), r, content = content)
        Foot(step, setup, word, onWord, fade = list)
        over?.invoke(this)
    }
}

/** The success beat: the step's tick in its hue, what it got, a fact under it; held a moment before the flood. */
@Composable
internal fun Beat(step: Step, setup: SetupUi, word: String, fact: String?, foot: String? = null, onFoot: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val m = rememberTextMeasurer(cacheSize = 4)
    val check = rememberVectorPainter(Glyphs.Check)
    val hue = stepHue(step)
    SetupStepPage(step, setup, foot, onFoot) {
        if (onBack != null) StillPull(onBack, stepRamp(step)) { BeatCanvas(m, check, hue, word, fact) } else BeatCanvas(m, check, hue, word, fact)
    }
}

@Composable
private fun BeatCanvas(m: androidx.compose.ui.text.TextMeasurer, check: androidx.compose.ui.graphics.painter.Painter, hue: Color, word: String, fact: String?) {
        Canvas(Modifier.fillMaxSize()) {
            pageScale {
                translate(0f, -DRAWN_TOP) {
                    dot(240f, 270f, 66f, hue)
                    glyph(check, 200f, 230f, 80f, GROUND)
                    text(m, 240f, 410f, fit(m, word, BeamType.Second, 432f), BeamType.Second, INK, Anchor.Middle)
                    if (fact != null) text(m, 240f, 456f, fact, BeamType.Value, SUB, Anchor.Middle)
                }
            }
        }
}

/** A page that does not scroll but still takes the pull: a tick up, or a finger pulled down, steps back. */
@Composable
internal fun StillPull(onBack: () -> Unit, r: Ramp, content: @Composable BoxScope.() -> Unit) {
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(h), content = content)
        }
    }
}

// ---- hello: the button and the wheel ----------------------------------------------------

private enum class Lesson(val first: String, val second: String?) {
    Hold("hold to ask", null),
    Press("press to sleep", null),
    Twice("press twice", "for the camera"),
    Turn("turn to scroll", null),
    /** The step back: the pull's two dots, a finger down or a tick up; the edge drag counts too. */
    Down("swipe down", "to go back"),
}

/**
 * Hello: one gesture a page, the R1 face on with the part it is about lit, what it does in big
 * words, "try it" under them ("release" while held). Each is tried and only heard: the button is
 * the setup's ([Core.gestures]), so nothing asks, opens or sleeps; the wheel is heard three ticks;
 * the last is the step back, whose pull shows its two dots and goes nowhere, the edge drag counting
 * as well (2026-09-29). Done, the words take the hue and the next gesture comes; after the last,
 * the next step.
 */
@Composable
private fun HelloStep(core: Core, setup: SetupUi) {
    val hue = stepHue(Step.Hello)
    var lesson by remember { mutableStateOf(Lesson.Hold) }
    var held by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var ticks by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        core.gestures.collect { g ->
            if (done) return@collect
            when (lesson) {
                Lesson.Hold -> when (g) {
                    Gesture.HoldStart -> held = true
                    Gesture.HoldEnd -> if (held) { held = false; done = true }
                    else -> Unit
                }
                Lesson.Press -> if (g == Gesture.Press) done = true
                Lesson.Twice -> if (g == Gesture.Twice) done = true
                else -> Unit
            }
        }
    }
    LaunchedEffect(lesson, done) {
        if (!done) return@LaunchedEffect
        Cues.play(Sound.Commit)
        delay(900)
        val next = Lesson.entries.getOrNull(lesson.ordinal + 1)
        if (next == null) core.setupNext() else { lesson = next; done = false; held = false; ticks = 0 }
    }
    // The wheel's lesson hears the wheel; the others step back with a tick up, as every page,
    // except the last, which is that very pull, only heard.
    val pull = rememberPull { if (lesson == Lesson.Down) { if (!done) done = true } else core.setupBack() }
    WheelTarget { delta ->
        if (lesson == Lesson.Turn) {
            if (!done) { ticks += 1; if (ticks >= 3) done = true }
            true
        } else {
            if (delta < 0) pull.stepBack()
            false
        }
    }
    val edge = LocalEdgeLesson.current
    DisposableEffect(lesson) {
        edge.value = if (lesson == Lesson.Down) ({ if (!done) done = true }) else null
        onDispose { edge.value = null }
    }
    val m = rememberTextMeasurer(cacheSize = 6)
    val moon = rememberVectorPainter(Glyphs.Moon)
    val camera = rememberVectorPainter(Glyphs.Camera)
    val wave = rememberInfiniteTransition(label = "held")
    val phase by wave.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    SetupStepPage(Step.Hello, setup, "skip", { core.setupNext(skip = true) }) {
        Box(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(rememberScrollState())) {
            Canvas(Modifier.fillMaxWidth().height((640f - CONTENT_TOP).px)) {
                pageScale {
                    translate(0f, -DRAWN_TOP) {
                        r1(lesson, hue, live = held || (done && lesson == Lesson.Hold), phase = phase, ticks = ticks, moon = { x, y, s -> glyph(moon, x, y, s, SUB) }, camera = { x, y, s -> glyph(camera, x, y, s, hue) })
                        val col = if (done) hue else INK
                        text(m, 240f, 484f, lesson.first, BeamType.Second, col, Anchor.Middle)
                        lesson.second?.let { text(m, 240f, 534f, it, BeamType.Second, col, Anchor.Middle) }
                        val hint = when {
                            held -> "release"
                            lesson == Lesson.Down -> "or from the left edge"
                            else -> "try it"
                        }
                        if (!done) text(m, 240f, if (lesson.second != null) 574f else 534f, hint, BeamType.Value, SUB, Anchor.Middle)
                    }
                }
            }
        }
    }
}

/**
 * The R1 face on, centred on (240, 280), as a photo of the device has it (2026-09-29): a black body
 * in the white line, the screen left; on the front right, in the body, two rounded recesses, the
 * camera's with the lens module a rounded bar in it, and under it the wheel's with the wheel lying
 * across it, its rims two lines; the side button on the right edge. The part the lesson is about is
 * lit in [hue].
 */
private fun DrawScope.r1(lesson: Lesson, hue: Color, live: Boolean, phase: Float, ticks: Int, moon: DrawScope.(Float, Float, Float) -> Unit, camera: DrawScope.(Float, Float, Float) -> Unit) {
    val x0 = 90f
    val y0 = 155f
    val pressLit = lesson == Lesson.Hold || lesson == Lesson.Press || lesson == Lesson.Twice
    val screen = Color(0xFF141414)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color, line: Color, width: Float) {
        drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r))
        drawRoundRect(line, Offset(x, y), Size(w, h), CornerRadius(r), style = Stroke(width))
    }
    box(x0 + 290f, y0 + 48f, 22f, 70f, 8f, if (pressLit) hue else GROUND, INK, 4f)
    box(x0, y0, 300f, 250f, 46f, GROUND, INK, 6f)
    val sx = x0 + 24f
    val sy = y0 + 24f
    val sw = 188f
    val sh = 202f
    box(sx, sy, sw, sh, 20f, if (lesson == Lesson.Press) GROUND else screen, INK, 3f)
    // The camera: its recess, and the lens module across it.
    val shooting = lesson == Lesson.Twice
    box(x0 + 226f, y0 + 34f, 56f, 50f, 15f, GROUND, INK, 4f)
    box(x0 + 237f, y0 + 48f, 34f, 22f, 8f, if (shooting) hue else screen, if (shooting) hue else INK, 2.5f)
    // The wheel: its recess, the cylinder lying in it, the two rims.
    val turning = lesson == Lesson.Turn
    box(x0 + 226f, y0 + 98f, 56f, 70f, 17f, GROUND, INK, 4f)
    box(x0 + 233f, y0 + 108f, 42f, 50f, 15f, if (turning) hue else screen, if (turning) hue else INK, 3f)
    for (y in floatArrayOf(120f, 146f)) drawLine(if (turning) GROUND else SUB, Offset(x0 + 241f, y0 + y), Offset(x0 + 267f, y0 + y), 2.5f, StrokeCap.Round)
    val mx = sx + sw / 2
    val my = sy + sh / 2
    fun line(vararg pts: Float, c: Color = hue, w: Float = 4f) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0], pts[1]); var i = 2
            while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        }
        drawPath(path, c, style = Stroke(w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
    when (lesson) {
        Lesson.Hold -> {
            val bx = x0 + 312f
            val by = y0 + 83f
            floatArrayOf(24f, 40f, 56f).forEachIndexed { k, r ->
                drawArc(hue.copy(alpha = 1f - k * 0.28f), -44f, 88f, false, Offset(bx - r, by - r), Size(2 * r, 2 * r), style = Stroke(4f, cap = StrokeCap.Round))
            }
            if (live) floatArrayOf(18f, 42f, 64f, 36f, 22f).forEachIndexed { k, b ->
                val h = b * (0.65f + 0.35f * sin(phase + k * 1.3f))
                drawRoundRect(hue, Offset(mx - 56f + k * 24f, my - h / 2), Size(14f, h), CornerRadius(7f))
            }
        }
        Lesson.Press -> {
            line(x0 + 352f, y0 + 83f, x0 + 328f, y0 + 83f)
            line(x0 + 337f, y0 + 73f, x0 + 327f, y0 + 83f, x0 + 337f, y0 + 93f)
            moon(mx - 22f, my - 22f, 44f)
        }
        Lesson.Twice -> {
            for (d in floatArrayOf(0f, 18f)) line(x0 + 346f + d, y0 + 73f, x0 + 336f + d, y0 + 83f, x0 + 346f + d, y0 + 93f)
            camera(mx - 30f, my - 30f, 60f)
        }
        Lesson.Turn -> {
            line(x0 + 318f, y0 + 122f, x0 + 327f, y0 + 113f, x0 + 336f, y0 + 122f, w = 3f)
            line(x0 + 318f, y0 + 146f, x0 + 327f, y0 + 155f, x0 + 336f, y0 + 146f, w = 3f)
            val on = ticks % 4
            for (k in 0 until 4) drawRoundRect(if (k == on) hue else Color(0xFF3A3A3A), Offset(sx + 22f, sy + 36f + k * 42f), Size(if (k == on) 110f else 128f - k * 14f, 16f), CornerRadius(8f))
        }
        Lesson.Down -> {
            // The pull's two dots at the top of the page, and the finger's way down.
            dot(mx - 11f, sy + 24f, 6f, hue)
            dot(mx + 11f, sy + 24f, 6f, hue)
            line(mx, sy + 52f, mx, sy + 148f, w = 5f)
            line(mx - 16f, sy + 132f, mx, sy + 148f, mx + 16f, sy + 132f, w = 5f)
        }
    }
}
