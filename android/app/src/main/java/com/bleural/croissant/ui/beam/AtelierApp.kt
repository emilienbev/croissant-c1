package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState
import com.bleural.croissant.atelier.AppRuntime
import com.bleural.croissant.atelier.Keys
import com.bleural.croissant.atelier.Piece
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.rememberPull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * An app's page, in the app's own hue: its name the header word, then its pieces in the order its
 * render gave them, drawn with Beam's kit. The app runs while this page is in front (Shell.kt tells
 * Core). Page pixels, on the lifted page (content from 66).
 */

/** No app running yet: nothing but the header. */
private val LOADING: StateFlow<AppRuntime.View> = MutableStateFlow(AppRuntime.View.Loading)

/**
 * A touched piece while its touch waits or runs, how fast it goes there, and after how long:
 * a quick handler never shows it. ~~At once~~ until 2026-10-17: the first frame of the fade
 * was already a layer, which cut a focused pill's ring to its four corners on every tap.
 */
private const val WAIT_DIM = 0.4f
private const val WAIT_MS = 220
private const val WAIT_AFTER = 150

/** Under the last piece, so it scrolls clear of the foot. */
private const val FOOT = 64f

/** How far the wheel scrolls a tick where there is nothing to step to. */
private const val STEP = 96f

/** The page under a hold's words: moved down 176 (42 more a line of words past the first), at 22 %. */
private const val HEARD_PUSH = 176f
private const val HEARD_ALPHA = 0.22f
private val HEARD_SPRING = spring<Float>(dampingRatio = 0.85f, stiffness = 450f)

/** A failed touch's line at the foot: a moment, then fading. */
private const val FAILED_HOLD_MS = 1200L
private const val FAILED_FADE_MS = 1300

private val NUMBER_KEYS = KeyboardOptions(keyboardType = KeyboardType.Number)
private val TEXT_KEYS = KeyboardOptions(keyboardType = KeyboardType.Text)

/**
 * App [id]'s page: nothing but the header while it starts, the error with a reload key when
 * it could not start or draw, else its pieces ([AppShown]).
 */
@Composable
internal fun AtelierAppPage(id: String, core: Core, nav: NavState) {
    val apps by core.atelier.apps.collectAsStateWithLifecycle()
    val open by core.atelier.open.collectAsStateWithLifecycle()
    // The runtime this page drew last: kept while the page slides out after its app stopped.
    val kept = remember { arrayOfNulls<AppRuntime>(1) }
    val rt = open?.takeIf { it.app.id == id }?.also { kept[0] = it } ?: kept[0]
    val app = apps.firstOrNull { it.id == id } ?: rt?.app ?: return
    val r = appRamp(app)
    val name = app.manifest.name
    val view by (rt?.view ?: LOADING).collectAsStateWithLifecycle()
    when (val v = view) {
        AppRuntime.View.Loading -> {
            val pull = rememberPull(nav::pop)
            WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
            PageTop(name, r) { PulledCanvas(pull, r) {} }
        }
        is AppRuntime.View.Failed -> AppFailed(name, r, v.failure, onReload = { rt?.reload() }, nav)
        is AppRuntime.View.Shown -> if (rt != null) AppShown(name, r, rt, v, core, nav)
    }
}

/**
 * The script could not start or draw its page: "it ran into an error", the error in danger, where
 * it was in sub; the quiet key runs it again from its saved state.
 */
@Composable
private fun AppFailed(name: String, r: Ramp, f: AppRuntime.Failure, onReload: () -> Unit, nav: NavState) {
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    val side = Modifier.padding(start = 24.px, end = 24.px)
    PageTop(name, r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            // On 112, the error from 172, where 52 under its last line.
            Baselines(listOf(46f, 60f, 52f), bottom = 112f) {
                Text("it ran into an error", style = BeamType.Body, color = r.ink, modifier = side)
                Text(f.message, style = BeamType.Body, color = r.danger, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = side)
                Text(failedWhere(f), style = BeamType.Value, color = r.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = side)
            }
        }
        QuietKey(Glyphs.Restart, r, onClick = { Cues.play(Sound.Commit); onReload() })
    }
}

