package com.bleural.croissant.ui.beam

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.TranscribeState
import com.bleural.croissant.TxPhase
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Recording
import com.bleural.croissant.data.lengthClock
import com.bleural.croissant.data.lengthWords
import com.bleural.croissant.data.paragraphs
import com.bleural.croissant.data.stampText
import com.bleural.croissant.send.Offer
import com.bleural.croissant.send.Share
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.LibraryLine
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.libraryLines
import com.bleural.croissant.ui.tappable
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Recorder's pushed pages: the tapes, one tape, renaming it. Each unpacks from [core] only
 * what it reads.
 */
@Composable
internal fun RecorderPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.Library -> {
            val items by core.recordings.items.collectAsStateWithLifecycle()
            val transcribing by core.transcribing.collectAsStateWithLifecycle()
            TapesPage(items, transcribing, onOpen = { nav.push(Screen.Recording(it.path)) }, onBack = nav::pop)
        }
        is Screen.Recording -> {
            val items by core.recordings.items.collectAsStateWithLifecycle()
            val transcribing by core.transcribing.collectAsStateWithLifecycle()
            TapePage(
                File(screen.path), items, transcribing,
                onTranscribe = core::transcribe,
                onSend = { nav.push(Screen.SendTape(it.path)) },
                onDelete = { core.deleteRecording(it); nav.pop(Sound.Destroy) },
                onRename = { nav.push(Screen.RenameRecording(it.path)) },
                onBack = nav::pop,
            )
        }
        is Screen.RenameRecording -> {
            val items by core.recordings.items.collectAsStateWithLifecycle()
            TapeRenamePage(File(screen.path), items, onSave = { core.renameRecording(File(screen.path), it); nav.pop(Sound.Commit) }, onBack = nav::pop)
        }
        // Keyed on the page, not the list: a rename or a new segment while the code is up
        // must not mint a new token under a phone that is already scanning the old one.
        is Screen.SendTape -> {
            val items by core.recordings.items.collectAsStateWithLifecycle()
            val ts by core.tailscale.state.collectAsStateWithLifecycle()
            val rec = items.firstOrNull { it.file.path == screen.path }
            if (rec != null) {
                val share = remember(screen) { Share(Offer.Tape(rec)) }
                SendPage(share, rec.title, rampOf(Feature.Recorder), core.tailscale, ts, onBack = nav::pop)
            } else LaunchedEffect(screen) { if (nav.current == screen) nav.pop() }
        }
        else -> Unit
    }
}

/**
 * The tapes: by day under their labels ("today", "mon 21 sept") with the day's count, newest first,
 * each by its name ("Tape 3" until it is given one) with its length as the value, the text mark
 * before it once transcribed, "sending" or "transcribing" in its place while one is on its way. The
 * header is the word alone: the counts are the labels'. The wheel steps them, the focused one in
 * the hue; a tap opens one.
 */
