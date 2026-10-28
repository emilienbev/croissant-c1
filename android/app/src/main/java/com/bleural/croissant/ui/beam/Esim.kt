package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.bleural.croissant.Cell
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.esim.ActivationCode
import com.bleural.croissant.esim.EsimCard
import com.bleural.croissant.esim.EsimDraft
import com.bleural.croissant.esim.EsimFormat
import com.bleural.croissant.esim.EsimProblem
import com.bleural.croissant.esim.EsimProfile
import com.bleural.croissant.esim.EsimRepo
import com.bleural.croissant.esim.EsimTask
import com.bleural.croissant.esim.EsimUi
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberEsimScanner
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.sentence
import com.bleural.croissant.ui.word

/*
 * Settings > eSIM and its pages in the Settings hue, each the kit laid out as the Settings list is:
 * rows from y 104 or a first label at 132, groups apart by space and labels, nothing round them,
 * what goes wrong in danger. The card's state, a profile's name and the field being edited are on
 * the page, not in the header. Back is the pull everywhere, the profile, scan and download pages
 * included.
 */

private val S: Ramp get() = rampOf(Feature.Settings)

/** Why the card could not be read, in this design's lowercase (the shared [sentence]'s words, Magisk keeping its capital). */
private fun EsimProblem.low() = when (this) {
    EsimProblem.NoSim -> "no sim in the device"
    EsimProblem.NotReady -> "the sim is not ready: it may be starting up or off"
    EsimProblem.NoRoot -> "root was refused, so the card cannot be reached: the app needs its Magisk policy row"
    EsimProblem.NotEsim -> "the card in the slot is not an esim"
    EsimProblem.NoAnswer -> "the card did not answer"
}

private fun EsimTask.short() = when (kind) {
    EsimTask.Kind.Add -> "adding"
    EsimTask.Kind.Delete -> "deleting"
    EsimTask.Kind.Enable -> "enabling"
    EsimTask.Kind.Disable -> "disabling"
    EsimTask.Kind.Rename -> "renaming"
}

/** A step's word, lowercase. */
private fun EsimTask.Step.low() = word().lowercase()

/** Only the rows under it slide [pill], and only while [on]: with none focused it goes, rather than staying on the last. */
@Composable
private fun PillWhile(on: Boolean, pill: PillState, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPill provides if (on) pill else null, content = content)

/** One line of the eSIM page; the wheel stops on some of them. [shelf] names the group a row belongs to, null for a line that is not a row. */
private sealed class EsimLine(val shelf: String?) {
    /** The card's state over everything (the network, "no service", "reading"): the first label. */
    data class Status(val word: String) : EsimLine(null)
    data class Data(val cell: Cell) : EsimLine("top")
    data object Sms : EsimLine("top")
    data class Problem(val problem: EsimProblem, val detail: String?) : EsimLine(null)
    data object Retry : EsimLine("retry")
    data object Reading : EsimLine("reading")
    data class Group(val label: String, val count: Int?) : EsimLine(null)
    data class Profile(val profile: EsimProfile) : EsimLine("profiles")
    data object NoProfiles : EsimLine("profiles")
    data object Add : EsimLine("profiles")
    data class Facts(val card: EsimCard) : EsimLine("facts")
}

private fun esimLines(ui: EsimUi, cell: Cell?): List<EsimLine> = buildList {
    // The card's state, less a problem (its sentence says it) and the reading with no card yet (its row says it).
    val card0 = ui.card
    val switching = ui.task?.takeIf { it.running && (it.kind == EsimTask.Kind.Enable || it.kind == EsimTask.Kind.Disable) }
    val status = when {
        switching != null -> switching.short()
        ui.problem != null || card0 == null -> null
        ui.reading -> "reading"
        card0.inUse == null -> card0.summary.lowercase()
        ui.network != null -> ui.network
        else -> "no service"
    }
    status?.let { add(EsimLine.Status(it)) }
    val problem = ui.problem?.let { listOf(EsimLine.Problem(it, ui.detail), EsimLine.Retry) }.orEmpty()
    if (ui.card == null) addAll(problem)
    cell?.let { add(EsimLine.Data(it)) }
    add(EsimLine.Sms)
    if (ui.card != null) addAll(problem)
    val card = ui.card
    if (card == null) {
        if (ui.reading && ui.problem == null) add(EsimLine.Reading)
        return@buildList
    }
    add(EsimLine.Group("profiles", card.profiles.size))
    if (card.profiles.isEmpty()) add(EsimLine.NoProfiles)
    card.profiles.forEach { add(EsimLine.Profile(it)) }
    add(EsimLine.Add)
    add(EsimLine.Group("card", null))
    add(EsimLine.Facts(card))
}

