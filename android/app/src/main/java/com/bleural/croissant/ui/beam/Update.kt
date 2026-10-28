package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.tappable
import com.bleural.croissant.update.InstallFailure
import com.bleural.croissant.update.InstallStep
import com.bleural.croissant.update.Probe
import com.bleural.croissant.update.Source
import com.bleural.croissant.update.Stall
import com.bleural.croissant.update.UpdatePhase
import com.bleural.croissant.update.UpdateProblem
import com.bleural.croissant.update.UpdateUi
import com.bleural.croissant.update.Updater
import com.bleural.croissant.update.installAllowed
import com.bleural.croissant.update.timedLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Settings > update: one action at a time, the version always the biggest thing on the page, every
 * state saying what is happening and what comes next. A version in the hue is one this C1 has not
 * installed yet. Downloading and installing are two steps: a download goes on behind every page and
 * through sleep, and only installing locks, one black page of steps from the tap to "updated",
 * drawn by [BeamUpdateLock] over everything and by every process the install and the compile
 * restart.
 */

/** The Settings row's value: the newer version in the hue while it is out, downloading or ready, else the installed one in sub. */
internal fun updateValue(u: UpdateUi): Pair<String, Boolean> {
    val newer = u.release?.takeIf { u.phase == UpdatePhase.Out || u.phase == UpdatePhase.Downloading || u.phase == UpdatePhase.Ready }
    return if (newer != null && u.problem != UpdateProblem.OtherKey) newer.version.toString() to true else u.installed.toString() to false
}