@Composable
internal fun TapesPage(items: List<Recording>, inFlight: TranscribeState?, onOpen: (File) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Recorder)
    val lines = remember(items) { libraryLines(items, LocalDate.now()) }
    val rowAt = remember(lines) { lines.indices.filter { lines[it] is LibraryLine.Row } }
    // Each line's place in its day's group, and how many the group holds.
    val place = remember(lines) {
        val at = IntArray(lines.size)
        val of = IntArray(lines.size)
        var day = 0
        var count = 0
        lines.forEachIndexed { i, l ->
            if (l is LibraryLine.Day) { day = i; count = l.count } else { at[i] = i - day - 1; of[i] = count }
        }
        at to of
    }
    val rows = rememberRows(
        rowAt.size, onBack, itemOf = { rowAt[it] },
        topOf = { rowAt[it].let { i -> if (i > 0 && lines[i - 1] is LibraryLine.Day) i - 1 else i } },
    )
    PageTop("recorder", r) {
        ShelfList(rows, r) {
            items(lines.size, key = { i -> lines[i].let { if (it is LibraryLine.Day) "d" + it.label else (it as LibraryLine.Row).rec.file.name } }) { i ->
                when (val line = lines[i]) {
                    is LibraryLine.Day -> DotLabel(line.label.lowercase(), r, line.count, top = if (i == 0) LABEL_FIRST else LABEL_ABOVE)
                    is LibraryLine.Row -> {
                        val rec = line.rec
                        val at = rowAt.indexOf(i)
                        val flight = inFlight?.takeIf { it.file == rec.file && it.error == null }
                        ShelfRow(
                            rec.title, r, focused = at == rows.focus,
                            onClick = { rows.focus = at; onOpen(rec.file) },
                            value = flight?.let { if (it.phase == TxPhase.Sending) "sending" else "transcribing" } ?: lengthClock(rec.durationMs),
                            mark = if (flight == null && rec.meta?.transcribed == true) Glyphs.Text else null,
                            edge = shelfEdge(place.first[i], place.second[i]),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(22.px)) }
        }
    }
}

private val TAPE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/**
 * One tape: its name (a tap renames it) with "today · 9:14 · 12 min" under it, then
 * the transcript by the minute: each paragraph's start in sub, its words in Body white.
 *
 * On its way to text, "sending" or "transcribing" in the hue under the name, and a meter of
 * the progress under that; failed, why in danger in the same place, and Transcribe lit again.
 * What came back before a failure or while it runs shows under it. Delete through the sheet;
 * Transcribe beside it while there is no transcript (dim while one is on its way), Send in its
 * place once there is one. The wheel scrolls; a tick up at the top goes back.
 */
@Composable
internal fun TapePage(
    file: File,
    items: List<Recording>,
    inFlight: TranscribeState?,
    onTranscribe: (File) -> Unit,
    onSend: (File) -> Unit,
    onDelete: (File) -> Unit,
    onRename: (File) -> Unit,
    onBack: () -> Unit,
) {
    val rec = items.firstOrNull { it.file == file }
    LaunchedEffect(file.path) { if (rec == null) onBack() }
    if (rec == null) return
    val r = rampOf(Feature.Recorder)
    val ctx = LocalContext.current
    val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm a", Locale.UK)
    val flight = inFlight?.takeIf { it.file == file }
    val busy = flight != null && flight.error == null
    val started = rec.startedAt
    val details = buildList {
        if (started != null) add(if (started.toLocalDate() == LocalDate.now()) "today" else started.toLocalDate().format(TAPE_DATE))
        if (started != null) add(started.format(clock))
        add(lengthWords(rec.durationMs))
    }.joinToString(" · ").lowercase()
    val paras = remember(rec.meta?.transcript) { rec.meta?.transcript?.let(::paragraphs) ?: emptyList() }
    val transcribed = rec.meta?.transcribed == true
    val (scroll, pull) = rememberWordsScroll(onBack)
    var asking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PageTop("recorder", r) {
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll).padding(bottom = 140.px)) {
                ItemTitle(rec.title, r, details, Modifier.endAtBaseline(), onClick = { onRename(file) })
                val f = flight
                if (f != null && f.error == null) {
                    TapeWords(if (f.phase == TxPhase.Sending) "sending" else "transcribing", BeamType.Label, r.hue, UNDER_TITLE)
                    Meter({ f.progress }, r, Modifier.padding(start = 24.px, end = 24.px, top = 20.px).endAtBaseline())
                }
                f?.error?.let { TapeWords(it, BeamType.Body, r.danger, UNDER_TITLE) }
                paras.forEachIndexed { i, p ->
                    // A minute's label 58 under the title's details or the state above it, 46
                    // under the paragraph before; its words 42 under it, 42 a line.
                    val gap = when {
                        i > 0 -> UNDER_PARAGRAPH
                        f != null && f.error == null -> UNDER_METER
                        else -> UNDER_TITLE
                    }
                    TapeWords(stampText(p.start), BeamType.Label, r.sub, gap)
                    TapeWords(p.text, BeamType.Body, r.ink, UNDER_LABEL)
                }
            }
            val trash: () -> Unit = { asking = true; Cues.play(Sound.SheetIn) }
            DangerKey(Glyphs.Trash, r, trash, slot = 1)
            if (transcribed && !busy) KnobKey(Glyphs.Send, r, { onSend(file) })
            else KnobKey(Glyphs.Text, r, { onTranscribe(file) }, enabled = !busy)
        }
        if (asking) Sheet(
            r, "delete",
            onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
            onAct = { asking = false; onDelete(file) },
            colour = r.danger,
        )
    }
}

/** A tape's page, baseline to baseline: the title's details to the first label or the state line, */
private const val UNDER_TITLE = 58f

/** the meter's foot to the first label, a label to its paragraph, a paragraph's last line to the next label. */
private const val UNDER_METER = 52f
private const val UNDER_LABEL = 42f
private const val UNDER_PARAGRAPH = 46f

/**
 * One block of a tape's words from x 24 to 456, wrapping, its first baseline [gap] px under the
 * last baseline above (every block here ends at its own last baseline, [endAtBaseline]).
 */
@Composable
private fun TapeWords(text: String, style: TextStyle, colour: Color, gap: Float) {
    Text(
        text, style = style, color = colour,
        modifier = Modifier.endAtBaseline().padding(start = 24.px, end = 24.px).paddingFromBaseline(top = gap.px),
    )
}

/**
 * Ends this block at its last baseline (or its foot when it has none), so the next one can set
 * its own first baseline from it. What hangs below the baseline still draws.
 */
private fun Modifier.endAtBaseline(): Modifier = layout { m, c ->
    val p = m.measure(c)
    val b = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
    layout(p.width, b) { p.place(0, 0) }
}

/**
 * Renaming a tape: its name in the field, "Tape 3" in sub while it is empty, and the check key
 * under it on the right, filled in the hue; the keyboard is the system's. Saved empty, it is its
 * number again. The wheel does nothing here.
 */
@Composable
internal fun TapeRenamePage(file: File, items: List<Recording>, onSave: (String) -> Unit, onBack: () -> Unit) {
    val rec = items.firstOrNull { it.file == file }
    LaunchedEffect(file.path) { if (rec == null) onBack() }
    if (rec == null) return
    val r = rampOf(Feature.Recorder)
    var name by remember { mutableStateOf(rec.meta?.name ?: "") }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    WheelTarget { false }
    PageTop("recorder", r) {
        Column(Modifier.fillMaxSize().padding(top = 14.px)) {
            Field(
                name, { name = it }, r, Modifier.focusRequester(requester), placeholder = "Tape ${rec.number}",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(name) }),
            )
            SaveKey(Glyphs.Check, r, Modifier.align(Alignment.End).padding(top = 24.px, end = 24.px)) { onSave(name) }
        }
    }
}

/** An act key in the flow of a page (the rename's save): 84 round, filled in the hue, its glyph 40 in the ground. */
@Composable
private fun SaveKey(glyph: ImageVector, r: Ramp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(84.px).clip(CircleShape).background(r.hue).tappable(onClick), contentAlignment = Alignment.Center) {
        Icon(glyph, null, Modifier.size(40.px), r.ground)
    }
}
