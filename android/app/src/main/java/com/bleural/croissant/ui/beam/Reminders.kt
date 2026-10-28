package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.RemindersStore
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.addedDay
import com.bleural.croissant.ui.addedLabel
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.reminderSections
import com.bleural.croissant.ui.tappable
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/*
 * Reminders in Beam: the open reminders under the day each was added, what is done folded under one
 * label, and one reminder in full, drawn as words on black, the reminders blue where it matters.
 */

/** One line of the list: a day's label, a reminder under it, or the label that folds what is done. */
private sealed interface Line {
    data class Day(val label: String, val count: Int, val first: Boolean) : Line
    data class Item(val r: Reminder, val edge: ShelfEdge) : Line
    data class Done(val count: Int, val open: Boolean, val first: Boolean) : Line
}

/**
 * Reminders: each day's open reminders under its lowercase date label and count, every row with its
 * to-do [Ring] (sub, the hue when focused); the focused row runs to three lines, the rest are one
 * line, cut. What is done folds under a "done" label with its count, which a tap opens: the done
 * ones follow as plain rows in sub. The key, filled blue, is the plus, a new reminder; a reminder
 * is ticked done on its own page (2026-10-14). The wheel steps rows; a tick up on the first steps
 * back; a tap opens the reminder. One just added, here or by voice, comes in focused: the newest is
 * the first row.
 */