/**
 * The pieces: words as body text; a big value as the kit's big number with its name under it in the
 * hue; a row as the kit's row, its value at its end; a toggle as the kit's row with the switch; a
 * field as the kit's field on Beam's keys (the number pad for numbers), its text to the app as
 * typed and again when the keys go; pills in the flow from the left, a focused one ringed in the
 * hue. The wheel moves among what a tap acts on ([touchable]), the first to begin with, keeping it
 * in view, and scrolls where there is nothing more; up from the first, at the top, is the pull
 * back. A touch while a handler runs waits its turn, its piece dimmed. A hold on a page that takes
 * words shows them at the top with the level, the page moved down under them at 22 %. A touch that
 * ran into an error leaves the page as it was with a line at the foot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppShown(name: String, r: Ramp, rt: AppRuntime, shown: AppRuntime.View.Shown, core: Core, nav: NavState) {
    val page = shown.page
    val pieces = page.pieces
    val voice by core.voice.collectAsStateWithLifecycle()
    val heard = page.takesWords && voice.dictate && (voice.phase == Phase.Listening || voice.phase == Phase.Transcribing)

    // Touches, one at a time, each sent on the page the last one left.
    val queue = remember(rt) { mutableStateListOf<Touch>() }
    var running by remember(rt) { mutableIntStateOf(-1) }
    LaunchedEffect(rt) {
        while (true) {
            snapshotFlow { queue.firstOrNull() }.filterNotNull().first()
            val ready = combine(rt.view, rt.busy) { v, busy -> if (busy) null else v as? AppRuntime.View.Shown }.filterNotNull().first()
            val t = queue.removeFirstOrNull() ?: continue
            val on = ready.page
            val (handler, value) = resolve(on, t) ?: continue
            if (t.act.dims) running = t.at
            rt.call(on.gen, handler, value)
            // Done when it has drawn again (a handler that threw draws too); a handler may wait on the model.
            withTimeoutOrNull(AppRuntime.HANDLER_MS + 5_000L) {
                rt.view.first { (it as? AppRuntime.View.Shown)?.page?.gen != on.gen }
            }
            running = -1
        }
    }
    fun touch(t: Touch) {
        if (t.act == Act.Type) queue.removeAll { it.at == t.at && it.act == Act.Type }
        queue.add(t)
    }

    // The wheel's place among the pieces a tap acts on.
    val touchables = remember(pieces) { pieces.indices.filter { touchable(pieces[it]) } }
    var focus by remember { mutableIntStateOf(0) }
    val f = focus.coerceIn(0, maxOf(0, touchables.lastIndex))
    val focused = touchables.getOrNull(f) ?: -1

    // Where each piece is, to keep the one in hand in view: its top and the column's, in root
    // pixels from the last layout (their difference is its place in the column), and its height.
    val tops = remember { HashMap<Int, Float>() }
    val heights = remember { HashMap<Int, Int>() }
    val columnTop = remember { FloatArray(1) }
    val viewport = remember { IntArray(1) }
    val scroll = rememberScrollState()
    val pull = rememberPull(nav::pop)
    val scope = rememberCoroutineScope()
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    fun show(i: Int) {
        val top = (tops[i] ?: return) - columnTop[0]
        val h = heights[i] ?: return
        val margin = 24f * unit
        val now = scroll.value.toFloat()
        val to = when {
            top - margin < now -> top - margin
            top + h + margin > now + viewport[0] -> top + h + margin - viewport[0]
            else -> return
        }
        scope.launch { scroll.animateScrollTo(to.roundToInt().coerceIn(0, scroll.maxValue)) }
    }
    WheelTarget { delta ->
        if (delta < 0) {
            if (f > 0) { focus = f - 1; show(touchables[f - 1]); return@WheelTarget true }
            if (scroll.value > 0) scope.launch { scroll.glideBy(-(STEP * unit).roundToInt()) } else pull.stepBack()
            false
        } else {
            if (f < touchables.lastIndex) { focus = f + 1; show(touchables[f + 1]); return@WheelTarget true }
            scope.launch { scroll.glideBy((STEP * unit).roundToInt()) }
            false
        }
    }

    // The hold's words: how many lines they take moves the page down further.
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val lines = if (!heard) 1 else remember(voice.transcript) {
        measurer.measure(voice.transcript + "|", BeamType.Body, constraints = Constraints(maxWidth = (432f * unit).roundToInt())).lineCount.coerceIn(1, 3)
    }
    val down = animateFloatAsState(if (heard) 1f else 0f, HEARD_SPRING, label = "heard")
    val push = (HEARD_PUSH + 42f * (lines - 1)) * unit

    PageTop(name, r) {
        Box(
            Modifier.fillMaxSize().onSizeChanged { viewport[0] = it.height }
                .graphicsLayer { translationY = push * down.value; alpha = 1f - (1f - HEARD_ALPHA) * down.value.coerceIn(0f, 1f) },
        ) {
            Column(
                Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)
                    .onGloballyPositioned { columnTop[0] = it.positionInRoot().y },
            ) {
                var prev: Lay? = null
                var i = 0
                while (i < pieces.size) {
                    val lay = layOf(pieces[i])
                    Spacer(Modifier.height(pieceGap(prev, lay).px))
                    val from = i
                    var to = i
                    if (lay == Lay.Pills) while (to + 1 < pieces.size && pieces[to + 1] is Piece.Pill) to++
                    val each: @Composable (Int) -> Unit = { j ->
                        PieceView(
                            pieces[j], r, focused = j == focused,
                            waiting = j == running || queue.any { it.at == j && it.act.dims },
                            onTouch = { act, text ->
                                touchables.indexOf(j).takeIf { it >= 0 }?.let { focus = it }
                                touch(Touch(j, act, text))
                            },
                            modifier = Modifier.onGloballyPositioned { c -> tops[j] = c.positionInRoot().y; heights[j] = c.size.height },
                        )
                    }
                    if (lay == Lay.Pills) FlowRow(
                        Modifier.fillMaxWidth().padding(start = 24.px, end = 24.px),
                        horizontalArrangement = Arrangement.spacedBy(24.px),
                        verticalArrangement = Arrangement.spacedBy(24.px),
                    ) { for (j in from..to) each(j) }
                    else each(i)
                    prev = lay
                    i = to + 1
                }
                Spacer(Modifier.height(FOOT.px))
            }
            TopFade(r, scroll)
        }
        if (heard || down.value > 0.01f) Heard(voice, r, { core.level.value }, lines, { down.value.coerceIn(0f, 1f) })
        FailedFoot(shown, r)
    }
}

/** One piece, [waiting] dimmed while its touch waits or runs. */
@Composable
private fun PieceView(p: Piece, r: Ramp, focused: Boolean, waiting: Boolean, onTouch: (Act, String?) -> Unit, modifier: Modifier) {
    val dim = animateFloatAsState(if (waiting) WAIT_DIM else 1f, tween(WAIT_MS, delayMillis = if (waiting) WAIT_AFTER else 0), label = "waiting")
    // Each drawing faded on its own, no layer: a layer clips to the piece, and a pill's ring is drawn outside it.
    Box(modifier.graphicsLayer { alpha = dim.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
        when (p) {
            is Piece.Words -> TextShelf(p.text, r)
            is Piece.Big -> BigValue(p.value, p.label, r)
            is Piece.Row -> ShelfRow(
                p.text, r, focused = focused, value = p.value,
                onClick = if (p.tap == null) null else ({ Cues.play(Sound.Commit); onTouch(Act.Tap, null) }),
            )
            is Piece.Toggle -> ShelfRow(
                p.text, r, focused = focused,
                onClick = if (p.change == null) null else ({ Cues.play(if (p.on) Sound.SwitchOff else Sound.SwitchOn); onTouch(Act.Flip, null) }),
                trailing = { Toggle(p.on, r) },
            )
            is Piece.Field -> AppField(p, r, onType = { onTouch(Act.Type, it) }, onDone = { onTouch(Act.Done, it) })
            is Piece.Pill -> AppPill(p.text, r, focused, onClick = { if (p.tap != null) { Cues.play(Sound.Commit); onTouch(Act.Tap, null) } })
        }
    }
}

/**
 * A big value: the kit's big number (132 Light) in ink from x 14, its baseline [top] under what is
 * above it, smaller when it would run off the page, a point between digits opened 9 px either side
 * (at -5 the font sets 32.20 as 3220); its name 54 under it in the hue, as a row's words. It ends 8
 * under its last baseline. The model test's score and count are drawn with it too.
 */
@Composable
internal fun BigValue(value: String, label: String?, r: Ramp, top: Float = 110f) {
    val m = rememberTextMeasurer(cacheSize = 4)
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    val k = remember(value, unit) {
        val w = m.measure(value, BeamType.Big, softWrap = false, maxLines = 1).size.width / unit
        if (w <= BIG_ROOM) 1f else (BIG_ROOM / w).coerceAtLeast(0.35f)
    }
    val style = if (k == 1f) BeamType.Big else BeamType.Big.copy(fontSize = (132f * k).pxSp, letterSpacing = (-5f * k).pxSp)
    val shown = remember(value, k) { opened(value, (4f * k).pxSp) }
    Baselines(if (label != null) listOf(top, 54f) else listOf(top), bottom = 8f) {
        Text(shown, style = style, color = r.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.px, end = 14.px))
        if (label != null) Text(label, style = BeamType.Row, color = r.hue, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.px, end = 24.px))
    }
}

