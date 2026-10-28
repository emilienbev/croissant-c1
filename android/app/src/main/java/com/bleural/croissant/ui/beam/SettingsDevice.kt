package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.BatteryTracker
import com.bleural.croissant.Core
import com.bleural.croissant.Idle
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.IdleWords
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.BatterySample
import com.bleural.croissant.data.BatteryStats
import com.bleural.croissant.data.BatteryStats.Window
import com.bleural.croissant.ui.Designs
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PowerAction
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.power
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Settings, the second half: Device and the pages under it (Sound, Display, Design, Screen off,
 * Battery and their pickers), Power, and the eSIM pages (Esim.kt), all in the Settings hue. Each is
 * the kit laid out as the Settings list is, the first row at y 104 or the first label at 132,
 * groups apart by space.
 */

/** The Settings ramp, every page here. */
private val S: Ramp get() = rampOf(Feature.Settings)

/** The pushed Settings pages this half draws, by their [Screen]; the first half draws the rest. */
@Composable
internal fun SettingsDevicePages(
    screen: Screen,
    core: Core,
    nav: NavState,
) {
    when (screen) {
        Screen.Device -> {
            val battery by core.battery.items.collectAsStateWithLifecycle()
            val life = remember(battery) { BatteryStats.lifeMinutes(battery)?.let(BatteryStats::fmt) }
            DevicePage(core.volume, core.muted, core.brightness, life, Designs.label(core.look), core.tones, onOpen = nav::push, onSetup = { core.openSetup() }, onBack = nav::pop)
        }
        Screen.Sound -> LevelPage("sound", "volume", Glyphs.Sound, core.volume, step = 1, onChange = core::saveVolume, onBack = nav::pop)
        Screen.Display -> LevelPage("display", "brightness", Glyphs.Display, core.brightness, step = 5, onChange = core::saveBrightness, onBack = nav::pop)
        Screen.Battery -> BatteryListPage(core.rest, core.sleep, onOpen = nav::push, onBack = nav::pop)
        Screen.Rest -> PickPage("rest", null, Idle.OPTIONS, { Idle.label(it).lowercase() }, core.rest, note = IdleWords.REST, onPick = { core.saveRest(it); nav.pop() }, onBack = nav::pop)
        Screen.Sleep -> PickPage("sleep", null, Idle.OPTIONS, { Idle.label(it).lowercase() }, core.sleep, note = IdleWords.SLEEP, onPick = { core.saveSleep(it); nav.pop() }, onBack = nav::pop)
        Screen.BatteryStats -> {
            val battery by core.battery.items.collectAsStateWithLifecycle()
            val days by core.battery.days.collectAsStateWithLifecycle()
            val months by core.battery.months.collectAsStateWithLifecycle()
            BatteryPage(battery, days, months, core.batteryTrack, core.batteryEvery, onTrack = core::saveBatteryTrack, onEvery = { nav.push(Screen.BatteryEvery) }, onBack = nav::pop)
        }
        Screen.BatteryEvery -> PickPage("battery", "every", BatteryTracker.EVERY_OPTIONS, BatteryTracker::label, core.batteryEvery, onPick = { core.saveBatteryEvery(it); nav.pop() }, onBack = nav::pop)
        Screen.Power -> PowerPage(core.isOwner, onAction = { core.power(it) }, onBack = nav::pop)
        // Picking another design swaps the whole UI (Core.saveLook); this page goes with it.
        Screen.Design -> PickPage(
            "design", null, Designs.looks.map { it.first }, { l -> Designs.looks.first { it.first == l }.second }, Designs.resolve(core.look),
            onPick = { nav.pop(); core.saveLook(it) }, onBack = nav::pop,
        )
        // The key plays the highlighted pack's phrase, and moving the highlight stops it; a pick
        // stays here, so the packs can be tried in turn.
        Screen.Tones -> {
            DisposableEffect(Unit) { core.previewTones(true); onDispose { core.previewTones(false) } }
            val packs = core.tonePacks
            var focused by remember { mutableStateOf(packs.first()) }
            Box(Modifier.fillMaxSize()) {
                PickPage("tones", null, packs, { it }, core.tones, onFocus = { focused = it; core.stopTones() }, onPick = core::saveTones, onBack = nav::pop)
                KnobKey(if (core.tonesPlaying) Glyphs.Pause else Glyphs.Play, S, onClick = {
                    // The phrase speaks for the play; the pause is a tap like any other.
                    if (core.tonesPlaying) { core.stopTones(); Cues.play(Sound.Commit) } else core.playTones(focused)
                })
            }
        }
        Screen.Esim -> {
            val esim by core.esim.state.collectAsStateWithLifecycle()
            val device by core.device.collectAsStateWithLifecycle()
            EsimPage(core.esim, esim, device.cell, onOpen = { nav.push(Screen.EsimProfile(it.iccid)) }, onAdd = { nav.push(Screen.EsimAdd(scan = true)) }, onBack = nav::pop)
        }
        is Screen.EsimProfile -> {
            val esim by core.esim.state.collectAsStateWithLifecycle()
            EsimProfilePage(core.esim, esim, screen.iccid, onDeleted = { nav.pop() }, onBack = nav::pop)
        }
        is Screen.EsimAdd -> {
            val esim by core.esim.state.collectAsStateWithLifecycle()
            EsimAddPage(core.esim, esim, screen.scan, onDone = nav::pop, onBack = nav::pop)
        }
        else -> Unit
    }
}