/** The page's state, worked out once: its version, the facts beside it, its lines, and what else it shows. */
private data class Head(
    val version: String,
    val colour: Color,
    val right: List<String> = emptyList(),
    val lines: List<Pair<String, Color>> = emptyList(),
    /** Words after the first line's on its baseline, in sub ("updated from 0.1.0"). */
    val tail: String? = null,
    /** The battery after the first line's words, when it is why installing waits. */
    val battery: DeviceStatus? = null,
    val meter: Float? = null,
    val meterStopped: Boolean = false,
    /** A band of ink going over the meter: the download being checked. */
    val glint: Boolean = false,
    /** A line under the meter. */
    val under: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * What each state says: the first line what is happening, in ink; a meter where there is progress;
 * the next line what it means or what comes next, in sub; the numbers at the right. Installing and
 * finishing are drawn by [BeamUpdateLock] over this page.
 */
private fun headOf(u: UpdateUi, d: DeviceStatus, now: Long, r: Ramp): Head {
    val installed = u.installed.toString()
    val rel = u.release
    val newer = rel?.version?.toString()
    val facts = if (rel != null) listOfNotNull(dayOf(rel.published), mbOf(rel.apk.size)) else emptyList()
    val notes = rel?.notes?.map { it.lowercase(Locale.UK) } ?: emptyList()
    val failed = listOfNotNull("it did not install" to r.ink, failureOf(u.failure)?.let { it to r.sub })
    return when (u.phase) {
        UpdatePhase.Idle -> when (u.problem) {
            UpdateProblem.Offline -> Head(installed, r.ink, lines = listOf("can't reach github" to r.ink))
            UpdateProblem.Limited -> Head(installed, r.ink, lines = listOf("github says try later" to r.ink))
            UpdateProblem.NoReleases -> Head(installed, r.ink, lines = listOf("no releases at" to r.ink))
            else -> if (u.answered && u.checkedAt > 0) Head(installed, r.ink, right = listOf("checked " + checkedOf(u.checkedAt, now)), lines = listOf("up to date" to r.sub))
                else Head(installed, r.ink)
        }
        UpdatePhase.Checking -> if (newer != null) Head(newer, r.hue, facts, listOf("checking" to r.sub), notes = notes)
            else Head(installed, r.ink, lines = listOf("checking" to r.sub))
        UpdatePhase.Out -> when (u.problem) {
            UpdateProblem.OtherKey -> Head(installed, r.ink, lines = listOf("$newer is signed with another key" to r.danger, "the installer moves this c1 to it" to r.sub))
            UpdateProblem.Damaged -> Head(newer!!, r.hue, facts, listOf("that download was damaged" to r.ink), notes = notes)
            UpdateProblem.NotInstalled -> Head(newer!!, r.hue, facts, failed, notes = notes)
            else -> Head(newer!!, r.hue, facts, listOf("installed $installed" to r.sub), notes = notes)
        }
        UpdatePhase.Downloading -> {
            val version = newer ?: installed
            val total = rel?.apk?.size ?: 1L
            val frac = (u.done.toFloat() / total).coerceIn(0f, 1f)
            val had = "${mbCountOf(u.done)} of ${mbOf(total)}"
            val retryS = ((u.retryAt - now + 999) / 1000).coerceAtLeast(0)
            when {
                u.verifying -> Head(version, r.hue, listOf(mbOf(total)), listOf("checking the download" to r.ink), meter = 1f, glint = true)
                u.waitingForNetwork -> Head(version, r.hue, listOf(had), listOf((if (u.onData) "waiting for a network" else "waiting for wi-fi") to r.ink),
                    meter = frac, meterStopped = true, under = "goes on when it's back")
                u.retryAt > now -> Head(version, r.hue, listOf(had), listOf(stallOf(u.stall) to r.ink),
                    meter = frac, meterStopped = true, under = "trying again in $retryS s")
                else -> Head(version, r.hue, listOfNotNull(had, u.leftMs?.let(::aboutOf)), listOf("downloading" to r.ink),
                    meter = frac, under = "you can leave this page")
            }
        }
        UpdatePhase.Ready -> {
            val allowed = installAllowed(d.batteryPct, d.charging)
            // What keeps the key dim comes first: no room, then the battery; then why the last try failed.
            val (lines, battery) = when {
                u.problem == UpdateProblem.NoSpace -> listOf("not enough room to install" to r.ink, "needs ${mbOf(u.roomNeeded)} free" to r.sub) to false
                !allowed && d.charging -> listOf("install at 25 %" to r.ink) to true
                !allowed -> listOf("plug in to install" to r.ink, "needs 50 %, or 25 % charging" to r.sub) to true
                u.problem == UpdateProblem.NotInstalled -> failed to false
                else -> listOf("ready to install" to r.ink, "takes " + roughlyOf(u.times.totalMs) to r.sub) to false
            }
            Head(newer!!, r.hue, facts, lines, battery = d.takeIf { battery }, notes = notes)
        }
        // Under the lock, which draws the steps.
        UpdatePhase.Installing, UpdatePhase.Finishing -> Head(newer ?: installed, r.hue)
        UpdatePhase.Updated -> Head(installed, r.ink, lines = listOf("updated" to r.hue), tail = u.from?.let { "from $it" }, notes = notes)
    }
}

// The page's grid in content y (page y less the 66 above the content).
private const val VERSION_BASE = 104f
private const val FACT_PITCH = 36f
private const val LINE_BASE = 154f
private const val LINE_PITCH = 42f
/** The meter 24 under the first line, and the line under it 48 under the meter. */
private const val METER_TOP = LINE_BASE + 24f
private const val UNDER_BASE = METER_TOP + 36f + 48f
/** The rows start at y 254, or 34 under the last words when there are more. */
private const val ROWS_TOP = 188f

/**
 * The top of the page: the version at [BeamType.Version] at x 22 on baseline 170, the facts
 * about it (the time checked, a release's date and size, how much is down and how long is
 * left) at the right ending on that baseline, the lines of state from baseline 220 in Body,
 * the meter 24 under the first with a line under it, or the release's notes. As tall as what
 * it shows, the rows following.
 */
@Composable
private fun UpdateHead(h: Head, r: Ramp) {
    val lastLine = LINE_BASE + LINE_PITCH * (h.lines.size - 1).coerceAtLeast(0)
    val notesBase = (if (h.lines.isEmpty()) LINE_BASE - LINE_PITCH else lastLine) + 64f
    val bottom = maxOf(
        ROWS_TOP,
        when {
            h.notes.isNotEmpty() -> notesBase + LINE_PITCH * (h.notes.size - 1) + 34f
            // The line under a meter has its room even while it says nothing, so the rows hold still.
            h.meter != null -> UNDER_BASE + 34f
            else -> lastLine + 34f
        },
    )
    // At least as tall as the grid says; taller when a release's notes run onto more lines.
    Box(Modifier.fillMaxWidth().heightIn(min = bottom.px)) {
        Text(h.version, style = BeamType.Version, color = h.colour, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 22.px).paddingFromBaseline(top = VERSION_BASE.px))
        h.right.forEachIndexed { i, fact ->
            Text(fact, style = BeamType.Value, color = r.sub, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px)
                    .paddingFromBaseline(top = (VERSION_BASE - (h.right.size - 1 - i) * FACT_PITCH).px))
        }
        h.lines.forEachIndexed { i, (words, colour) ->
            Row(Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = (LINE_BASE + i * LINE_PITCH).px)) {
                Text(words, style = BeamType.Body, color = colour, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                if (i == 0 && h.tail != null) {
                    Text(" ${h.tail}", style = BeamType.Body, color = r.sub, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                }
                if (i == 0 && h.battery != null) {
                    Spacer(Modifier.width(22.px))
                    Icon(if (h.battery.charging) Glyphs.Charging else Glyphs.Battery, null, Modifier.size(28.px).align(Alignment.CenterVertically), r.sub)
                    Spacer(Modifier.width(10.px))
                    Text("${h.battery.batteryPct} %", style = BeamType.Value, color = r.sub, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
            }
        }
        if (h.meter != null) {
            val glint = if (h.glint) {
                val at by rememberInfiniteTransition(label = "glint").animateFloat(0f, 1f, infiniteRepeatable(tween(GLINT_MS, easing = LinearEasing)), label = "at")
                ({ at })
            } else null
            Meter({ h.meter }, r, Modifier.padding(horizontal = 24.px).offset(y = METER_TOP.px), lit = if (h.meterStopped) r.sub else r.fill, glint = glint)
        }
        if (h.under != null) {
            Text(h.under, style = BeamType.Body, color = r.sub, maxLines = 1,
                modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = UNDER_BASE.px))
        }
        if (h.notes.isNotEmpty()) {
            Text(h.notes.joinToString("\n"), style = BeamType.Body, color = r.ink,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = notesBase.px, bottom = 34.px))
        }
    }
}

