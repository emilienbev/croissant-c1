package com.bleural.croissant.ui.beam

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Conversation
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.historyDays
import com.bleural.croissant.ui.tappable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History (picked 2026-10-06): Ask's conversations by the day they were last asked in, each day a
 * lowercase label with its count, each conversation a row with its first question cut to fit and
 * the time on the right, a camera mark when a photo went with it. The wheel steps the rows; a tap
 * opens one on Ask, where the next hold continues it.
 *
 * Press and hold picks (as on Photos): each row gets the to-do ring, filled in the hue with a tick
 * when picked, and each day's label a ring at its right end that picks or drops the day whole; how
 * many are picked takes the first line, and the way out and Delete are keys. A step back empties
 * the selection first.
 */
@Composable
internal fun HistoryPage(items: List<Conversation>, onOpen: (Conversation) -> Unit, onDelete: (Set<String>) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Ask)
    val ctx = LocalContext.current
    val clock = remember { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm") }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(items) { historyDays(items, LocalDate.now()) }
    val flat = remember(days) { days.flatMap { it.items } }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    LaunchedEffect(flat) { picked = picked.intersect(flat.map { it.id }.toSet()) }
    var asking by remember { mutableStateOf<Set<String>?>(null) }
    fun cancel() { picked = emptySet(); Cues.play(Sound.ScreenOut) }
    // Lazy item 0 is the picked line (or nothing); then each day's label before its rows.
    val itemOf = remember(days) {
        val at = IntArray(flat.size)
        var item = 1; var k = 0
        for (d in days) { item++; for (c in d.items) at[k++] = item++ }
        at
    }
    val labelOf = remember(days) {
        val at = IntArray(flat.size)
        var item = 1; var k = 0
        for (d in days) { val label = item++; for (c in d.items) { at[k++] = label; item++ } }
        at
    }
    // A day's first row keeps its label in view with it.
    val rows = rememberRows(flat.size, onBack = { if (picked.isNotEmpty()) cancel() else onBack() },
        itemOf = { itemOf.getOrElse(it) { 0 } },
        topOf = { i -> if (i == 0 || labelOf.getOrElse(i) { 0 } != labelOf.getOrElse(i - 1) { 0 }) labelOf.getOrElse(i) { 0 } else itemOf.getOrElse(i) { 0 } })

    fun toggle(ids: Collection<String>, on: Boolean) {
        picked = if (on) picked + ids else picked - ids.toSet()
        Cues.play(if (on) Sound.SwitchOn else Sound.SwitchOff)
    }

    Box(Modifier.fillMaxSize()) {
        PageTop("history", r) {
            ShelfList(rows, r) {
                item(key = "lead") { if (selecting) PickedLine(picked.size, r) }
                var k = 0
                for ((di, day) in days.withIndex()) {
                    val ids = day.items.map { it.id }
                    item(key = "d" + day.label) {
                        val whole = selecting && ids.all { it in picked }
                        DayHead(
                            day.label, r, day.items.size,
                            top = if (di == 0 && !selecting) LABEL_FIRST else LABEL_ABOVE,
                            selecting = selecting, whole = whole,
                            onToggle = { toggle(ids, !whole) },
                        )
                    }
                    for (c in day.items) {
                        val at = k++
                        item(key = c.id) {
                            val on = c.id in picked
                            ShelfRow(
                                c.title.ifBlank { "…" }, r,
                                focused = at == rows.focus,
                                ring = selecting, picked = on,
                                value = Instant.ofEpochMilli(c.last).atZone(zone).format(clock),
                                mark = if (c.turns.any { it.photo != null }) Glyphs.Camera else null,
                                onClick = {
                                    rows.focus = at
                                    if (!selecting) onOpen(c) else toggle(listOf(c.id), !on)
                                },
                                onLongClick = { rows.focus = at; picked = picked + c.id; Cues.play(Sound.SwitchOn) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(if (selecting) UNDER_KEYS.px else 22.px)) }
            }
            if (selecting) {
                KeyShade(r)
                DangerKey(Glyphs.Trash, r, onClick = { asking = picked; Cues.play(Sound.SheetIn) })
                QuietKeyAt(Glyphs.Close, r, cx = 82f, cy = 574f, onClick = { cancel() })
            }
        }
        asking?.let { going ->
            Sheet(
                r, "delete ${going.size}", colour = r.danger,
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = null; picked = emptySet(); onDelete(going); Cues.play(Sound.Destroy) },
            )
        }
    }
}

/**
 * A day's name over its rows: the word lowercase in `Label` sub at x 24, the baseline [top] under
 * what is above and the rows [LABEL_GAP] under it. Its count ends at 456; while picking, the count
 * gives way to a ring there that picks or drops the day whole, filled in the hue with the word when
 * the whole day is picked.
 */
@Composable
private fun DayHead(text: String, r: Ramp, count: Int, top: Float, selecting: Boolean, whole: Boolean, onToggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().then(if (selecting) Modifier.tappable(onToggle) else Modifier)) {
        Text(text.lowercase(), style = BeamType.Label, color = if (whole) r.hue else r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.px, end = 90.px).paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
        if (!selecting) Text(count.toString(), style = BeamType.Label, color = r.sub, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
        else {
            // Centred on the label's middle (its baseline less 9), its right edge at 456 as the count's.
            val ring = Modifier.align(Alignment.TopEnd).padding(end = 22.px, top = (top - 9f - 16f).px)
            if (whole) PickedRing(r, ring) else Ring(r, ring)
        }
    }
}
