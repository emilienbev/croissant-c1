package com.bleural.croissant.ui.beam

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Event
import com.bleural.croissant.data.EventsStore
import com.bleural.croissant.ui.AgendaItem
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.agenda
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.whenText
import java.time.Duration
import java.time.OffsetDateTime

/*
 * Calendar in Beam: the agenda, each day with something under its lowercase label, and one event in
 * full. Drawn as words on black, the calendar violet where it matters. An event's title is typed on
 * its page and the event deleted there; everything else changes by voice.
 */

/**
 * What a row shows at the right instead of a time: "all day", or the last day of a stay
 * ("until 18:00"); null for a timed row.
 */
private val AgendaItem.Row.span: String?
    get() = if (event.allDay || time == "All day" || time.startsWith("Until")) time.lowercase() else null

/** Whether this row has already ended: a timed event today whose end has gone by. */
private fun AgendaItem.Row.past(now: OffsetDateTime): Boolean {
    if (event.allDay || date != now.toLocalDate()) return false
    val end = runCatching { OffsetDateTime.parse(event.end) }.getOrNull() ?: return false
    return end.isBefore(now)
}

/**
 * Calendar: only the days that have something, each under a [DotLabel]
 * ("today", "tomorrow", "wed 23 sept"), its events as rows with the time in a column in sub
 * and the title at 150; an all-day event (and a stay's last day) has no time column, its
 * title at 24 and "all day" as the row's value at the right. What has already ended today is
 * in sub. The focused row's words are violet. The list starts on the first event still to
 * come; the wheel steps rows, a tick up on the first steps back, a tap opens the event.
 */