/**
 * Settings > update. The wheel walks the rows (the source, check daily, and download on esim
 * while there is an eSIM profile, or it is on); up past the first is the step back. A look at
 * the source when the page opens, unless one was just made.
 */
@Composable
internal fun UpdatePage(core: Core, nav: NavState) {
    val u by core.update.ui.collectAsStateWithLifecycle()
    val device by core.device.collectAsStateWithLifecycle()
    val r = rampOf(Feature.Settings)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A countdown or a "checked" time on the page: the clock it reads moves every second.
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(Unit) {
        val idle = u.phase == UpdatePhase.Idle || u.phase == UpdatePhase.Out
        if (idle && (!u.answered || System.currentTimeMillis() - u.checkedAt > RECHECK_MS)) core.update.check()
    }
    val updated = u.phase == UpdatePhase.Updated
    val showData = device.cell != null || u.onData
    val count = if (updated) 0 else if (showData) 3 else 2
    val rows = rememberRows(count, nav::pop, itemOf = { it + 1 })
    // The first start of a new version opens here, out of the black the restart left.
    val appear = remember { Animatable(if (updated) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(Updater.FADE_MS.toInt())) }
    val head = headOf(u, device, now, r)
    Box(Modifier.fillMaxSize().background(r.ground).graphicsLayer { alpha = appear.value }) {
        PageTop("update", r) {
            ShelfList(rows, r) {
                item { UpdateHead(head, r) }
                if (!updated) {
                    item {
                        SourceRow(u.source.slug, r, focused = rows.focus == 0) { rows.focus = 0; nav.push(Screen.UpdateSource) }
                    }
                    item {
                        ShelfRow("check daily", r, focused = rows.focus == 1, trailing = { Toggle(u.daily, r) }, onClick = {
                            rows.focus = 1
                            core.update.setDaily(!u.daily)
                            Cues.play(if (u.daily) Sound.SwitchOff else Sound.SwitchOn)
                        })
                    }
                    if (showData) item {
                        ShelfRow("download on esim", r, focused = rows.focus == 2, trailing = { Toggle(u.onData, r) }, onClick = {
                            rows.focus = 2
                            core.update.setOnData(!u.onData)
                            Cues.play(if (u.onData) Sound.SwitchOff else Sound.SwitchOn)
                        })
                    }
                }
                // Clear of the key, so the last row can come up above it.
                item { Spacer(Modifier.height(140.px)) }
            }
            UpdateKey(u, device, r, core)
        }
    }
}

