package com.bleural.croissant.ui.beam

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.backup.BackupPhase
import com.bleural.croissant.backup.BackupProblem
import com.bleural.croissant.backup.BackupUi
import com.bleural.croissant.backup.LastBackup
import com.bleural.croissant.backup.ListedBackup
import com.bleural.croissant.backup.RestoreOutcome
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Settings > Device > backup (backup/Backups.kt). Back up now and restore come first and never move,
 * so the wheel and a finger find them where they were whatever the page says under them: the last
 * backup, the sending, the result or a failure, from a label's place 40 under the rows. Restore
 * lists the server's backups, this C1's then another C1's, and asks first on a card (Agents'
 * surface, two pills), since a restore replaces everything on this C1 and restarts it.
 */

/** What the block under the rows says, worked out once from the state; drawn by [BackupBlock]. */
private data class Block(
    /** Body from the first line's place, 42 a line, each in its colour; one line when a file or a meter follows. */
    val lines: List<Pair<String, Color>> = emptyList(),
    /** In the first line's place instead, a group's name ("last"). */
    val label: String? = null,
    /** A backup's file at the row's size, and under it its folder and machine when they are known. */
    val file: String? = null,
    val where: List<String> = emptyList(),
    val meter: Float? = null,
    /** Under the meter: how much is across, or what comes next. */
    val under: String? = null,
    val key: BlockKey? = null,
)

/** The one key a frame has: the quiet close stops what runs; the check puts a restore's outcome away. */
private enum class BlockKey { Cancel, PutAway }

/**
 * Each frame: what runs first (with the quiet close while it can stop), then a failure, then
 * "backed up", then what the last start made of a restore (until its check puts it away), then the
 * page at rest. [server] names the machine while the list is asked for.
 */
private fun blockOf(u: BackupUi, server: String, r: Ramp): Block {
    val problem = u.problem
    val outcome = u.outcome
    val last = u.last
    return when {
        u.phase == BackupPhase.Making -> Block(listOf("sending to ${u.to}" to r.ink), meter = 0f, key = BlockKey.Cancel)
        u.phase == BackupPhase.Sending ->
            Block(listOf("sending to ${u.to}" to r.ink), meter = levelOf(u.sent, u.total), under = acrossOf(u.sent, u.total), key = BlockKey.Cancel)
        u.phase == BackupPhase.Listing -> Block(listOf("asking $server" to r.ink), key = BlockKey.Cancel)
        u.phase == BackupPhase.Fetching -> {
            val b = checkNotNull(u.restoring) { "fetching with no backup to restore: $u" }
            Block(listOf(b.name to r.ink), meter = levelOf(u.fetched, b.size), under = acrossOf(u.fetched, b.size), key = BlockKey.Cancel)
        }
        u.phase == BackupPhase.Restarting -> {
            val b = checkNotNull(u.restoring) { "restarting with no backup to restore: $u" }
            Block(listOf(b.name to r.ink), meter = 1f, under = "restarting")
        }
        problem != null -> {
            val (first, detail) = said(problem)
            Block(listOf(first to r.danger) + listOfNotNull(detail?.let { it to r.sub }))
        }
        u.phase == BackupPhase.Saved -> {
            val kept = checkNotNull(last) { "saved with no last backup: $u" }
            Block(listOf("backed up" to r.hue), file = kept.name, where = whereOf(kept))
        }
        outcome != null -> outcomeBlock(outcome, r)
        last != null -> Block(label = "last", file = last.name, where = whereOf(last))
        else -> Block(listOf("no backup yet" to r.sub))
    }
}

/** "restored" in the hue with the file under it, or "not restored" in danger with the error under it. */
private fun outcomeBlock(o: RestoreOutcome, r: Ramp): Block =
    if (o.ok) Block(listOf("restored" to r.hue), file = o.name, key = BlockKey.PutAway)
    else Block(listOf("not restored" to r.danger) + listOfNotNull(o.error?.let { it to r.sub }), key = BlockKey.PutAway)

private fun whereOf(b: LastBackup): List<String> = listOf("in ${b.folder}", "on ${b.machine}")