@Composable
internal fun CalendarPage(events: List<Event>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Calendar)
    val ctx = LocalContext.current
    val tick by rememberMinuteTick()
    val now = remember(tick) { OffsetDateTime.now() }
    val items = remember(events, now) { agenda(events, now, DateFormat.is24HourFormat(ctx)) }
    val rowAt = remember(items) { items.indices.filter { items[it] is AgendaItem.Row } }
    // Where each row falls in its day's group (the kit's signature; nothing is drawn round a group).
    val edges = remember(items) {
        val counts = IntArray(items.size) { i -> if (items[i] is AgendaItem.Day) items.drop(i + 1).takeWhile { it is AgendaItem.Row }.size else 0 }
        val out = arrayOfNulls<ShelfEdge>(items.size)
        var day = 0
        items.forEachIndexed { i, it -> if (it is AgendaItem.Day) day = i else out[i] = shelfEdge(i - day - 1, counts[day]) }
        out
    }
    val rows = rememberRows(
        rowAt.size, onBack,
        itemOf = { rowAt[it] },
        topOf = { val i = rowAt[it]; if (i > 0 && items[i - 1] is AgendaItem.Day) i - 1 else i },
    )
    // The first row still to come is where the list starts: what has passed today is above it.
    remember(rows) { rows.focus = rowAt.indexOfFirst { !(items[it] as AgendaItem.Row).past(now) }.coerceAtLeast(0); true }

    PageTop("Calendar", r) {
        ShelfList(rows, r) {
            items(items.size, key = { i -> items[i].let { if (it is AgendaItem.Day) "d${it.date}" else (it as AgendaItem.Row).let { x -> "${x.event.id}@${x.date}" } } }) { i ->
                when (val it = items[i]) {
                    is AgendaItem.Day -> DotLabel(it.label.lowercase(), r, top = if (i == 0) LABEL_FIRST else LABEL_ABOVE)
                    is AgendaItem.Row -> {
                        val at = rowAt.indexOf(i)
                        val span = it.span
                        ShelfRow(
                            it.event.title, r, focused = at == rows.focus,
                            onClick = { rows.focus = at; onOpen(it.event.id) },
                            time = if (span == null) it.time else null, value = span,
                            past = it.past(now), edge = edges[i] ?: ShelfEdge.Only,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(22.px)) }
        }
    }
}

/**
 * How long until an event starts, when it is today and still ahead: "in 38 min", "in 2 h",
 * "in 1 h 20 min", rounded up to the minute; null otherwise (all day, begun, another day).
 */
private fun until(e: Event, now: OffsetDateTime): String? {
    if (e.allDay) return null
    val start = runCatching { OffsetDateTime.parse(e.start).withOffsetSameInstant(now.offset) }.getOrNull() ?: return null
    if (!start.isAfter(now) || start.toLocalDate() != now.toLocalDate()) return null
    val min = (Duration.between(now, start).seconds + 59) / 60
    val h = min / 60
    val m = min % 60
    return when {
        h == 0L -> "in $m min"
        m == 0L -> "in $h h"
        else -> "in $h h $m min"
    }
}

/**
 * One event in full: the title in [BeamType.Second] wrapping from x 22 (baseline 152), when it
 * happens in [BeamType.Value] sub under it ("today · 14:00 to 14:45"), then, when it is today and
 * still ahead, how long until it as a row's words in violet 66 under that ("in 38 min"). Where it
 * is and its notes follow when it has them: each a label in sub 60 under what is above, the words
 * in [BeamType.Body] ink 42 under the label. The wheel scrolls, a tick up at the top steps back.
 * Closes if the event goes away under it. 2026-10-28: a tap on the title types it where it is, as a
 * reminder's (the keys up, the X gone; done, the keys put away or the page left keeps it; left
 * empty, the sheet asks to delete the event). The X at the bottom right, in the danger red, deletes
 * it through the sheet. Only the title is typed: when it happens changes by voice.
 */
@Composable
internal fun EventPage(
    events: List<Event>,
    id: String,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val e = events.firstOrNull { it.id == id }
    LaunchedEffect(e == null) { if (e == null) onBack() }
    if (e == null) return
    val r = rampOf(Feature.Calendar)
    val ctx = LocalContext.current
    val tick by rememberMinuteTick()
    val now = remember(tick) { OffsetDateTime.now() }
    // whenText's "Today, 14:00 to 15:00" read as "today · 14:00 to 15:00".
    val whenLine = remember(e, now) { whenText(e, now, DateFormat.is24HourFormat(ctx)).lowercase().replaceFirst(", ", " · ") }
    val soon = remember(e, now) { until(e, now) }
    val (scroll, pull) = rememberWordsScroll(onBack)
    val title = remember { TextFieldState(e.title) }
    var typing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // A title changed by voice shows, unless it is being typed.
    LaunchedEffect(e.title) { if (!typing) title.setTextAndPlaceCursorAtEnd(e.title) }
    fun keep() {
        val t = title.text.toString()
        if (t.isBlank()) { asking = true; Cues.play(Sound.SheetIn) }
        else if (t.trim() != e.title) onRename(e.id, t)
    }
    // Left while typing: what was typed is kept.
    val leaving by rememberUpdatedState({ if (typing && title.text.isNotBlank()) keep() })
    DisposableEffect(Unit) { onDispose { leaving() } }
    val gaps = buildList {
        add(TITLE_FIRST); add(DETAILS_UNDER)
        if (soon != null) add(66f)
        if (e.location != null) { add(60f); add(42f) }
        if (e.notes != null) { add(60f); add(42f) }
    }
    PageTop("Calendar", r) {
        Baselines(gaps, Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll), bottom = 112f) {
            TitleField(title, r, r.ink, EventsStore.TITLE_MAX, onFocus = { f -> if (typing && !f) keep(); typing = f })
            Text(whenLine, style = BeamType.Value, color = r.sub, modifier = Modifier.padding(start = 24.px, end = 22.px))
            if (soon != null) Text(soon, style = BeamType.Row, color = r.hue, maxLines = 1, modifier = Modifier.padding(start = 24.px, end = 22.px))
            e.location?.let {
                Text("where", style = BeamType.Label, color = r.sub, modifier = Modifier.padding(start = 24.px, end = 24.px))
                Text(it, style = BeamType.Body, color = r.ink, modifier = Modifier.padding(start = 24.px, end = 24.px))
            }
            e.notes?.let {
                Text("notes", style = BeamType.Label, color = r.sub, modifier = Modifier.padding(start = 24.px, end = 24.px))
                Text(it, style = BeamType.Body, color = r.ink, modifier = Modifier.padding(start = 24.px, end = 24.px))
            }
        }
        if (!typing) DangerKey(Glyphs.Close, r, onClick = { asking = true; Cues.play(Sound.SheetIn) })
        if (asking) Sheet(
            r, "delete", colour = r.danger,
            onCancel = { asking = false; title.setTextAndPlaceCursorAtEnd(e.title); Cues.play(Sound.SheetDismiss) },
            onAct = { asking = false; onDelete(e.id); Cues.play(Sound.Destroy) },
        )
    }
}

/**
 * Every page pushed over the Calendar: an event in full (`Screen.feature` gives the Calendar
 * only [Screen.Event]).
 */
@Composable
internal fun CalendarPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        is Screen.Event -> {
            val items by core.events.items.collectAsStateWithLifecycle()
            EventPage(
                items, screen.id,
                onRename = { id, title -> runCatching { core.events.update(id, title = title) } },
                onDelete = { runCatching { core.events.delete(it) } },
                onBack = nav::pop,
            )
        }
        else -> Unit
    }
}