/** The one key the state has: check again, download, cancel, install (dim while the battery says wait), or put "updated" away. */
@Composable
private fun BoxScope.UpdateKey(u: UpdateUi, d: DeviceStatus, r: Ramp, core: Core) {
    when (u.phase) {
        UpdatePhase.Idle -> QuietKey(Glyphs.Restart, r, onClick = { core.update.check() })
        UpdatePhase.Checking -> SpinKey(r)
        UpdatePhase.Out -> if (u.problem != UpdateProblem.OtherKey) KnobKey(Glyphs.Download, r, onClick = {
            core.update.download()
            Cues.play(Sound.Commit)
        })
        UpdatePhase.Downloading -> QuietKey(Glyphs.Close, r, onClick = {
            core.update.cancel()
            Cues.play(Sound.SwitchOff)
        })
        UpdatePhase.Ready -> {
            val allowed = installAllowed(d.batteryPct, d.charging) && u.problem != UpdateProblem.NoSpace
            KnobKey(Glyphs.Check, r, enabled = allowed, onClick = { if (core.update.install()) Cues.play(Sound.Commit) })
        }
        UpdatePhase.Updated -> QuietKey(Glyphs.Check, r, onClick = { core.update.dismissUpdated() })
        UpdatePhase.Installing, UpdatePhase.Finishing -> {}
    }
}

/** The check-again key while it looks: its ring, and the arrow going round. */
@Composable
private fun BoxScope.SpinKey(r: Ramp) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = 24.px, bottom = 24.px).size(84.px)
            .drawBehind { val u = size.width / 84f; drawCircle(r.hue, 40.5f * u, style = Stroke(3f * u)) }
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyphs.Restart, null, Modifier.size(40.px).rotate(turn), r.hue) }
}

/**
 * The source as a row: its repository is too long beside the title, so it is the row's second line,
 * 38 under, in the value's colours. 102 tall, the chevron on the first line.
 */
@Composable
private fun SourceRow(slug: String, r: Ramp, focused: Boolean, onClick: () -> Unit) {
    val side = if (focused) r.hue else r.sub
    Box(Modifier.fillMaxWidth().height(102.px).tappable(onClick)) {
        Text("source", style = if (focused) BeamType.RowFocused else BeamType.Row, color = if (focused) r.hue else r.ink, maxLines = 1,
            modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = 45.px))
        Text(slug, style = BeamType.Value, color = side, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 83.px))
        Chevron(side, Modifier.align(Alignment.TopEnd).padding(end = 24.px).offset(y = 20.px))
    }
}

/**
 * Update > source: the repository in a field over the keys, `owner/repo` or a github.com link; done
 * keeps it and looks at what it has (finds X, or no releases there). The quiet key puts back the
 * build's own, dim while that is already the source.
 */