@Composable
internal fun RemindersPage(items: List<Reminder>, onOpen: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Reminders)
    val tick by rememberMinuteTick()
    var showDone by remember { mutableStateOf(false) }
    val lines = remember(items, tick, showDone) {
        val sections = reminderSections(items, OffsetDateTime.now())
        val open = sections.filter { s -> s.rows.none { it.reminder.done } }
        val done = items.filter { it.done }.sortedByDescending { it.doneAt ?: "" }
        buildList {
            for (s in open) {
                add(Line.Day(s.label.lowercase(), s.rows.size, first = isEmpty()))
                s.rows.forEachIndexed { i, row -> add(Line.Item(row.reminder, shelfEdge(i, s.rows.size))) }
            }
            if (done.isNotEmpty()) {
                add(Line.Done(done.size, showDone, first = isEmpty()))
                if (showDone) done.forEachIndexed { i, d -> add(Line.Item(d, shelfEdge(i, done.size))) }
            }
        }
    }
    val rowAt = remember(lines) { lines.indices.filter { lines[it] is Line.Item } }
    // A row's group label (a day, or the done label) stays in view above its first row.
    val rows = rememberRows(
        rowAt.size, onBack,
        itemOf = { rowAt[it] },
        topOf = { val i = rowAt[it]; if (i > 0 && lines[i - 1] !is Line.Item) i - 1 else i },
    )
    val known = remember { items.mapTo(HashSet()) { it.id } }
    LaunchedEffect(items) {
        val fresh = items.any { it.id !in known && !it.done }
        known.clear(); items.mapTo(known) { it.id }
        if (fresh) { rows.focus = 0; rows.list.scrollToItem(0) }
    }

    PageTop("Reminders", r) {
        ShelfList(rows, r) {
            items(lines.size, key = { i -> lines[i].let { when (it) { is Line.Item -> it.r.id; is Line.Day -> "day ${it.label}"; is Line.Done -> "done" } } }) { i ->
                when (val l = lines[i]) {
                    is Line.Day -> DotLabel(l.label, r, count = l.count, top = if (l.first) LABEL_FIRST else LABEL_ABOVE)
                    is Line.Done -> DoneLine(l.count, l.open, r, top = if (l.first) LABEL_FIRST else LABEL_ABOVE, onClick = { showDone = !showDone; Cues.play(Sound.Commit) })
                    is Line.Item -> {
                        val at = rowAt.indexOf(i)
                        // A done one is its words alone, in sub: the label above says what it is.
                        ShelfRow(
                            l.r.title, r, focused = at == rows.focus,
                            onClick = { rows.focus = at; onOpen(l.r.id) },
                            ring = !l.r.done, past = l.r.done, wrap = true, edge = l.edge,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(112.px)) }
        }
        KnobKey(Glyphs.Plus, r, onClick = onAdd)
    }
}

/**
 * The label that folds what is done: "done" as a [DotLabel]'s word (26, sub, x 24) with a
 * small chevron after it, right while folded and down once open, and the count ending at 456.
 * The whole line takes the tap. [top] px to its baseline, [LABEL_GAP] under it, as a label's.
 */
@Composable
private fun DoneLine(count: Int, open: Boolean, r: Ramp, top: Float, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().tappable(onClick)) {
        Row(Modifier.padding(start = 24.px), verticalAlignment = Alignment.Top) {
            Text("done", style = BeamType.Label, color = r.sub, maxLines = 1,
                modifier = Modifier.paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
            Spacer(Modifier.width(12.px))
            // 16 across, its middle 9 over the baseline: on the word's x-height.
            Canvas(Modifier.padding(top = (top - 17f).px).size(16.px)) {
                val u = size.width / 16f
                val p = Path().apply {
                    if (open) { moveTo(2f * u, 5f * u); lineTo(8f * u, 11f * u); lineTo(14f * u, 5f * u) }
                    else { moveTo(5f * u, 2f * u); lineTo(11f * u, 8f * u); lineTo(5f * u, 14f * u) }
                }
                drawPath(p, r.sub, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(count.toString(), style = BeamType.Label, color = r.sub, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
    }
}

/**
 * One reminder in full: the whole title in [BeamType.Second] (48, 54 a line) wrapping from x 22,
 * its first baseline 52 down (152 on the page); the day it was added ("thu 17 sept") in
 * [BeamType.Value] sub 44 under its last line. Done, it is shown quietly: the title in sub and
 * "done" under it. The filled key ticks it done, or back once it is done (the arrow back). The
 * wheel scrolls a long title, a tick up at the top steps back. Closes if the reminder goes away
 * under it (deleted by voice). 2026-10-14: a tap on the title types it where it is, the keys up and
 * the key gone; the typing ends with done, the keys put away or the page left, and keeps what was
 * typed. Left empty, the sheet asks to delete the reminder, and cancel brings its title back.
 * 2026-10-28: the X left of the key, in the danger red, deletes it through the same sheet; both
 * keys go while typing.
 */
@Composable
internal fun ReminderPage(
    items: List<Reminder>,
    id: String,
    onToggle: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val item = items.firstOrNull { it.id == id }
    LaunchedEffect(item == null) { if (item == null) onBack() }
    if (item == null) return
    val r = rampOf(Feature.Reminders)
    val tick by rememberMinuteTick()
    val day = remember(item, tick) { if (item.done) "done" else OffsetDateTime.now().let { addedLabel(addedDay(item, it), it.toLocalDate()).lowercase() } }
    val (scroll, pull) = rememberWordsScroll(onBack)
    val title = remember { TextFieldState(item.title) }
    var typing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // A title changed by voice shows, unless it is being typed.
    LaunchedEffect(item.title) { if (!typing) title.setTextAndPlaceCursorAtEnd(item.title) }
    fun keep() {
        val t = title.text.toString()
        if (t.isBlank()) { asking = true; Cues.play(Sound.SheetIn) }
        else if (t.trim() != item.title) onRename(item.id, t)
    }
    // Left while typing: what was typed is kept.
    val leaving by rememberUpdatedState({ if (typing && title.text.isNotBlank()) keep() })
    DisposableEffect(Unit) { onDispose { leaving() } }
    PageTop("Reminders", r) {
        Baselines(
            listOf(TITLE_FIRST, DETAILS_UNDER),
            Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll),
            bottom = 112f,
        ) {
            TitleField(title, r, if (item.done && !typing) r.sub else r.ink, RemindersStore.TITLE_MAX, onFocus = { f -> if (typing && !f) keep(); typing = f })
            Text(day, style = BeamType.Value, color = r.sub, modifier = Modifier.padding(start = 24.px, end = 22.px))
        }
        if (!typing) {
            KnobKey(if (item.done) Glyphs.Retake else Glyphs.Check, r, onClick = {
                onToggle(item.id)
                Cues.play(if (item.done) Sound.ReminderUndone else Sound.ReminderDone)
            })
            DangerKey(Glyphs.Close, r, onClick = { asking = true; Cues.play(Sound.SheetIn) }, slot = 1)
        }
        if (asking) Sheet(
            r, "delete", colour = r.danger,
            onCancel = { asking = false; title.setTextAndPlaceCursorAtEnd(item.title); Cues.play(Sound.SheetDismiss) },
            onAct = { asking = false; onDelete(item.id); Cues.play(Sound.Destroy) },
        )
    }
}

/**
 * A new reminder (Reminders' plus): its title typed where a reminder's own page shows it, "a
 * new reminder" in sub while it is empty, the keys up from the start. Done adds it and goes
 * back to the list, where it comes in focused; done with nothing typed only goes back. With
 * the keys put away, the filled key adds it. The way back drops what was typed.
 */
@Composable
internal fun ReminderAddPage(onAdd: (String) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Reminders)
    val (scroll, pull) = rememberWordsScroll(onBack)
    val title = remember { TextFieldState() }
    var typing by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    fun add() = if (title.text.isBlank()) onBack() else onAdd(title.text.toString())
    PageTop("Reminders", r) {
        Baselines(
            listOf(TITLE_FIRST),
            Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll),
            bottom = 112f,
        ) {
            TitleField(title, r, r.ink, RemindersStore.TITLE_MAX, Modifier.focusRequester(requester), placeholder = "a new reminder", onFocus = { typing = it }, onDone = ::add)
        }
        if (!typing) KnobKey(Glyphs.Check, r, onClick = ::add, enabled = title.text.isNotBlank())
    }
}

/**
 * A reminder's or an event's title as words to type: [BeamType.Second] in [colour] from x 22
 * to 458, wrapping, with a hue caret and [placeholder] in sub while it is empty; typed on
 * Beam's keys, whose action key says done. [max] characters at most, as the store keeps.
 * Done does [onDone], or lets go of the field when there is none.
 */
@Composable
internal fun TitleField(
    state: TextFieldState,
    r: Ramp,
    colour: Color,
    max: Int,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onFocus: (Boolean) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val options = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)
    val actions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default
    var focused by remember { mutableStateOf(false) }
    HueSelection(r) {
        OwnTextField(
            state,
            textStyle = BeamType.Second.copy(color = colour),
            cursorBrush = SolidColor(r.hue),
            inputTransformation = InputTransformation.maxLength(max),
            keyboardOptions = options,
            keyboardActions = actions,
            modifier = modifier.fillMaxWidth().padding(start = 22.px, end = 22.px).onFocusChanged {
                if (it.isFocused && !focused) Cues.play(Sound.FieldFocus)
                if (it.isFocused != focused) { focused = it.isFocused; onFocus(it.isFocused) }
            },
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty() && placeholder != null) Text(placeholder, style = BeamType.Second, color = r.sub)
                    inner()
                }
            },
        )
    }
}

