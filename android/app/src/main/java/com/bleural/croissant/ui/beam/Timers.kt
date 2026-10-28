package com.bleural.croissant.ui.beam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.bleural.croissant.data.TimersStore
import com.bleural.croissant.ui.keepInView
import androidx.compose.ui.unit.dp
import com.bleural.croissant.ui.rememberPull
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Timer
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.countdownText
import com.bleural.croissant.ui.fraction
import com.bleural.croissant.ui.millisLeft
import com.bleural.croissant.ui.rememberSecondTick
import com.bleural.croissant.ui.tappable
import com.bleural.croissant.ui.wrap

/*
 * Timers in Beam: the running timers as big times over segment meters, a new timer as three
 * columns, and the ringing flood, the one screen filled with the hue, the croissant ringing on it
 * and everything else black.
 */

/**
 * Timers: every running timer, soonest first, under "running" and their count. Each is its time in
 * [BeamType.Mid] at the left, its name right in [BeamType.Row] and a segment meter under them, lit
 * for what is left. The focused one's time, name (Medium) and meter are in the hue, the others'
 * time in ink and name and meter in sub. The wheel moves the focus; a tap focuses one and asks
 * through the sheet before it goes. The knob pushes a new timer; a tick up on the first steps back.
 * 2026-10-14: a tap on a timer's name types it where it is, the keys up and the knob gone; done or
 * the keys put away keep it, empty it is "timer" again.
 */
@Composable
internal fun TimersPage(items: List<Timer>, onCancel: (String) -> Unit, onRename: (String, String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Timers)
    val timers = remember(items) { items.sortedBy { it.endsAt } }
    val now by rememberSecondTick()
    var asking by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    // A timer that ends while the sheet asks about it takes the sheet with it.
    LaunchedEffect(timers) { if (timers.none { it.id == asking }) asking = null }
    // Lazy item 0 is the label, so timer i is item i + 1 and the label stays above the first.
    val list = rememberRows(timers.size, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    // The name being typed stays in view over the keys.
    LaunchedEffect(naming) {
        val i = timers.indexOfFirst { it.id == naming }
        if (i < 0) return@LaunchedEffect
        snapshotFlow { list.list.layoutInfo.viewportSize }.collect { list.list.keepInView(i + 1) }
    }
    PageTop("timers", r) {
        ShelfList(list, r) {
            item(key = "label") { if (timers.isNotEmpty()) DotLabel("running", r, count = timers.size, top = LABEL_FIRST) }
            items(timers.size, key = { timers[it].id }) { i ->
                val t = timers[i]
                TimerRow(
                    t, now, r, focused = list.focus == i, naming = naming == t.id,
                    onClick = { list.focus = i; asking = t.id; Cues.play(Sound.SheetIn) },
                    onName = { list.focus = i; naming = t.id },
                    onNamed = { name -> if (naming == t.id) naming = null; onRename(t.id, name) },
                )
            }
            item { Spacer(Modifier.height(112.px)) }
        }
        if (naming == null) KnobKey(Glyphs.Plus, r, onClick = onAdd)
        asking?.let { id ->
            Sheet(
                r, "delete", colour = r.danger,
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = null; onCancel(id) },
            )
        }
    }
}

/**
 * One timer, 118 tall: the time from x 20 on baseline 70, the name ending at 456 on 66, taking
 * what the time leaves (20 clear, cut with an ellipsis, gone when fewer than three characters
 * fit), and the meter at 88: 18 rounded segments 14 wide and 12 tall, 24 apart from x 24, lit
 * from the left for what is left, the rest faint. Unnamed, it is "timer". A tap right of the
 * time, where the name is, types the name ([naming]: the field in its place, the words running
 * left from 456, "timer" in sub while empty); anywhere else it is [onClick].
 */
@Composable
private fun TimerRow(t: Timer, now: Long, r: Ramp, focused: Boolean, naming: Boolean, onClick: () -> Unit, onName: () -> Unit, onNamed: (String) -> Unit) {
    val text = rememberTextMeasurer()
    val left = t.millisLeft(now)
    val lit = (t.fraction(now) * SEGMENTS).roundToInt()
    val label = t.label ?: "timer"
    val time = if (focused) r.hue else r.ink
    val words = if (focused) r.hue else r.sub
    val name = if (focused) BeamType.RowFocused else BeamType.Row
    val clock = countdownText(left)
    val room = 456f - 20f - text.measure(clock, BeamType.Mid).size.width - 20f
    val shown = room >= text.measure(label.take(3), name).size.width
    val tap by rememberUpdatedState { x: Float -> if (shown && x > 456f - room) onName() else onClick() }
    Box(Modifier.fillMaxWidth().height(118.px)) {
        Canvas(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { tap(it.x * 480f / size.width) } }) {
            pageScale {
                text(text, 20f, 70f, clock, BeamType.Mid, time)
                if (shown && !naming) text(text, 456f, 66f, fit(text, label, name, room), name, words, Anchor.End)
                for (i in 0 until SEGMENTS) rect(24f + i * 24f, 88f, 14f, 12f, if (i < lit) words else r.deep, 7f)
            }
        }
        if (naming) NameField(t.label.orEmpty(), r, room, Modifier.align(Alignment.TopEnd).padding(end = (24f - CARET).px), onNamed)
    }
}