@Composable
internal fun UpdateSourcePage(core: Core, nav: NavState) {
    val u by core.update.ui.collectAsStateWithLifecycle()
    val r = rampOf(Feature.Settings)
    var text by remember { mutableStateOf(u.source.slug) }
    var typing by remember { mutableStateOf(true) }
    var answer by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    WheelTarget { false }
    fun look(source: Source) {
        answer = "checking" to r.sub
        scope.launch {
            answer = when (val p = core.update.probe(source)) {
                is Probe.Found -> "finds ${p.version}" to r.hue
                Probe.None -> "no releases there" to r.danger
                Probe.Offline -> "can't reach github" to r.ink
            }
        }
    }
    fun keep() {
        typing = false
        focus.clearFocus()
        val source = Source.parse(text)
        if (source == null || !core.update.setSource(text)) { answer = "that is not owner/repo" to r.danger; return }
        text = source.slug
        Cues.play(Sound.Commit)
        look(source)
    }
    PageTop("source", r) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.padding(top = 22.px)) {
                Field(
                    text, { text = it; typing = true; answer = null }, r,
                    Modifier.focusRequester(requester),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { keep() }),
                )
            }
            val (line, colour) = answer ?: ("github owner/repo" to r.sub)
            Text(line, style = if (answer == null && typing) BeamType.Label else BeamType.Body, color = colour, maxLines = 1,
                modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = 128.px))
            QuietKey(Glyphs.Return, r, enabled = !u.own, onClick = {
                core.update.resetSource()
                text = core.update.ui.value.source.slug
                typing = false
                focus.clearFocus()
                Cues.play(Sound.Commit)
                look(core.update.ui.value.source)
            })
        }
    }
}

/**
 * An install under way, over everything, the keys and the control centre included: the version, the
 * time left at the right, the four steps (done, under way with what it means, to come) and why
 * nothing answers a press; nothing to tap and every touch taken. The side button, the wheel and
 * power are refused in Core. Every process draws it the same from the marker, so the steps carry on
 * across both restarts; the step before each one says the screen goes dark, the page stays until
 * the app is taken away, and the next process fades it back in.
 */
@Composable
internal fun BeamUpdateLock(core: Core) {
    val u by core.update.ui.collectAsStateWithLifecycle()
    if (!u.locked) return
    val r = rampOf(Feature.Settings)
    // Shown at once on the tap (the page under it looks the same); faded in after a restart.
    val shown = remember { Animatable(if (u.step == InstallStep.Checking) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(Updater.FADE_MS.toInt())) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The time left and the timed meter move with the clock.
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    Box(
        Modifier.fillMaxSize().background(r.ground).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
            PageTop("update", r) { InstallSteps(u, now, r) }
        }
    }
}

/**
 * The steps from baseline 220, 42 apart: a mark in a 28 column at x 24 (done a tick in sub,
 * under way a dot in the hue, to come a ring in dim), the words at 66 in sub, ink and dim.
 * Under the compile while it runs, with its words, a slim timed meter. The lock and why at
 * the foot, on 600.
 */