/** A problem as the page says it: its first line, in danger, and the detail under it in sub. */
private fun said(p: BackupProblem): Pair<String, String?> = when (p) {
    BackupProblem.NoServer -> "no server yet" to null
    is BackupProblem.Unreachable -> "can't reach ${p.machine}" to p.address
    is BackupProblem.TooOld -> "${p.machine} can't take backups" to "update croissant-server"
    is BackupProblem.NeedsCode -> "${p.machine} wants its pairing code" to "scan it again in settings > server"
    is BackupProblem.Dropped -> "the connection dropped" to p.why
    is BackupProblem.Damaged -> "it arrived damaged" to "${p.got} of ${p.expected} bytes"
    is BackupProblem.DiskFull -> "${p.machine}'s disk is full" to p.detail
    is BackupProblem.ServerSaid -> "${p.machine} turned it away" to p.message
    is BackupProblem.Local -> "it failed on this c1" to p.why
    is BackupProblem.NoSerial -> "can't read this c1's serial" to p.why
    is BackupProblem.NoRoom -> "not enough room on this c1" to "needs ${mbOf(p.needed)} mb, ${mbOf(p.free)} mb free"
    is BackupProblem.Newer -> "needs ${p.version} or newer" to "this c1 has ${p.app}"
    is BackupProblem.Refused -> "can't restore it" to p.why
}

/** Megabytes to a tenth, as the page says them: "4.1". */
private fun mbOf(bytes: Long): String = String.format(Locale.UK, "%.1f", bytes / 1_000_000.0)

/** How much is across: "4.1 of 9.6 mb". */
private fun acrossOf(done: Long, total: Long): String = "${mbOf(done)} of ${mbOf(total)} mb"

private fun levelOf(done: Long, total: Long): Float = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

// The block's grid, from the rows' foot (page y 214): its first line on a label's place 40 under
// (254), a file 88 under (302) with its folder 38 and its machine 74 under that; the meter 24 under
// the first line, and how much is across 48 under the meter.
private const val SAY = LABEL_ABOVE
private const val FILE = 88f
private const val FOLDER = FILE + 38f
private const val WHERE_PITCH = 36f
private const val METER_TOP = SAY + 24f
private const val UNDER = METER_TOP + 36f + 48f
/** Clear of the key, so the last words can come up above it. */
private const val FOOT = 140f

/** The rows while something runs: every part in dim, the kit's colour for what cannot act. */
private val DEAD: Ramp by lazy { Ramp(hue = DIM, ground = GROUND, shelf = GROUND, deep = FAINT, fill = DIM, sub = DIM, ink = DIM, knob = DIM) }

/**
 * Settings > Device > backup: back up now (the wheel on it as the page opens) and restore, glyph
 * rows from y 70, then the block. While something runs the rows are dead and the quiet close
 * stops it. Restore asks the server first and opens its list once it answers; a failure stays here.
 */
@Composable
internal fun BackupPage(core: Core, nav: NavState) {
    val u by core.backups.ui.collectAsStateWithLifecycle()
    val r = rampOf(Feature.Settings)
    val live = !u.busy
    // Dead rows give the wheel nothing to walk; up still steps back.
    val rows = rememberRows(if (live) 2 else 0, nav::pop, itemOf = { it + 1 })
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<Job?>(null) }
    // "backed up" and a failure answer this visit: leaving puts them away.
    DisposableEffect(Unit) { onDispose { core.backups.dismiss() } }
    val block = blockOf(u, core.server.label, r)
    val ramp = if (live) r else DEAD
    PageTop("backup", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(4.px)) }
            item {
                ShelfRow("back up now", ramp, focused = live && rows.focus == 0, glyph = Glyphs.Backup, onClick = if (!live) null else ({
                    rows.focus = 0
                    core.backups.backUp()
                    if (core.backups.ui.value.busy) Cues.play(Sound.Commit)
                }))
            }
            item {
                ShelfRow("restore", ramp, focused = live && rows.focus == 1, glyph = Glyphs.Restore, chevron = true, onClick = if (!live) null else ({
                    rows.focus = 1
                    listing = openList(core, scope, nav)
                }))
            }
            item { BackupBlock(block, r) }
        }
        when (block.key) {
            BlockKey.Cancel -> QuietKey(Glyphs.Close, r, onClick = {
                listing?.cancel()
                core.backups.cancel()
                Cues.play(Sound.SwitchOff)
            })
            BlockKey.PutAway -> QuietKey(Glyphs.Check, r, onClick = { core.backups.dismissOutcome() })
            null -> Unit
        }
    }
}

/**
 * Asks the server for its backups and opens the list once they are in; a failure stays in the
 * block. Null when no list started: refused at once (the block says why), or the last one
 * cancelled is still finishing.
 */
private fun openList(core: Core, scope: CoroutineScope, nav: NavState): Job? {
    core.backups.list()
    if (core.backups.ui.value.phase != BackupPhase.Listing) return null
    return scope.launch {
        val done = core.backups.ui.first { it.phase != BackupPhase.Listing }
        if (done.problem == null && done.list != null) nav.push(Screen.Restore)
    }
}