/**
 * A running timer's name as words to type, in its place on the row: [BeamType.RowFocused] in
 * the hue, its baseline 66 down, "timer" in sub while it is empty; the keys up as it comes.
 * The field is as wide as its words and the caret, which hangs past the name's end at 456,
 * so the words stay where the name was; past [room] they scroll under the caret. Done or the keys
 * put away hand back what was typed ([onNamed]).
 */
@Composable
private fun NameField(name: String, r: Ramp, room: Float, modifier: Modifier, onNamed: (String) -> Unit) {
    val state = remember { TextFieldState(name) }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val options = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)
    val style = BeamType.RowFocused
    val measurer = rememberTextMeasurer()
    val wide = measurer.measure(state.text.toString().ifEmpty { "timer" }, style).size.width + CARET
    HueSelection(r) {
        OwnTextField(
            state,
            textStyle = style.copy(color = r.hue),
            cursorBrush = SolidColor(r.hue),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(TimersStore.LABEL_MAX),
            keyboardOptions = options,
            modifier = modifier.width(minOf(wide, room + CARET).coerceAtLeast(0f).px).paddingFromBaseline(top = 66.px).focusRequester(requester).onFocusChanged {
                if (it.isFocused && !focused) Cues.play(Sound.FieldFocus)
                if (focused && !it.isFocused) onNamed(state.text.toString())
                focused = it.isFocused
            },
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) Text("timer", style = style, color = r.sub, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** The pages pushed from Timers: a new timer. */
@Composable
internal fun TimersPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.TimerAdd -> TimerAddPage(onStart = { s, name -> core.timers.add(s, name); Cues.play(Sound.TimerStarted); nav.pop() }, onBack = nav::pop)
        else -> Unit
    }
}

/**
 * A new timer: hours, minutes and seconds as three columns, each its number in [BeamType.Huge]
 * centred at x 90, 240 and 390 on baseline 300 over its word in sub on 346; the selected one
 * (minutes first), number and word, in the hue. The wheel turns the selected column, wrapping; a
 * tap on a column selects it; a drag up or down on one selects it and turns it a step every 24 px.
 * The knob starts it, dim while it is zero. The wheel only turns values, as on the Metronome's
 * dial; the way back is the elastic pull, a finger pulled down anywhere off the columns, or the
 * edge drag. 2026-09-25: no "New timer" label; the header and the columns already say it.
 * 2026-10-14: its name, if it has one, in a [Field] above the columns, "name" in sub while empty;
 * while it is typed the keys are up and the knob is gone, done puts them away.
 */
@Composable
internal fun TimerAddPage(onStart: (Int, String) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Timers)
    val pull = rememberPull(onBack)
    val values = remember { mutableStateOf(intArrayOf(0, 0, 0)) }
    var column by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    // Hours 0 to 24, minutes and seconds 0 to 59.
    val ranges = intArrayOf(25, 60, 60)
    fun set(c: Int, v: Int) { values.value = values.value.copyOf().also { it[c] = v } }
    val v = values.value
    val total = v[0] * 3600 + v[1] * 60 + v[2]
    WheelTarget { delta -> set(column, wrap(v[column] + delta, ranges[column])); true }
    val latest = rememberUpdatedState(v)
    val text = rememberTextMeasurer()
    // Which column page x [x] falls in: they are centred 150 apart from 90, so they meet at 165 and 315.
    fun columnAt(x: Float) = ((x - 15f) / 150f).toInt().coerceIn(0, 2)
    PageTop("timers", r) {
        PulledCanvas(
            pull, r,
            canvas = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    // The canvas starts under the header: y is put back on the page's.
                    val u = size.width / 480f
                    val down = awaitFirstDown()
                    // The left edge is the way back.
                    if (down.position.x < 28.dp.toPx()) return@awaitEachGesture
                    val y0 = down.position.y / u + DRAWN_TOP
                    // Off the columns the moves are left alone, so the scroll under the canvas
                    // takes them into the pull; on them they are kept here.
                    if (y0 !in 190f..380f) return@awaitEachGesture
                    down.consume()
                    val c = columnAt(down.position.x / u)
                    var dragged = false
                    var travel = 0f
                    var lastY = down.position.y
                    drag(down.id) { ch ->
                        ch.consume()
                        if (!dragged && abs(ch.position.y - down.position.y) / u > 12f) { dragged = true; column = c }
                        if (dragged) {
                            travel -= (ch.position.y - lastY) / u
                            while (travel >= STEP || travel <= -STEP) {
                                val d = if (travel > 0) 1 else -1
                                travel -= d * STEP
                                set(c, wrap(latest.value[c] + d, ranges[c]))
                                Cues.play(Sound.WheelMoved)
                            }
                        }
                        lastY = ch.position.y
                    }
                    if (!dragged && c != column) { column = c; Cues.play(Sound.Commit) }
                }
            },
        ) {
            pageScale {
                translate(0f, -DRAWN_TOP) {
                    for (c in 0..2) {
                        val x = 90f + c * 150f
                        val on = c == column
                        val num = if (c == 0) v[c].toString() else "%02d".format(v[c])
                        text(text, x, 300f, num, BeamType.Huge, if (on) r.hue else r.ink, Anchor.Middle)
                        text(text, x, 346f, WORDS[c], BeamType.Label, if (on) r.hue else r.sub, Anchor.Middle)
                    }
                }
            }
        }
        Field(
            name, { name = it }, r, Modifier.padding(top = 14.px).onFocusChanged { typing = it.hasFocus }, placeholder = "name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        if (!typing) KnobKey(Glyphs.Play, r, onClick = { onStart(total, name) }, enabled = total > 0)
    }
}