@Composable
private fun InstallSteps(u: UpdateUi, now: Long, r: Ramp) {
    val step = u.step ?: InstallStep.Installing
    val elapsed = (now - u.stepAt).coerceAtLeast(0L)
    val version = (u.release?.version ?: u.installed).toString()
    Box(Modifier.fillMaxSize()) {
        Text(version, style = BeamType.Version, color = r.hue, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 22.px).paddingFromBaseline(top = VERSION_BASE.px))
        Text(aboutOf(u.times.leftMs(step, elapsed)), style = BeamType.Value, color = r.sub, maxLines = 1, softWrap = false,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = VERSION_BASE.px))
        var b = LINE_BASE
        for (s in InstallStep.entries) {
            val done = s < step
            val current = s == step
            val mid = b - 11f
            when {
                done -> Icon(Glyphs.Check, null, Modifier.offset(x = 24.px, y = (mid - 14f).px).size(28.px), r.sub)
                current -> Box(Modifier.offset(x = 30.px, y = (mid - 8f).px).size(16.px).background(r.hue, CircleShape))
                else -> Box(Modifier.offset(x = 30.px, y = (mid - 8f).px).size(16.px).border(2.px, r.dim, CircleShape))
            }
            Text(stepOf(s), style = BeamType.Body, color = if (done) r.sub else if (current) r.ink else r.dim, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = STEP_X.px).paddingFromBaseline(top = b.px))
            if (current && s == InstallStep.Compiling) {
                Meter({ timedLevel(elapsed, u.times.compilingMs) }, r,
                    Modifier.padding(start = STEP_X.px, end = 24.px).offset(y = (b + 20f).px), height = 22f)
                b += METER_ROOM
            }
            b += LINE_PITCH
        }
        Icon(Glyphs.Lock, null, Modifier.offset(x = 24.px, y = (FOOT_BASE - 25f).px).size(28.px), r.sub)
        Text("button and wheel are off", style = BeamType.Value, color = r.sub, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = STEP_X.px).paddingFromBaseline(top = FOOT_BASE.px))
    }
}

private fun stepOf(s: InstallStep): String = when (s) {
    InstallStep.Checking -> "checking the download"
    InstallStep.Installing -> "installing"
    InstallStep.Compiling -> "compiling"
    InstallStep.Restarting -> "restarting"
}

/** Why the last install did not happen, as the line under "it did not install". */
private fun failureOf(f: InstallFailure?): String? = when (f) {
    InstallFailure.NoRoom -> "there wasn't room for it"
    InstallFailure.Refused -> "android turned it down"
    InstallFailure.Interrupted -> "it was interrupted"
    InstallFailure.TooLong -> "it took too long"
    null -> null
}

private fun stallOf(s: Stall?): String = when (s) {
    Stall.Server -> "github isn't answering"
    Stall.Dropped, null -> "the connection dropped"
}

/** A time left as the page says it: to 5 s under a minute, then about a minute, then whole minutes. */
private fun aboutOf(ms: Long): String = when {
    ms < 57_500L -> "about ${maxOf(5L, (ms + 2_500L) / 5_000L * 5L)} s"
    ms < 90_000L -> "about a minute"
    else -> "about ${(ms + 30_000L) / 60_000L} min"
}

/** How long an install takes, before it starts: no finer than the minute. */
private fun roughlyOf(ms: Long): String = if (ms < 90_000L) "about a minute" else "about ${(ms + 30_000L) / 60_000L} min"

/** How old a look may be when the page opens before it looks again. */
private const val RECHECK_MS = 10 * 60 * 1000L
/** One pass of the band over the meter while the download is checked. */
private const val GLINT_MS = 1400
/** The steps' words, and what is under them, start here; the marks sit in the column before. */
private const val STEP_X = 66f
/** The compile's slim meter, 20 under its words and 22 tall, and the air before the next step. */
private const val METER_ROOM = 42f
/** The lock line's baseline in content y (600 on the page). */
private const val FOOT_BASE = 534f

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** A release's day, "12 oct". */
private fun dayOf(iso: String): String? = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DAY).lowercase(Locale.UK)
}.getOrNull()

/** When it last looked: the time today, the day before that. */
private fun checkedOf(at: Long, now: Long): String {
    val z = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(z)
    return if (t.toLocalDate() == Instant.ofEpochMilli(now).atZone(z).toLocalDate()) t.format(CLOCK) else t.format(DAY).lowercase(Locale.UK)
}

/** A size as the page says it: whole megabytes, "85 mb". */
private fun mbOf(bytes: Long): String = "${mbCountOf(bytes)} mb"

/** Whole megabytes without the unit, for "36 of 85 mb". */
private fun mbCountOf(bytes: Long): Long = (bytes + 500_000) / 1_000_000