private fun EsimLine.key(): String = when (this) {
    is EsimLine.Status -> "status"; is EsimLine.Data -> "data"; EsimLine.Sms -> "sms"; is EsimLine.Problem -> "problem"; EsimLine.Retry -> "retry"; EsimLine.Reading -> "reading"
    is EsimLine.Group -> "group-$label"; EsimLine.NoProfiles -> "none"; EsimLine.Add -> "add"; is EsimLine.Profile -> "profile-${profile.iccid}"; is EsimLine.Facts -> "facts"
}

/**
 * Settings > eSIM: the card's state as the first label (the network by its own name, "no
 * service", "reading", "enabling"), mobile data (a toggle, while a profile is in use) and sms
 * (a toggle that cannot move) under it, then the profiles under their label, a tick on the one
 * in use, add profile after them, and the card's facts under theirs. A failed read says why
 * in danger, with try again under it.
 */
@Composable
internal fun EsimPage(repo: EsimRepo, ui: EsimUi, cell: Cell?, onOpen: (EsimProfile) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(repo) { repo.refresh() }
    val r = S
    val lines = remember(ui, cell) { esimLines(ui, cell) }
    // Lazy item 0 is the 4 px over the first row, so line i is item i + 1.
    val stops = remember(lines) { lines.indices.filter { lines[it].let { l -> l is EsimLine.Data || l is EsimLine.Retry || l is EsimLine.Profile || l is EsimLine.Add || l is EsimLine.Facts } } }
    val rows = rememberRows(stops.size, onBack, itemOf = { stops[it] + 1 }, topOf = { stops[it].let { at -> if (lines.getOrNull(at - 1) is EsimLine.Group) at else at + 1 } })
    val lit = stops.getOrNull(rows.focus)?.takeIf { lines[it] !is EsimLine.Facts }
    // Each row's place in its group: a run of lines in the same group is one group.
    val edges = remember(lines) {
        lines.indices.map { i ->
            val s = lines[i].shelf ?: return@map null
            var a = i; while (a > 0 && lines[a - 1].shelf == s) a--
            var b = i; while (b < lines.lastIndex && lines[b + 1].shelf == s) b++
            shelfEdge(i - a, b - a + 1)
        }
    }
    PageTop("esim", r) {
        ShelfList(rows, r) {
            // 4 px over the first row, or nothing when the state's label comes first.
            item { if (lines.firstOrNull() !is EsimLine.Status) Spacer(Modifier.height(4.px)) }
            items(lines.size, key = { lines[it].key() }) { i ->
                val line = lines[i]
                val focusHere = { rows.focus = stops.indexOf(i) }
                val edge = edges[i]
                // A group that follows another group, or the words of a problem, stands apart from it; one under a label does not.
                val gap = edge != null && edge.top && i > 0 && lines[i - 1] !is EsimLine.Group && lines[i - 1] !is EsimLine.Status
                PillWhile(lit != null, rows.pill) {
                    Column {
                        if (gap) Spacer(Modifier.height(SETTINGS_GAP))
                        when (line) {
                            is EsimLine.Status -> DotLabel(line.word, r, top = LABEL_FIRST)
                            is EsimLine.Data -> ShelfRow(
                                "mobile data", r, focused = lit == i, edge = edge,
                                value = if (line.cell.dataEnabled && !line.cell.dataUp) "no internet" else null,
                                onClick = { focusHere(); Cues.play(if (line.cell.dataEnabled) Sound.SwitchOff else Sound.SwitchOn); repo.setMobileData(!line.cell.dataEnabled) },
                                trailing = { Toggle(line.cell.dataEnabled, r) },
                            )
                            EsimLine.Sms -> ShelfRow("sms", r, dim = true, edge = edge, trailing = { Toggle(!ui.smsBlocked, r) })
                            is EsimLine.Problem -> Column(Modifier.fillMaxWidth()) {
                                // First under the header, on the first label's line (132); under the top rows, a label's step below them.
                                SettingsWords(line.problem.low(), r.danger, top = if (i == 0) 28f else 40f)
                                if (line.problem == EsimProblem.NoAnswer && line.detail != null) {
                                    Text(line.detail, style = BeamType.Label, color = r.sub, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 36.px))
                                }
                            }
                            EsimLine.Retry -> ShelfRow("try again", r, focused = lit == i, edge = edge, value = if (ui.reading) "checking" else null, onClick = { focusHere(); if (!ui.reading) repo.recheck() })
                            EsimLine.Reading -> ShelfRow("reading the card", r, dim = true, edge = edge)
                            is EsimLine.Group -> DotLabel(line.label, r, count = line.count)
                            EsimLine.NoProfiles -> ShelfRow("no profiles on the card", r, dim = true, edge = edge)
                            is EsimLine.Profile -> {
                                val p = line.profile
                                val working = ui.task?.takeIf { it.iccid == p.iccid && it.running }
                                ShelfRow(
                                    p.name, r, focused = lit == i, edge = edge,
                                    value = working?.step?.low() ?: p.kind.takeIf { it != EsimProfile.Kind.Operational }?.name?.lowercase(),
                                    mark = if (p.enabled && working == null) Glyphs.Check else null,
                                    onClick = { focusHere(); onOpen(p) },
                                )
                            }
                            EsimLine.Add -> {
                                val adding = ui.task?.takeIf { it.kind == EsimTask.Kind.Add && it.running }
                                val held = adding == null && ui.task?.running == true
                                ShelfRow(
                                    if (adding != null) "adding" else "add profile", r, focused = lit == i, dim = held, edge = edge,
                                    value = adding?.step?.low(),
                                    onClick = { focusHere(); if (!held) onAdd() },
                                )
                            }
                            is EsimLine.Facts -> Column(Modifier.fillMaxWidth()) {
                                IdLines("eid", line.card.eid, r)
                                line.card.freeBytes?.let { ShelfRow("free space", r, value = EsimFormat.bytes(it)) }
                                line.card.firmware?.let { ShelfRow("firmware", r, value = it) }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** A long number among rows: its key as a label in sub at x 24, the digits in fours under it in ink, on as many lines as they take. */
@Composable
private fun IdLines(key: String, digits: String, r: Ramp) {
    Column(Modifier.fillMaxWidth().padding(start = 24.px, end = 24.px)) {
        Text(key, style = BeamType.Label, color = r.sub, modifier = Modifier.paddingFromBaseline(top = 38.px))
        Text(EsimFormat.grouped(digits), style = BeamType.Body, color = r.ink, modifier = Modifier.paddingFromBaseline(top = 40.px, bottom = 20.px))
    }
}

/**
 * A profile: its name as the item's title (a rename or delete under way under it); in use as
 * a toggle (starting the switch, which says where it is) and its nickname (a tap edits it
 * under a "nickname" label); what the card says about it and the iccid after a gap; a refusal
 * in danger; delete as the danger key for one not in use, through the sheet. The wheel
 * scrolls it and a tick up at the top steps back.
 */
@Composable
internal fun EsimProfilePage(repo: EsimRepo, ui: EsimUi, iccid: String, onDeleted: () -> Unit, onBack: () -> Unit) {
    val r = S
    val profile = ui.card?.profiles?.firstOrNull { it.iccid == iccid }
    val task = ui.task?.takeIf { it.iccid == iccid }
    val busy = task?.running == true
    var naming by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(task?.outcome) {
        if (task?.kind == EsimTask.Kind.Delete && task.outcome == EsimTask.Outcome.Done) { repo.dismissTask { it.iccid == iccid }; onDeleted() }
    }
    val endedBefore = remember(task?.started) { task?.outcome }
    LaunchedEffect(task?.outcome) {
        val ended = task ?: return@LaunchedEffect
        if (ended.outcome == endedBefore || ended.kind == EsimTask.Kind.Rename) return@LaunchedEffect
        when (ended.outcome) {
            EsimTask.Outcome.Done -> if (ended.kind == EsimTask.Kind.Enable) Cues.play(Sound.LinkUp) else if (ended.kind == EsimTask.Kind.Disable) Cues.play(Sound.LinkDown)
            EsimTask.Outcome.Failed -> Cues.play(Sound.JoinFailed)
            else -> Unit
        }
    }
    DisposableEffect(Unit) { onDispose { repo.dismissTask { it.iccid == iccid } } }

    if (naming && profile != null) {
        var text by remember { mutableStateOf(profile.nickname.orEmpty()) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        WheelTarget { false }
        PageTop("esim", r) {
            Column(Modifier.fillMaxSize()) {
                DotLabel("nickname", r, top = LABEL_FIRST)
                Field(
                    text, { text = it }, r, Modifier.focusRequester(requester),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        naming = false
                        if (text.trim() != profile.nickname.orEmpty()) repo.rename(profile, text)
                        Cues.play(Sound.Commit)
                    }),
                )
            }
        }
        return
    }
    val (scroll, pull) = rememberWordsScroll(onBack)
    val canDelete = profile != null && !profile.enabled
    Box(Modifier.fillMaxSize()) {
        PageTop("esim", r) {
            if (profile != null) Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                val switching = busy && (task!!.kind == EsimTask.Kind.Enable || task.kind == EsimTask.Kind.Disable)
                // The profile's name as the item's title; a rename or a delete under way under it (a switch says so on its row).
                ItemTitle(profile.name, r, details = if (busy && !switching) task!!.short() else null)
                Spacer(Modifier.height(SETTINGS_GAP))
                Shelf(r) {
                    ShelfRow(
                        "in use", r,
                        value = if (switching) task!!.step.low() else null,
                        onClick = { if (!busy) { Cues.play(if (profile.enabled) Sound.SwitchOff else Sound.SwitchOn); repo.setEnabled(profile, !profile.enabled) } },
                        trailing = { Toggle(profile.enabled, r) },
                    )
                    ShelfRow("nickname", r, value = profile.nickname, chevron = true, onClick = { if (!busy) naming = true })
                }
                Spacer(Modifier.height(SETTINGS_GAP))
                Shelf(r) {
                    profile.profileName?.takeIf { it != profile.name }?.let { ShelfRow("name", r, value = it) }
                    profile.provider?.takeIf { it != profile.name }?.let { ShelfRow("provider", r, value = it) }
                    if (profile.kind != EsimProfile.Kind.Operational) ShelfRow("kind", r, value = profile.kind.name.lowercase())
                    IdLines("iccid", profile.iccid, r)
                }
                val refusal = task?.takeIf { it.outcome == EsimTask.Outcome.Failed }?.let { it.problem?.low() ?: it.error?.sentence }
                if (refusal != null) SettingsWords(refusal, r.danger, top = 58f)
                Spacer(Modifier.height(if (canDelete) 112.px else 24.px))
            }
            if (canDelete) DangerKey(Glyphs.Trash, r, onClick = { asking = true; Cues.play(Sound.SheetIn) }, enabled = !busy)
        }
        if (asking) Sheet(
            r, "delete",
            onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
            onAct = { asking = false; profile?.let(repo::delete); Cues.play(Sound.Destroy) },
            colour = DANGER,
        )
    }
}

private val FIELDS = listOf(EsimDraft.Field.Address, EsimDraft.Field.MatchingId, EsimDraft.Field.Confirmation)
private fun EsimDraft.Field.title() = when (this) {
    EsimDraft.Field.Address -> "server"
    EsimDraft.Field.MatchingId -> "code"
    EsimDraft.Field.Confirmation -> "confirmation"
}
private fun EsimDraft.valueOf(field: EsimDraft.Field) = when (field) {
    EsimDraft.Field.Address -> address
    EsimDraft.Field.MatchingId -> matchingId
    EsimDraft.Field.Confirmation -> confirmation
}
private val ADD_STEPS = listOf(EsimTask.Step.Connecting, EsimTask.Step.Authenticating, EsimTask.Step.Downloading, EsimTask.Step.Installing)

/**
 * Settings > eSIM > Add profile: the viewfinder first when reached from the page's own row, the
 * form when a link filled it in, and the download once it starts.
 */
@Composable
internal fun EsimAddPage(repo: EsimRepo, ui: EsimUi, scan: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val task = ui.task?.takeIf { it.kind == EsimTask.Kind.Add }
    DisposableEffect(Unit) { onDispose { repo.dismissTask { it.kind == EsimTask.Kind.Add } } }
    var scanning by remember { mutableStateOf(scan && task == null) }
    when {
        task != null -> Progress(repo, task, onDone, onBack)
        scanning -> Scan(onCode = { repo.fillDraft(it); scanning = false; Cues.play(Sound.ScreenIn) }, onByHand = { scanning = false; Cues.play(Sound.ScreenIn) }, onBack = onBack)
        else -> Form(repo, ui.draft, onBack)
    }
}

/**
 * The viewfinder as the Camera's is (x 16 to 464 from y 104, radius 14, faint until the camera
 * shows), what it sees or why not under it (in danger when it cannot), and the knob (the lines
 * glyph) to enter the code by hand instead.
 */
@Composable
private fun Scan(onCode: (ActivationCode) -> Unit, onByHand: () -> Unit, onBack: () -> Unit) {
    val r = S
    val scanner = rememberEsimScanner(onCode)
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    val (words, colour) = when {
        scanner.opened == false -> "the camera could not be opened" to r.danger
        scanner.opened == null -> "starting the camera" to r.sub
        scanner.notACode -> "that QR code is not an esim code" to r.danger
        else -> "point at the QR code" to r.ink
    }
    PageTop("esim", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(start = 16.px, end = 16.px, top = 4.px).fillMaxWidth().height(330.px).clip(RoundedCornerShape(14.px)).background(r.deep)) {
                if (scanner.opened != false) AndroidView(factory = { scanner.view }, modifier = Modifier.fillMaxSize())
            }
            Text(words, style = BeamType.Body, color = colour, maxLines = 2,
                modifier = Modifier.padding(start = 24.px, end = 120.px).paddingFromBaseline(top = 50.px))
            Spacer(Modifier.height(112.px))
        }
        KnobKey(Glyphs.Text, r, onClick = onByHand)
    }
}

/** The form: server, code and confirmation as rows from y 104 with what they hold, each edited in a field under its label; the knob adds, dim until it can. */
@Composable
private fun Form(repo: EsimRepo, draft: EsimDraft, onBack: () -> Unit) {
    val r = S
    var editing by remember { mutableStateOf<EsimDraft.Field?>(null) }
    val add = { if (draft.ready) { repo.add(); Cues.play(Sound.Commit) } }
    val field = editing
    if (field != null) {
        WheelTarget { false }
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        PageTop("esim", r) {
            Column(Modifier.fillMaxSize()) {
                DotLabel(field.title(), r, top = LABEL_FIRST)
                Field(
                    draft.valueOf(field), { repo.editDraft(field, it) }, r, Modifier.focusRequester(requester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field == EsimDraft.Field.Address) KeyboardType.Uri else KeyboardType.Ascii,
                        autoCorrectEnabled = false, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { editing = null; Cues.play(Sound.Commit) }),
                )
            }
        }
        return
    }
    val rows = rememberRows(FIELDS.size, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    PageTop("esim", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(4.px)) }
            items(FIELDS.size) { i ->
                val f = FIELDS[i]
                ShelfRow(f.title(), r, focused = rows.focus == i, value = draft.valueOf(f).ifEmpty { null }, onClick = { rows.focus = i; editing = f }, edge = shelfEdge(i, FIELDS.size))
            }
        }
        KnobKey(Glyphs.Plus, r, onClick = add, enabled = draft.ready)
    }
}