/** An item's title's first baseline under the content's top: 52 (152 on the page). */
internal const val TITLE_FIRST = 52f

/** Its details' baseline under the title's last: 44. */
internal const val DETAILS_UNDER = 44f

/**
 * A column of text placed by baselines, for a page of words drawn baseline by baseline (a
 * reminder, an event): child i's first baseline is [gaps] i px under child i - 1's last
 * baseline (the first under the top), and the column ends [bottom] px under the last
 * baseline. Children take the full width and set their own side padding.
 */
@Composable
internal fun Baselines(gaps: List<Float>, modifier: Modifier = Modifier, bottom: Float = 22f, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { ms, c ->
        val u = 1.px.toPx()
        val ps = ms.map { it.measure(Constraints(maxWidth = c.maxWidth)) }
        var last = 0
        val ys = ps.mapIndexed { i, p ->
            val first = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
            val lastB = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            val y = last + (gaps.getOrElse(i) { 0f } * u).roundToInt() - first
            last = y + lastB
            y
        }
        val h = if (ps.isEmpty()) 0 else last + (bottom * u).roundToInt()
        layout(c.maxWidth, maxOf(h, c.minHeight)) { ps.forEachIndexed { i, p -> p.place(0, ys[i]) } }
    }
}

/**
 * Every page pushed over Reminders: a reminder in full and a new one (`Screen.feature` gives
 * Reminders only [Screen.Reminder] and [Screen.ReminderAdd]).
 */
@Composable
internal fun RemindersPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        is Screen.Reminder -> {
            val items by core.reminders.items.collectAsStateWithLifecycle()
            ReminderPage(
                items, screen.id,
                onToggle = core.reminders::toggle,
                onRename = { id, title -> runCatching { core.reminders.update(id, title = title) } },
                onDelete = { runCatching { core.reminders.delete(it) } },
                onBack = nav::pop,
            )
        }
        Screen.ReminderAdd -> ReminderAddPage(onAdd = { runCatching { core.reminders.add(it) }; nav.pop() }, onBack = nav::pop)
        else -> Unit
    }
}