/**
 * A page that is one group of rows under its header: from y 104 as the Settings list, or
 * under [label] (the first label, baseline 132) when it has one. The wheel walks the rows and
 * a tick up at the first steps back. [row] draws row `i`, focused when `rows.focus == i`, with
 * `edge = shelfEdge(i, count)`. [note] is a paragraph over the rows, in sub. [onFocus] hears of
 * every move of the focus after the first frame, by the wheel or a tap.
 */
@Composable
private fun RowsPage(word: String, count: Int, onBack: () -> Unit, label: String? = null, note: String? = null, onFocus: ((Int) -> Unit)? = null, row: @Composable (Int, Rows) -> Unit) {
    val r = S
    // Lazy item 0 is the label or the 4 px over the rows, so row i is item i + 1.
    val rows = rememberRows(count, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    val focused = rememberUpdatedState(onFocus)
    LaunchedEffect(rows) { snapshotFlow { rows.focus }.drop(1).collect { focused.value?.invoke(it) } }
    PageTop(word, r) {
        ShelfList(rows, r) {
            item {
                Column {
                    if (label != null) DotLabel(label, r, top = LABEL_FIRST) else Spacer(Modifier.height(4.px))
                    if (note != null) { SettingsWords(note, r.sub, top = 32f); Spacer(Modifier.height(24.px)) }
                }
            }
            items(count) { i -> row(i, rows) }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** Design is there only with a second look to pick: with one, its page would offer nothing. */
private val DEVICE_ROWS = listOf(Screen.Sound, Screen.Display, Screen.Design, Screen.Tones, Screen.Battery)
    .filter { it != Screen.Design || Designs.looks.size > 1 }

/**
 * Settings > Device: sound, display, design, tones and battery, glyph rows as Settings' with what
 * each is set to (the design and the tones by their own names); then, apart, setup, which runs the
 * first setup again from hello without wiping anything.
 */
@Composable
internal fun DevicePage(volume: Int, muted: Boolean, brightness: Int, batteryLife: String?, design: String, tones: String, onOpen: (Screen) -> Unit, onSetup: () -> Unit, onBack: () -> Unit) {
    RowsPage("device", DEVICE_ROWS.size + 1, onBack) { i, rows ->
        if (i == DEVICE_ROWS.size) {
            Column {
                Spacer(Modifier.height(SETTINGS_GAP))
                ShelfRow("setup", S, focused = rows.focus == i, onClick = { rows.focus = i; onSetup() }, glyph = Glyphs.Restart, chevron = true)
            }
            return@RowsPage
        }
        val s = DEVICE_ROWS[i]
        val (glyph, title, value) = when (s) {
            Screen.Sound -> Triple(Glyphs.Sound, "sound", if (muted) "muted" else volume.toString())
            Screen.Display -> Triple(Glyphs.Display, "display", brightness.toString())
            Screen.Design -> Triple(Glyphs.Settings, "design", design)
            Screen.Tones -> Triple(Glyphs.Tones, "tones", tones)
            else -> Triple(Glyphs.Battery, "battery", batteryLife)
        }
        ShelfRow(title, S, focused = rows.focus == i, onClick = { rows.focus = i; onOpen(s) }, glyph = glyph, value = value, chevron = true, edge = shelfEdge(i, DEVICE_ROWS.size))
    }
}

/**
 * One level, 0 to 100: [label] first (baseline 132), the number in [BeamType.Big] at x 24 on
 * baseline 290, [glyph] 40 in the hue under it, and the level down the right (the kit's eight
 * segments, 56 x 420 from x 400 and y 150, lit in the hue from the bottom). The wheel turns it
 * [step] a detent, up is more, and never leaves the page; a tap or a drag on the level sets
 * it. Each change is applied at once. A finger pulled down on the page steps back.
 */
@Composable
internal fun LevelPage(word: String, label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector, value: Int, step: Int, onChange: (Int) -> Unit, onBack: () -> Unit) {
    val r = S
    val latest = rememberUpdatedState(value)
    WheelTarget { delta ->
        val next = (latest.value - delta * step).coerceIn(0, 100)
        if (next == latest.value) return@WheelTarget false
        onChange(next)
        true
    }
    val pull = rememberPull(onBack)
    // The level on the page: 400 to 456 across, 116 to 570 down, its top [LIFT] higher than under
    // the top bar and its foot where it was; in the content, from [DRAWN_TOP].
    val top = 150f - DRAWN_TOP
    val tall = 420f + LIFT
    val set = { y: Float ->
        val v = (100f * (1f - (y - top) / tall)).roundToInt().coerceIn(0, 100)
        if (v != latest.value) onChange(v)
    }
    PageTop(word, r) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(h)) {
                    DotLabel(label, r, top = LABEL_FIRST)
                    Text(value.toString(), style = BeamType.Big, color = r.ink, maxLines = 1,
                        modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = (290f - DRAWN_TOP).px))
                    Icon(glyph, null, Modifier.offset(x = 24.px, y = (322f - DRAWN_TOP).px).size(40.px), r.hue)
                    Level(value / 100f, r, Modifier.offset(x = 400.px, y = top.px).size(56.px, tall.px))
                    // Only a touch on the level or just round it sets it; the rest of the page is left to the pull.
                    Box(
                        Modifier.offset(x = 360.px, y = (top - 20f).px).size(120.px, (tall + 40f).px).pointerInput(Unit) {
                            val u = 1.px.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                set(down.position.y / u + top - 20f)
                                drag(down.id) { c -> c.consume(); set(c.position.y / u + top - 20f) }
                                Cues.play(Sound.Commit)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Settings > Device > Battery: rest and sleep with their times, and stats, glyph rows into their pages. */
@Composable
internal fun BatteryListPage(rest: Int, sleep: Int, onOpen: (Screen) -> Unit, onBack: () -> Unit) {
    val rows = listOf(Screen.Rest, Screen.Sleep, Screen.BatteryStats)
    RowsPage("battery", rows.size, onBack) { i, focus ->
        val s = rows[i]
        val (glyph, title, value) = when (s) {
            Screen.Rest -> Triple(Glyphs.ScreenOff, "rest", Idle.label(rest).lowercase())
            Screen.Sleep -> Triple(Glyphs.Moon, "sleep", Idle.label(sleep).lowercase())
            else -> Triple(Glyphs.Battery, "stats", null)
        }
        ShelfRow(title, S, focused = focus.focus == i, onClick = { focus.focus = i; onOpen(s) }, glyph = glyph, value = value, chevron = true, edge = shelfEdge(i, rows.size))
    }
}

/**
 * A picker: the choices as rows, the current one ticked; a tap picks and goes back. [title]
 * is what is being picked ("every"), the first label over the rows; with none (Design)
 * the rows start at y 104. [note] says what the choice does, over the rows. [onFocus] hears
 * of every move of the highlight after the first frame, by the wheel or a tap.
 */
@Composable
internal fun <T> PickPage(word: String, title: String?, options: List<T>, label: (T) -> String, value: T, note: String? = null, onFocus: ((T) -> Unit)? = null, onPick: (T) -> Unit, onBack: () -> Unit) {
    RowsPage(word, options.size, onBack, label = title, note = note, onFocus = onFocus?.let { f -> { i: Int -> options.getOrNull(i)?.let(f) } }) { i, rows ->
        val v = options[i]
        ShelfRow(label(v), S, focused = rows.focus == i, onClick = { rows.focus = i; onPick(v) }, mark = if (v == value) Glyphs.Check else null, edge = shelfEdge(i, options.size))
    }
}

/**
 * Power: glyph rows, sleep at once; restart, power off and exit kiosk (while the app is the
 * owner) through the sheet, the last two in danger, on the row and on the sheet's word.
 */
@Composable
internal fun PowerPage(isOwner: Boolean, onAction: (PowerAction) -> Unit, onBack: () -> Unit) {
    val r = S
    val actions = PowerAction.entries.filter { it != PowerAction.ExitKiosk || isOwner }
    var asking by remember { mutableStateOf<PowerAction?>(null) }
    Box(Modifier.fillMaxSize()) {
        RowsPage("power", actions.size, onBack) { i, rows ->
            val a = actions[i]
            ShelfRow(a.label.lowercase(), r, focused = rows.focus == i, glyph = a.glyph, danger = a.danger, edge = shelfEdge(i, actions.size), onClick = {
                rows.focus = i
                if (a.confirm) { asking = a; Cues.play(Sound.SheetIn) } else onAction(a)
            })
        }
        asking?.let { a ->
            Sheet(
                r, a.label.lowercase(),
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = null; Cues.play(if (a.danger) Sound.Destroy else Sound.Commit); onAction(a) },
                colour = if (a.danger) DANGER else null,
            )
        }
    }
}

private val PowerAction.glyph
    get() = when (this) {
        PowerAction.Sleep -> Glyphs.Moon
        PowerAction.Reboot -> Glyphs.Restart
        PowerAction.PowerOff -> Glyphs.Power
        PowerAction.ExitKiosk -> Glyphs.Exit
    }

/** Red for what ends something: Power off and Exit kiosk. */
private val PowerAction.danger: Boolean
    get() = this == PowerAction.PowerOff || this == PowerAction.ExitKiosk

/**
 * Settings > Device > Battery > Stats: track as a toggle and every into its picker while tracking,
 * from y 104; the window (24 h, 7 d, 30 d) as words under them; the chart, with nothing round
 * it; the facts over the window as rows, a rate reaching back past the window with how far it
 * went. Groups apart by space. The wheel steps track, every and the window, then walks the
 * window and scrolls the facts. Whether it tracks is the toggle's to say.
 */
@Composable
internal fun BatteryPage(samples: List<BatterySample>, days: List<BatteryStats.Day>, months: List<BatteryStats.Month>, track: Boolean, every: Int, onTrack: (Boolean) -> Unit, onEvery: () -> Unit, onBack: () -> Unit) {
    val r = S
    var window by remember { mutableIntStateOf(0) }
    var focus by remember { mutableIntStateOf(0) }
    val sel = if (track) 2 else 1
    val stats = sel + 1
    LaunchedEffect(sel) { if (focus > sel) focus = sel }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.px.roundToPx() }
    val pull = rememberPull(onBack)
    val pill = rememberPill()
    LaunchedEffect(focus) { if (focus < stats) scroll.animateScrollTo(0) }
    WheelTarget { delta ->
        val wasFocus = focus
        val wasWindow = window
        when {
            focus == stats && delta < 0 && scroll.value - step <= 0 -> focus = sel
            focus == stats -> scope.launch { scroll.glideBy(delta * step) }
            focus == sel && delta > 0 -> if (window < Window.entries.lastIndex) window++
                else if (scroll.maxValue > 0) { focus = stats; scope.launch { scroll.animateScrollTo(step.coerceAtMost(scroll.maxValue)) } }
            focus == sel && delta < 0 -> if (window > 0) window-- else focus = sel - 1
            delta < 0 && focus == 0 -> pull.stepBack()
            else -> focus = (focus + delta).coerceIn(0, sel)
        }
        focus != wasFocus && focus != stats || window != wasWindow
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val w = Window.entries[window]
    val since = now - w.days * 86_400_000L
    val chart = remember(samples, days, w, now) { BatteryStats.chart(samples, w, now, ZoneId.systemDefault(), days) }
    val rates = remember(samples, days, months, since) { BatteryStats.reach(samples, since, days, months) }

    PageTop("stats", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            Spacer(Modifier.height(4.px))
            // The pill is on a row only while a row has the focus; on the window it goes.
            CompositionLocalProvider(LocalPill provides if (focus < sel) pill else null) {
                Shelf(r) {
                    ShelfRow("track", r, focused = focus == 0, onClick = { focus = 0; onTrack(!track); Cues.play(if (track) Sound.SwitchOff else Sound.SwitchOn) }, trailing = { Toggle(track, r) })
                    if (track) ShelfRow("every", r, focused = focus == 1, value = BatteryTracker.label(every), chevron = true, onClick = { focus = 1; onEvery() })
                }
            }
            Spacer(Modifier.height(SETTINGS_GAP))
            WindowPick(window, focused = focus == sel, r) { i -> focus = sel; window = i; Cues.play(Sound.Commit) }
            Spacer(Modifier.height(SETTINGS_GAP))
            BatteryChart(chart, r, Modifier.fillMaxWidth().padding(horizontal = 24.px))
            Spacer(Modifier.height(SETTINGS_GAP))
            Shelf(r) {
                if (samples.isNotEmpty()) {
                    val onBattery = BatteryStats.onBatteryMinutes(samples, now)
                    ShelfRow("on battery", r, value = onBattery?.let(BatteryStats::fmt) ?: "charging")
                    ShelfRow("screen on", r, value = BatteryStats.fmt(BatteryStats.screenOnMinutes(samples, since, now, days)))
                }
                ShelfRow("screen on drain", r, value = BatteryStats.fmtReached(rates.screenOn, since, now) ?: "n/a")
                ShelfRow("screen off drain", r, value = BatteryStats.fmtReached(rates.screenOff, since, now) ?: "n/a")
                ShelfRow("charge rate", r, value = BatteryStats.fmtReached(rates.charge, since, now) ?: "n/a")
            }
            Spacer(Modifier.height(24.px))
        }
    }
}

/**
 * The chart, from x 24 to 456: a bar a slot in the hue, plugged in in faint, low in danger;
 * the mean across in ink, the labels in sub. Levels by the hour take 24 slots across; more
 * than 24 (7 d) scroll sideways, opening on the newest day, a drag right going back, and the
 * mean is of the bars in view (2026-10-18).
 */
@Composable
private fun BatteryChart(chart: BatteryStats.Chart, r: Ramp, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    val n = chart.bars.size
    val across = if (chart.levels) minOf(n, 24) else n
    if (n == 0) { Spacer(modifier.height(160.px)); return }
    // Value 0 is the end of the bars: the newest hour on the right.
    val sideways = remember(n) { ScrollState(0) }
    BoxWithConstraints(modifier.height(160.px)) {
        val view = constraints.maxWidth.toFloat()
        val pitch = view / across
        val wide = with(LocalDensity.current) { (pitch * n).toDp() }
        Box(Modifier.fillMaxSize().horizontalScroll(sideways, enabled = n > across, reverseScrolling = true)) {
            Canvas(Modifier.width(wide).fillMaxHeight()) {
                val u = 1.px.toPx()
                val base = 110 * u; val gap = 4 * u
                val bw = pitch - gap
                chart.bars.forEachIndexed { i, bar ->
                    val v = bar.value ?: return@forEachIndexed
                    val bh = maxOf(3 * u, base * v / 100f)
                    val c = when { bar.plugged -> r.deep; chart.levels && v <= BatteryStats.LOW -> r.danger; else -> r.fill }
                    drawRoundRect(c, Offset(i * pitch, base - bh), Size(bw, bh), CornerRadius(minOf(bw / 2, 5 * u)))
                }
                for ((i, label) in chart.labels) {
                    val x = i * pitch + if (chart.levels) 0f else bw / 2
                    val laid = text.measure(label, BeamType.Label)
                    val tx = (if (chart.levels) x else x - laid.size.width / 2f).coerceIn(0f, size.width - laid.size.width)
                    drawText(laid, r.sub, Offset(tx, size.height - laid.size.height))
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = 1.px.toPx()
            val base = 110 * u
            // The bars in view: the left edge sits at the content's width less the view and the scroll.
            val left = pitch * n - view - sideways.value
            val first = (left / pitch).toInt().coerceIn(0, n - 1)
            val last = ((left + view) / pitch).toInt().coerceIn(first, n - 1)
            val mean = (if (n > across) BatteryStats.mean(chart.bars.subList(first, last + 1)) else chart.mean) ?: return@Canvas
            val y = base - base * mean / 100f
            drawRoundRect(r.ink, Offset(0f, y - 1.5f * u), Size(size.width, 3 * u), CornerRadius(1.5f * u))
        }
    }
}

/**
 * The battery window as three words from x 24, as the kit's [Selector] draws them (Select 34,
 * 36 apart, the chosen one in the hue over a 4 px underline 12 under its baseline, the rest in
 * sub), and while the wheel is on it ([focused]) the chosen word goes Medium, as a focused
 * row's does, so the one focus on the page stays readable. Kit candidate: `Selector(focused = ...)`.
 */
@Composable
private fun WindowPick(selected: Int, focused: Boolean, r: Ramp, onPick: (Int) -> Unit) {
    Row(Modifier.padding(start = 24.px), horizontalArrangement = Arrangement.spacedBy(36.px)) {
        Window.entries.forEachIndexed { i, win ->
            val on = i == selected
            Text(
                win.label, style = if (on && focused) BeamType.Select.copy(fontWeight = FontWeight.Medium) else BeamType.Select,
                color = if (on) r.hue else r.sub, maxLines = 1, softWrap = false,
                modifier = Modifier
                    .drawBehind {
                        if (on) {
                            val u = 1.px.toPx()
                            drawRoundRect(r.hue, Offset(0f, 46f * u), Size(size.width, 4f * u), CornerRadius(2f * u))
                        }
                    }
                    .tappable { onPick(i) }
                    .paddingFromBaseline(top = 34.px, bottom = 18.px),
            )
        }
    }
}