/**
 * A timer ringing (2026-10-28), over everything: the whole screen in the hue, to be seen from
 * across a room. The croissant big at the top in its own white, acting [AlertActs.ringing] until it
 * goes, then 0:00 in [BeamType.Big], the timer's name in [BeamType.Second] and "tap to stop" at 60%
 * black 46 under it, all centred and black. Two timers at once take a name a line, 54 apart; three
 * or more, the first one's name and how many more, so the words stay on the screen. Any tap or tick
 * dismisses it, as does the side button (the activity). It slides in from the right and settles the
 * room under it ([onSettle]).
 */
@Composable
internal fun BeamRinging(ringing: List<Timer>, onDismiss: () -> Unit, onSettle: () -> Unit) {
    var shown by remember { mutableStateOf<List<Timer>>(emptyList()) }
    if (ringing.isNotEmpty()) shown = ringing
    AnimatedVisibility(
        ringing.isNotEmpty(),
        enter = slideInHorizontally(SLIDE) { it },
        exit = slideOutHorizontally(SLIDE) { it },
    ) {
        LaunchedEffect(Unit) { onSettle() }
        WheelTarget { onDismiss(); false }
        val r = rampOf(Feature.Timers)
        val black = r.ground
        val text = rememberTextMeasurer()
        // A rig of its own each time it rings, so the ears shoot up as it slides in.
        val rig = remember { IdleV3.rig().apply { perform(AlertActs.ringing) } }
        PlayCroissant(rig)
        Box(Modifier.fillMaxSize().background(r.hue).tappable(onDismiss)) {
            Canvas(Modifier.fillMaxSize()) {
                pageScale {
                    text(text, 240f, 446f, "0:00", BeamType.Big, black, Anchor.Middle)
                    val lines = ringLines(shown)
                    lines.forEachIndexed { i, l ->
                        text(text, 240f, 508f + i * 54f, fit(text, l, BeamType.Second, 432f), BeamType.Second, black, Anchor.Middle)
                    }
                    text(text, 240f, 554f + (lines.size - 1).coerceAtLeast(0) * 54f, "tap to stop", BeamType.Value, black.copy(alpha = 0.6f), Anchor.Middle)
                }
            }
            // Its own canvas on its own layer, so a frame of the loop records the croissant again and nothing else.
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                pageScale { croissant(rig.pose(), RING_CROISSANT_X, RING_CROISSANT_TOP, RING_CROISSANT_W, RING_CROISSANT_INK, r.ink, black) }
            }
        }
    }
}

/** The names the ringing screen shows: each timer's, two at most; three or more, the first one's and how many more. */
private fun ringLines(timers: List<Timer>): List<String> {
    val names = timers.map { it.label ?: "timer" }
    return if (names.size <= 2) names else listOf(names.first(), "and ${names.size - 1} more")
}

// The ringing croissant where Ask's listens: centred, its top 112, 260 wide, a 7 px line.
private const val RING_CROISSANT_X = 240f
private const val RING_CROISSANT_TOP = 112f
private const val RING_CROISSANT_W = 260f
private const val RING_CROISSANT_INK = 7f

private val WORDS = listOf("hours", "minutes", "seconds")

/** A timer's meter: 18 segments span x 24 to 446. */
private const val SEGMENTS = 18

/** Room after a name being typed for its caret, in page pixels, in the row's right margin. */
private const val CARET = 6f

/** Page pixels of drag that turn a column one step. */
private const val STEP = 24f

private val SLIDE = tween<IntOffset>(120, easing = LinearEasing)