/** The block, every part at its place on the grid from the rows' foot. */
@Composable
private fun BackupBlock(b: Block, r: Ramp) {
    Box(Modifier.fillMaxWidth().padding(bottom = FOOT.px)) {
        if (b.label != null) {
            Text(b.label, style = BeamType.Label, color = r.sub, maxLines = 1, modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = SAY.px))
        }
        if (b.lines.isNotEmpty()) {
            val words = buildAnnotatedString {
                b.lines.forEachIndexed { i, (text, colour) ->
                    if (i > 0) append('\n')
                    withStyle(SpanStyle(color = colour)) { append(text) }
                }
            }
            val one = b.file != null || b.meter != null
            Text(words, style = BeamType.Body, maxLines = if (one) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = SAY.px))
        }
        if (b.file != null) {
            Text(b.file, style = BeamType.Row, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = FILE.px))
        }
        b.where.forEachIndexed { i, w ->
            Text(w, style = BeamType.Value, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = (FOLDER + i * WHERE_PITCH).px))
        }
        b.meter?.let { level -> Meter({ level }, r, Modifier.padding(start = 24.px, end = 24.px, top = METER_TOP.px)) }
        if (b.under != null) {
            Text(b.under, style = BeamType.Body, color = r.sub, maxLines = 1, modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = UNDER.px))
        }
    }
}

// ---- restore --------------------------------------------------------------------------

/**
 * Backup > restore: the server's backups as rows, this C1's under "this c1" then another C1's under
 * "another c1", each newest first, the version each came from at the right, in the hue when this C1
 * has not got it yet (a restore of it is refused); those the server cannot read last, dim, with its
 * error, no tap. A tap asks first on [RestoreCard]; restore there fetches it, back on the backup
 * page, which shows it coming and the restart.
 */
@Composable
internal fun RestorePage(core: Core, nav: NavState) {
    val u by core.backups.ui.collectAsStateWithLifecycle()
    val r = rampOf(Feature.Settings)
    val list = u.list
    val mine = list?.mine.orEmpty()
    val picks = remember(list) { list?.let { it.mine + it.others }.orEmpty() }
    val unreadable = list?.unreadable.orEmpty()
    var asking by remember { mutableStateOf<ListedBackup?>(null) }
    // A group's label shares its first row's item, so keeping the row in view keeps the label.
    val rows = rememberRows(picks.size, nav::pop)
    val cancel = { asking = null; Cues.play(Sound.SheetDismiss) }
    Box(Modifier.fillMaxSize().edgeLeave({ asking != null }) { cancel() }) {
        PageTop("restore", r) {
            ShelfList(rows, r) {
                items(picks.size, key = { picks[it].name }) { i ->
                    val b = picks[i]
                    Column {
                        when (i) {
                            0 -> DotLabel(if (mine.isNotEmpty()) "this c1" else "another c1", r, top = LABEL_FIRST)
                            mine.size -> DotLabel("another c1", r)
                        }
                        ShelfRow(b.name, r, focused = rows.focus == i, value = b.about?.version, valueColour = if (b.newer) r.hue else null,
                            onClick = { rows.focus = i; asking = b; Cues.play(Sound.SheetIn) })
                    }
                }
                if (unreadable.isNotEmpty()) item { DotLabel("unreadable", r, top = if (picks.isEmpty()) LABEL_FIRST else LABEL_ABOVE) }
                items(unreadable.size, key = { unreadable[it].name }) { i -> UnreadableRow(unreadable[i], r) }
                if (list != null && picks.isEmpty() && unreadable.isEmpty()) {
                    item { SettingsWords("no backups on ${list.machine}", r.sub, top = LABEL_FIRST) }
                }
                item { Spacer(Modifier.height(24.px)) }
            }
        }
        asking?.let { b ->
            val refusal = remember(b) { core.backups.refusal(b) }
            RestoreCard(b, refusal, r, onCancel = cancel, onRestore = {
                asking = null
                core.backups.restore(b)
                nav.pop(Sound.Destroy)
            })
        }
    }
}

/** An unreadable backup's error: the row's second line, 38 under its title as update's source row has it. */
private const val ERROR_BASE = 83f

/** A backup the server cannot read: its file dim, as a row that cannot act, and the server's error under it in sub. */
@Composable
private fun UnreadableRow(b: ListedBackup, r: Ramp) {
    Box(Modifier.fillMaxWidth()) {
        ShelfRow(b.name, DEAD)
        b.error?.let {
            Text(it, style = CARD_LINE, color = r.sub,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = ERROR_BASE.px, bottom = (ROW - rowBase(ROW)).px))
        }
    }
}