/** The big number's room: from x 14 to 466. */
private const val BIG_ROOM = 452f

/** [s] with each point between two digits and the digit before it set at [spacing], not the number's -5. */
private fun opened(s: String, spacing: TextUnit): AnnotatedString = buildAnnotatedString {
    append(s)
    for (i in 1 until s.length - 1) {
        if (s[i] == '.' && s[i - 1].isDigit() && s[i + 1].isDigit()) addStyle(SpanStyle(letterSpacing = spacing), i - 1, i + 1)
    }
}

/**
 * A field: the kit's field with the app's hint in sub while it is empty, on the number pad when the
 * app asks for a number. Each new text goes to its `change`, and the text to its `done` when the
 * keys go.
 */
@Composable
private fun AppField(p: Piece.Field, r: Ramp, onType: (String) -> Unit, onDone: (String) -> Unit) {
    val text = remember { arrayOf(p.value) }
    val had = remember { booleanArrayOf(false) }
    Field(
        p.value, { text[0] = it; onType(it) }, r,
        Modifier.onFocusChanged { s ->
            if (had[0] && !s.hasFocus) onDone(text[0])
            had[0] = s.hasFocus
        },
        placeholder = p.hint,
        keyboardOptions = if (p.keys == Keys.Number) NUMBER_KEYS else TEXT_KEYS,
    )
}