/**
 * The download: the profile's name as the item's title ("added" or "cancelled" under it once
 * it ends), the four steps as rows, a tick on each one done and the current one focused with
 * a sweeping meter; a failure's words in danger under them. The knob cancels until it is
 * installing, then says done (the tick) or try again (the arrow round).
 */
@Composable
private fun Progress(repo: EsimRepo, task: EsimTask, onDone: () -> Unit, onBack: () -> Unit) {
    val r = S
    val bottom: Pair<androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>? = when {
        task.cancellable -> Glyphs.Close to { repo.cancelAdd(); Cues.play(Sound.SwitchOff) }
        task.running -> null
        task.outcome == EsimTask.Outcome.Done -> Glyphs.Check to onDone
        else -> Glyphs.Retake to { repo.add(); Cues.play(Sound.Commit) }
    }
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    val endedBefore = remember(task.started) { task.outcome }
    LaunchedEffect(task.outcome) {
        if (task.outcome != endedBefore) when (task.outcome) {
            EsimTask.Outcome.Done -> Cues.play(Sound.LinkUp)
            EsimTask.Outcome.Failed -> Cues.play(Sound.JoinFailed)
            else -> Unit
        }
    }
    // Under way, the focused step says where it is; failed, the words under the steps say why.
    val ended = when (task.outcome) {
        EsimTask.Outcome.Done -> "added"
        EsimTask.Outcome.Cancelled -> "cancelled"
        else -> null
    }
    val reached = ADD_STEPS.indexOf(task.step)
    val current = reached.takeIf { task.outcome != EsimTask.Outcome.Done && it >= 0 }
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    val pill = rememberPill()
    PageTop("esim", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            ItemTitle(task.name, r, details = ended)
            Spacer(Modifier.height(SETTINGS_GAP))
            PillWhile(current != null, pill) {
                Shelf(r) {
                    ADD_STEPS.forEachIndexed { i, step ->
                        val done = i < reached || task.outcome == EsimTask.Outcome.Done
                        val now = i == current
                        val failed = now && task.outcome == EsimTask.Outcome.Failed
                        ShelfRow(
                            step.low(), r, focused = now, dim = !done && !now,
                            value = if (now && task.outcome == EsimTask.Outcome.Cancelled) "stopped" else null,
                            mark = if (done) Glyphs.Check else null,
                            trailing = when {
                                failed -> ({ RowLabel("failed", r.danger) })
                                now && task.running -> ({ Meter({ sweep }, r, Modifier.width(86.px), thick = false, track = r.deep) })
                                else -> null
                            },
                        )
                    }
                }
            }
            if (task.outcome == EsimTask.Outcome.Failed) {
                val sentence = task.problem?.low() ?: task.error?.sentence
                val detail = if (task.problem != null) task.error?.sentence else task.error?.detail
                Column(Modifier.fillMaxWidth().padding(start = 24.px, end = 24.px)) {
                    if (sentence != null) Text(sentence, style = BeamType.Body, color = r.danger, modifier = Modifier.paddingFromBaseline(top = 58.px))
                    if (detail != null) Text(detail, style = BeamType.Label, color = r.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.paddingFromBaseline(top = 36.px))
                }
            }
            Spacer(Modifier.height(112.px))
        }
        if (bottom != null) KnobKey(bottom.first, r, onClick = bottom.second)
    }
}