// The card (`bkCard2`): 16 from the page's sides, radius 28, its words 24 in; the file's baseline
// 50 down, the lines from 88, 36 apart, the pills 30 under the last line and 24 over the card's
// foot, 12 apart. The page round it is cleared to the ground 16 out, fading over 32 more.
private const val CARD_SIDE = 16f
private const val CARD_IN = 24f
private const val CARD_RADIUS = 28f
private const val CARD_FILE = 50f
private const val CARD_LINES = 88f
private const val CARD_PILLS = 30f
private const val CARD_FOOT = 24f
private const val PILL_GAP = 12f
private const val CLEAR = 16f
private const val CLEAR_FADE = 32f
/** A pill filling or emptying as the wheel moves, as Agents' card lifts. */
private const val FILL_MS = 160

/** The card's lines, 36 apart. */
private val CARD_LINE = BeamType.Value.copy(lineHeight = 36f.pxSp)

/** What the confirm says under the file. */
private val ASK_LINES = listOf("replaces everything on this c1", "and restarts it")

/**
 * Asking before a restore, over the list under the sheet's scrim: a card in the middle of the page,
 * the file at 34, what restoring does at 28 in sub, then cancel and restore as pills. The wheel's
 * pill is filled, cancel in the hue, restore in danger; the other is its word alone. It opens on
 * cancel, since restore replaces everything. [refusal]: why it cannot go ahead (both versions and
 * what to do), restore dim and out of the wheel's reach. Off the card cancels.
 */
@Composable
private fun RestoreCard(b: ListedBackup, refusal: BackupProblem?, r: Ramp, onCancel: () -> Unit, onRestore: () -> Unit) {
    val dead = refusal != null
    var focus by remember { mutableIntStateOf(0) }
    WheelTarget { delta -> moved(focus, delta, if (dead) 0 else 1) { focus = it } }
    val lines = refusal?.let(::refusedLines) ?: ASK_LINES
    val fill = tween<Color>(FILL_MS)
    val cancelFill by animateColorAsState(if (focus == 0) r.hue else r.hue.copy(alpha = 0f), fill, label = "cancelFill")
    val cancelInk by animateColorAsState(if (focus == 0) r.ground else r.ink, fill, label = "cancelInk")
    val restoreFill by animateColorAsState(if (focus == 1) r.danger else r.danger.copy(alpha = 0f), fill, label = "restoreFill")
    val restoreInk by animateColorAsState(if (dead) r.dim else if (focus == 1) r.ground else r.danger, fill, label = "restoreInk")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(r.ground.copy(alpha = SCRIM)).tappable(onCancel))
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = CARD_SIDE.px)
                .drawBehind { clearing(r.ground) }
                .clip(RoundedCornerShape(CARD_RADIUS.px)).background(SURFACE)
                // The card takes its own touches, so only one off it cancels.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .padding(horizontal = CARD_IN.px),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(b.name, style = BeamType.Select, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.paddingFromBaseline(top = CARD_FILE.px))
                Text(lines.joinToString("\n"), style = CARD_LINE, color = r.sub,
                    modifier = Modifier.paddingFromBaseline(top = CARD_LINES.px, bottom = CARD_PILLS.px))
            }
            Row {
                Pill("cancel", cancelFill, cancelInk, onClick = onCancel)
                Spacer(Modifier.width(PILL_GAP.px))
                Pill("restore", restoreFill, restoreInk, onClick = if (dead) null else onRestore)
            }
            Spacer(Modifier.height(CARD_FOOT.px))
        }
    }
}

/** A refused restore's lines: a newer backup's both versions and what to do, else the refusal as the page says it. */
private fun refusedLines(p: BackupProblem): List<String> = when (p) {
    is BackupProblem.Newer -> listOf("needs ${p.version} or newer", "this c1 has ${p.app}", "update, then restore")
    else -> said(p).let { (first, detail) -> listOfNotNull(first, detail) }
}

/** The page cleared to the ground round the card, [CLEAR] out across the whole width and fading over [CLEAR_FADE] more, so no row is cut at its edge. */
private fun DrawScope.clearing(ground: Color) {
    val u = 1.px.toPx()
    val left = -CARD_SIDE * u
    val wide = size.width + 2f * CARD_SIDE * u
    val top = -CLEAR * u
    val bottom = size.height + CLEAR * u
    val fade = CLEAR_FADE * u
    val clear = ground.copy(alpha = 0f)
    drawRect(Brush.verticalGradient(listOf(clear, ground), startY = top - fade, endY = top), Offset(left, top - fade), Size(wide, fade))
    drawRect(ground, Offset(left, top), Size(wide, bottom - top))
    drawRect(Brush.verticalGradient(listOf(ground, clear), startY = bottom, endY = bottom + fade), Offset(left, bottom), Size(wide, fade))
}