/** A pill: the kit's ([Pill]), black words on the hue, pushed in as it is tapped. ~~Focused, a 3 px ring in the hue 6 clear of it~~ until 2026-10-17: no ring, the push says it. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun AppPill(word: String, r: Ramp, focused: Boolean, onClick: () -> Unit) = Pill(word, r.hue, GROUND, onClick = onClick)

/**
 * A hold's words over the page: as heard, from 112, the caret in the hue while you talk; the level
 * under them in the hue, its middle on 184 (42 lower a line past the first). Faded in and out with
 * the page's move, [k].
 */
@Composable
private fun BoxScope.Heard(voice: VoiceState, r: Ramp, level: () -> Float, lines: Int, k: () -> Float) {
    val talking = voice.phase == Phase.Listening
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = k() }) {
        val words = Modifier.padding(start = 24.px, end = 24.px).wordsAt(112f - CONTENT_TOP)
        if (talking) CaretWords(voice.transcript, r.ink, r.hue, words, style = BeamType.Body)
        else Text(voice.transcript, style = BeamType.Body, color = r.ink, modifier = words)
        if (talking) AppLevel(level, r, Modifier.padding(top = (184f - 40f - CONTENT_TOP + 42f * (lines - 1)).px))
    }
}

/** The level while you hold: 18 bars 14 wide, 24 apart from x 29, 14 (a dot) to 80 tall on their middle; the newest on the right every 70 ms. */
@Composable
private fun AppLevel(level: () -> Float, r: Ramp, modifier: Modifier) {
    val bars = remember { FloatArray(18) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(70)
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = level().coerceIn(0f, 1f)
            tick++
        }
    }
    Canvas(modifier.fillMaxWidth().height(80.px)) {
        if (tick < 0) return@Canvas
        pageScale {
            for (i in bars.indices) {
                val h = 14f + 66f * bars[i]
                rect(29f + i * 24f, 40f - h / 2f, 14f, h, r.hue, 7f)
            }
        }
    }
}

/**
 * A touch or a hold that ran into an error, the page left as it was: what was touched and "ran into
 * an error" in danger at 26 from x 24 on 584, over the foot's black (500 to 560), a moment and then
 * fading. Each new error shows it again.
 */
@Composable
private fun BoxScope.FailedFoot(shown: AppRuntime.View.Shown, r: Ramp) {
    val f = shown.failed
    val a = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (f == null) return@LaunchedEffect a.snapTo(0f)
        Cues.play(Sound.ExchangeFailed)
        a.snapTo(1f)
        delay(FAILED_HOLD_MS)
        a.animateTo(0f, tween(FAILED_FADE_MS))
    }
    if (f == null) return
    val words = failedFoot(f)
    val text = rememberTextMeasurer(cacheSize = 4)
    Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(140.px)) {
        val k = a.value
        if (k <= 0.01f) return@Canvas
        pageScale {
            // Page y 500 to 640 here.
            drawRect(Brush.verticalGradient(0f to GROUND.copy(alpha = 0f), 1f to GROUND.copy(alpha = k), startY = 0f, endY = 60f), Offset.Zero, Size(480f, 60f))
            drawRect(GROUND.copy(alpha = k), Offset(0f, 60f), Size(480f, 80f))
            text(text, 24f, 84f, fit(text, words, BeamType.Label, 432f), BeamType.Label, r.danger.copy(alpha = k))
        }
    }
}
