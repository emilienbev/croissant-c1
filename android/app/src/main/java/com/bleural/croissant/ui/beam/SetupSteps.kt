package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.Prefs
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Links
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.send.encode
import com.bleural.croissant.setup.Candidate
import com.bleural.croissant.setup.ServerQr
import com.bleural.croissant.setup.ServerSearch
import com.bleural.croissant.setup.SetupUi
import com.bleural.croissant.setup.Step
import com.bleural.croissant.setup.Zones
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.QrScanner
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.rememberQrScanner
import com.bleural.croissant.wifi.WifiNet
import com.bleural.croissant.wifi.WifiQr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How long the success beat holds before the flood. */
private const val BEAT_MS = 1_100L
private const val ASK_UP_EVERY_MS = 10_000L
/** How long the server's scan says "no answer" before it reads again. */
private const val NO_ANSWER_MS = 3_000L

/**
 * How far down the content the Tailscale step puts its [LoginCode]: the card and its two lines
 * (y 10 to about 366 in the card) centred between the header (66) and the foot (566), so the
 * card runs from page y 138. Settings keeps it at the top.
 */
private const val LOGIN_TOP = 62f

/** Room under a step's list for its foot. */
private val FOOT_CLEAR = 120.px

// ---- wi-fi ------------------------------------------------------------------------------

/**
 * Wi-Fi: scan a code first (a Wi-Fi QR code carries the network and its password), then the
 * networks in range, the joined one ticked. A secured one asks for its password, or its code from
 * the quiet key beside the join knob. Joined, the beat, then the next step.
 */
@Composable
internal fun WifiStep(core: Core, device: DeviceStatus, setup: SetupUi) {
    val r = stepRamp(Step.Wifi)
    val ui by core.wifi.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { core.wifi.start(); onDispose { core.wifi.stop() } }
    var joining by remember { mutableStateOf<WifiNet?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var beat by remember { mutableStateOf<String?>(null) }
    val joined = ui.networks.firstOrNull { it.connected }?.ssid?.takeIf { device.wifiConnected }
    LaunchedEffect(joined, asked) {
        if (!asked || joined == null) return@LaunchedEffect
        joining = null; scanning = false
        beat = joined
        delay(BEAT_MS)
        core.setupNext()
    }
    beat?.let { Beat(Step.Wifi, setup, it, "joined"); return }
    when {
        scanning -> {
            SetupBackTarget { scanning = false; Cues.play(Sound.ScreenOut) }
            QrScanPage(
                "wi-fi", r, WifiQr::parse, "point at the network's code", "that code is not a wi-fi network",
                knob = Glyphs.Lock, onKnob = { scanning = false; Cues.play(Sound.ScreenIn) },
                onBack = { scanning = false },
                onCode = { qr -> core.joinWifi(qr); asked = true; scanning = false; joining = null; Cues.play(Sound.Commit) },
            )
        }
        joining != null -> {
            val net = joining!!
            SetupBackTarget { joining = null; Cues.play(Sound.ScreenOut) }
            WifiJoinPage(net, onJoin = { pw -> Links.touched(); core.wifi.connect(net, pw); asked = true; joining = null }, onScan = { scanning = true; Cues.play(Sound.ScreenIn) })
        }
        else -> {
            val nets = ui.networks.filter { it.level >= 0 || it.connected }
            val rows = rememberRows(1 + nets.size, core::setupBack, itemOf = { if (it == 0) 0 else it + 1 }, topOf = { if (it == 0) 0 else if (it == 1) 1 else it + 1 })
            SetupStepPage(Step.Wifi, setup, if (joined != null) "next" else "skip", { core.setupNext(skip = joined == null) }, list = true) {
                ShelfList(rows, r) {
                    item(key = "scan") {
                        Column {
                            Spacer(Modifier.height(4.px))
                            ShelfRow("scan a code", r, focused = rows.focus == 0, glyph = Glyphs.Scan, onClick = { rows.focus = 0; scanning = true; Cues.play(Sound.ScreenIn) })
                        }
                    }
                    item(key = "label") { DotLabel("networks", r, count = nets.size) }
                    items(nets.size, key = { nets[it].ssid }) { i ->
                        val net = nets[i]
                        val failed = ui.failed?.takeIf { it.ssid == net.ssid && !net.connected }
                        val state = when {
                            ui.connecting == net.ssid && !net.connected -> "connecting"
                            failed != null -> null
                            !net.joinable -> "not supported"
                            else -> null
                        }
                        ShelfRow(
                            net.ssid, r, focused = rows.focus == i + 1,
                            onClick = {
                                rows.focus = i + 1
                                when {
                                    net.connected || !net.joinable -> Unit
                                    net.saved || !net.secured -> { Links.touched(); core.wifi.connect(net); asked = true; Cues.play(Sound.Commit) }
                                    else -> { joining = net; Cues.play(Sound.ScreenIn) }
                                }
                            },
                            value = state,
                            mark = when {
                                state != null || failed != null -> null
                                net.connected -> Glyphs.Check
                                net.secured -> Glyphs.Lock
                                net.level == 3 -> Glyphs.Signal3
                                net.level == 2 -> Glyphs.Signal2
                                else -> Glyphs.Signal1
                            },
                            trailing = if (failed != null) ({ RowLabel(if (failed.wrongPassword) "wrong password" else "not joined", r.danger) }) else null,
                        )
                    }
                    item { Spacer(Modifier.height(FOOT_CLEAR)) }
                }
            }
        }
    }
}

/**
 * The viewfinder, as the eSIM's: the camera from y 104, radius 14, what to do under it (in danger
 * for a code that is not one), the knob to go back the other way.
 */
@Composable
internal fun <T : Any> QrScanPage(
    word: String, r: Ramp, parse: (String) -> T?, point: String, notOne: String,
    knob: androidx.compose.ui.graphics.vector.ImageVector, onKnob: () -> Unit, onBack: () -> Unit, onCode: (T) -> Unit,
) = QrScanPage(word, r, rememberQrScanner(parse, onCode), point, notOne, onBack, knob, onKnob)

/** The viewfinder on a [scanner] of the caller's: [status], when there is one, says what became of the code it read; no [knob], no key. */
@Composable
internal fun QrScanPage(
    word: String, r: Ramp, scanner: QrScanner, point: String, notOne: String, onBack: () -> Unit,
    knob: androidx.compose.ui.graphics.vector.ImageVector? = null, onKnob: () -> Unit = {}, status: Pair<String, androidx.compose.ui.graphics.Color>? = null,
) {
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    val (words, colour) = when {
        scanner.opened == false -> "the camera could not be opened" to r.danger
        scanner.opened == null -> "starting the camera" to r.sub
        status != null -> status
        scanner.notACode -> notOne to r.danger
        else -> point to r.ink
    }
    PageTop(word, r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(start = 16.px, end = 16.px, top = 4.px).fillMaxWidth().height(330.px).clip(RoundedCornerShape(14.px)).background(r.deep)) {
                if (scanner.opened != false) AndroidView(factory = { scanner.view }, modifier = Modifier.fillMaxSize())
            }
            Text(words, style = BeamType.Body, color = colour, maxLines = 2,
                modifier = Modifier.padding(start = 24.px, end = 120.px).paddingFromBaseline(top = 50.px))
            Spacer(Modifier.height(112.px))
        }
        if (knob != null) QuietKey(knob, r, onClick = onKnob)
    }
}

// ---- tailscale --------------------------------------------------------------------------

/**
 * Tailscale: signed out, the node comes up on its own with the new node's flags and waits for the
 * login; its address shows as a QR code for a phone and as words to read off. Online, the beat with
 * the node's name and address, then the next step.
 */
@Composable
internal fun TailscaleStep(core: Core, setup: SetupUi) {
    val r = stepRamp(Step.Tailscale)
    val ts by core.tailscale.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { core.tailscale.start(); onDispose { core.tailscale.stop() } }
    val upAlready = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ts.available, ts.backend) {
        if (ts.available == true && upAlready.value == null) upAlready.value = ts.running && ts.online
    }
    // Asked again while there is no login address: an ask made before Wi-Fi is up goes to a
    // daemon the boot script restarts once the route is in, and is lost with it.
    LaunchedEffect(Unit) {
        core.tailscale.state.first { it.available == true }
        while (true) {
            val s = core.tailscale.state.value
            if (s.available == true && !s.running && !s.busy && s.authUrl == null) { Links.touched(); core.tailscale.setUp(true) }
            delay(ASK_UP_EVERY_MS)
        }
    }
    val online = ts.running && ts.online
    var beat by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online || upAlready.value != false) return@LaunchedEffect
        beat = true
        delay(BEAT_MS)
        core.setupNext()
    }
    if (online) {
        Beat(Step.Tailscale, setup, ts.hostname ?: "tailscale", ts.ip, foot = if (beat) null else "next", onFoot = { core.setupNext() }, onBack = if (beat) null else core::setupBack)
        return
    }
    SetupStepPage(Step.Tailscale, setup, "skip", { core.setupNext(skip = true) }) {
        StillPull(core::setupBack, r) {
            when {
                ts.available == false -> SettingsWords("not reachable", r.danger)
                ts.needsLogin && ts.authUrl != null -> Box(Modifier.padding(top = LOGIN_TOP.px)) { LoginCode(ts.authUrl!!) }
                else -> SettingsWords("starting", r.sub)
            }
        }
    }
}

/**
 * The login (Settings' signed-out Tailscale too): the address as a [QrCard], split
 * after `/a/`.
 */
@Composable
internal fun LoginCode(url: String) {
    val bare = url.removePrefix("https://")
    val cut = bare.indexOf("/a/").takeIf { it >= 0 }?.plus(3) ?: (bare.length / 2)
    QrCard(url, listOf(bare.take(cut), bare.drop(cut)).filter { it.isNotEmpty() })
}

/**
 * An address as a QR code on a white card 260 across at (110, 110), any phone camera reads
 * dark on light, and as [lines] of words under it in white; [notes] under those in sub, 42
 * apart from 74 under the last line. Drawn in the page's coordinates from y 100. [spent]: a
 * code that is gone (the page on a phone, opened), the card faint with that glyph (120) on it
 * in [hue].
 */
@Composable
internal fun QrCard(url: String, lines: List<String>, notes: List<String> = emptyList(), spent: Painter? = null, hue: Color = INK) {
    val code = remember(url) { if (spent == null) encode(url) else null }
    val m = rememberTextMeasurer(cacheSize = 4)
    val first = 320f + (lines.size - 1) * 38f + 74f
    Canvas(Modifier.fillMaxWidth().height((if (notes.isEmpty()) 400f else first + (notes.size - 1) * 42f + 24f).px)) {
        pageScale {
            drawRoundRect(if (spent != null) FAINT else INK, Offset(110f, 10f), Size(260f, 260f), CornerRadius(18f))
            if (spent != null) glyph(spent, 180f, 80f, 120f, hue)
            if (code != null) {
                val cell = 220f / code.side
                for (y in 0 until code.side) for (x in 0 until code.side) if (code[x, y]) {
                    drawRect(GROUND, Offset(130f + x * cell, 30f + y * cell), Size(cell + 0.4f, cell + 0.4f))
                }
            }
            lines.forEachIndexed { i, l -> text(m, 240f, 320f + i * 38f, fit(m, l, BeamType.Value, 440f), BeamType.Value, INK, Anchor.Middle) }
            notes.forEachIndexed { i, l -> text(m, 240f, first + i * 42f, fit(m, l, BeamType.Body, 440f), BeamType.Body, SUB, Anchor.Middle) }
        }
    }
}

// ---- server -----------------------------------------------------------------------------

/**
 * Server: two ways, the code croissant-server prints at its start or a device on the tailnet. The
 * code is read with the Wi-Fi step's viewfinder and asked before it is kept. The tailnet: every
 * peer asked at once on 8765 by its address (from the step's start); the ones that answer first
 * under "answers", the rest dim under "asking the tailnet" and then "no answer"; type an address at
 * the foot. Either keeps it and says how fast it answered.
 */
@Composable
internal fun ServerStep(core: Core, setup: SetupUi) {
    val r = stepRamp(Step.Server)
    val search by core.servers.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { core.servers.search() }
    DisposableEffect(Unit) { onDispose { core.servers.stop() } }
    var scanning by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var beat by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val keep = { name: String, host: String, port: Int, ms: Long ->
        core.saveServer(Prefs.Server(name, host, port))
        Cues.play(Sound.Commit)
        beat = name.ifBlank { host } to "answers in $ms ms"
        scope.launch { delay(BEAT_MS); core.setupNext() }
        Unit
    }
    beat?.let { (w, f) -> Beat(Step.Server, setup, w, f); return }
    val word = if (core.server.set) "next" else "skip"
    val onWord = { core.setupNext(skip = !core.server.set) }
    when {
        scanning -> {
            SetupBackTarget { scanning = false; Cues.play(Sound.ScreenOut) }
            ScanServer(r, core, onBack = { scanning = false }, onKeep = keep)
        }
        typing -> {
            SetupBackTarget { typing = false; Cues.play(Sound.ScreenOut) }
            TypeServer(r, core, onKeep = { host, port, ms -> keep("", host, port, ms) })
        }
        picking -> {
            SetupBackTarget { picking = false; Cues.play(Sound.ScreenOut) }
            val tap = { c: Candidate -> c.ms?.let { keep(c.peer.name, c.peer.ip, Prefs.DEFAULT_PORT, it) }; Unit }
            SetupStepPage(Step.Server, setup, word, onWord, list = true) {
                ServerList(search, r, { picking = false }, value = { it.peer.ip }, ticked = { false }, onTap = tap,
                    onType = { typing = true; Cues.play(Sound.ScreenIn) }, foot = FOOT_CLEAR)
            }
        }
        else -> SetupStepPage(Step.Server, setup, word, onWord, list = true) {
            ServerWays(r, core::setupBack, onScan = { scanning = true; Cues.play(Sound.ScreenIn) }, onPick = { picking = true; Cues.play(Sound.ScreenIn) })
        }
    }
}

/** The two ways to a server (the setup's Server step, and Settings' too): scan its code, or pick it on the tailnet. */
@Composable
internal fun ServerWays(r: Ramp, onBack: () -> Unit, onScan: () -> Unit, onPick: () -> Unit) {
    val rows = rememberRows(2, onBack)
    ShelfList(rows, r) {
        item(key = "scan") {
            Column {
                Spacer(Modifier.height(4.px))
                ShelfRow("scan a QR code", r, focused = rows.focus == 0, glyph = Glyphs.Scan, onClick = { rows.focus = 0; onScan() })
            }
        }
        item(key = "pick") {
            ShelfRow("pick tailnet device", r, focused = rows.focus == 1, glyph = Glyphs.Tailnet, onClick = { rows.focus = 1; onPick() })
        }
    }
}

/**
 * Scanning the server's code (the setup's, and Settings' too): the viewfinder reads the code
 * croissant-server prints at its start, asks it as a typed address is asked, and keeps it with
 * how fast it answered. One that does not answer says so a moment, then the scanner reads again.
 */
@Composable
internal fun ScanServer(r: Ramp, core: Core, onBack: () -> Unit, onKeep: (String, String, Int, Long) -> Unit) {
    var read by remember { mutableStateOf<ServerQr?>(null) }
    var none by remember { mutableStateOf(false) }
    val scanner = rememberQrScanner(ServerQr::parse) { read = it }
    LaunchedEffect(read) {
        val q = read ?: return@LaunchedEffect
        val ms = core.checkServer(q.host, q.port)
        if (ms != null) { onKeep(q.name, q.host, q.port, ms); return@LaunchedEffect }
        none = true
        Cues.play(Sound.ExchangeFailed)
        delay(NO_ANSWER_MS)
        none = false
        scanner.readAgain()
        read = null
    }
    val status = when {
        none -> "no answer" to r.danger
        read != null -> "asking" to r.sub
        else -> null
    }
    QrScanPage("server", r, scanner, "point at the server's code", "that code is not a server", onBack, status = status)
}

/**
 * The tailnet's servers as rows (Settings' too): the ones that answer first under "answers", the
 * rest dim under "asking the tailnet" and then "no answer"; type an address at the foot. Each row
 * says its [value]; [ticked] is the one in use.
 */
@Composable
internal fun ServerList(
    search: ServerSearch, r: Ramp, onBack: () -> Unit, value: (Candidate) -> String, ticked: (Candidate) -> Boolean,
    onTap: (Candidate) -> Unit, onType: () -> Unit, foot: androidx.compose.ui.unit.Dp,
) {
    val all = search.peers.orEmpty()
    val found = all.filter { it.ms != null }
    val rest = all.filter { it.ms == null }
    val count = found.size + rest.size + 1
    val rows = rememberRows(count, onBack, itemOf = { it + 2 }, topOf = { it + 1 })
    ShelfList(rows, r) {
        item(key = "found") { if (found.isNotEmpty()) DotLabel("answers", r, count = found.size, top = LABEL_FIRST) else Spacer(Modifier.height(4.px)) }
        items(found.size, key = { "f" + found[it].peer.ip }) { i ->
            val c = found[i]
            ShelfRow(c.peer.name, r, focused = rows.focus == i, value = value(c), mark = if (ticked(c)) Glyphs.Check else null,
                onClick = { rows.focus = i; onTap(c) })
        }
        item(key = "rest") {
            if (rest.isNotEmpty()) DotLabel(if (search.done) "no answer" else "asking the tailnet", r, count = if (search.done) rest.size else null,
                top = if (found.isEmpty()) LABEL_FIRST else LABEL_ABOVE)
            else if (search.peers == null) DotLabel("asking the tailnet", r, top = LABEL_FIRST)
        }
        items(rest.size, key = { "r" + rest[it].peer.ip }) { i ->
            val c = rest[i]
            ShelfRow(c.peer.name, r, focused = rows.focus == found.size + i, dim = true, value = c.peer.ip, onClick = { rows.focus = found.size + i })
        }
        item(key = "type") {
            Column {
                Spacer(Modifier.height(SETTINGS_GAP))
                ShelfRow("type an address", r, focused = rows.focus == count - 1, glyph = Glyphs.Plus, onClick = { rows.focus = count - 1; onType() })
            }
        }
        item { Spacer(Modifier.height(foot)) }
    }
}

/** Typing a server (Settings' too): the address and the port (8765 there already) over the keyboard; the knob asks it before keeping it. */
@Composable
internal fun TypeServer(r: Ramp, core: Core, onKeep: (String, Int, Long) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Prefs.DEFAULT_PORT.toString()) }
    var asking by remember { mutableStateOf(false) }
    var none by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    WheelTarget { false }
    val ready = host.isNotBlank() && port.toIntOrNull() in 1..65535 && !asking
    val ask = {
        if (ready) {
            asking = true; none = false
            scope.launch {
                val ms = core.checkServer(host.trim(), port.toInt())
                asking = false
                if (ms != null) onKeep(host.trim(), port.toInt(), ms) else { none = true; Cues.play(Sound.ExchangeFailed) }
            }
        }
    }
    PageTop("server", r) {
        Box(Modifier.fillMaxSize()) {
            Column {
                DotLabel("address", r, top = LABEL_FIRST)
                Field(host, { host = it.trim(); none = false }, r, Modifier.focusRequester(requester),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { ask() }))
                DotLabel("port", r)
                Field(port, { port = it.filter(Char::isDigit).take(5); none = false }, r, Modifier.padding(end = 116.px),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { ask() }))
                if (none || asking) Text(if (asking) "asking" else "no answer", style = BeamType.Value, color = if (asking) r.sub else r.danger,
                    modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = 40.px))
            }
            KnobKey(Glyphs.Check, r, onClick = { ask() }, enabled = ready)
        }
    }
}

// ---- time -------------------------------------------------------------------------------

/**
 * Time: the date and the clock as Home has them, in the zone the R1 is in (it found it from the
 * mobile network); the zone a row that opens the list; the knob keeps it and ends the setup, skip
 * ends it leaving the time for Settings.
 */
@Composable
internal fun TimeStep(core: Core, setup: SetupUi) {
    val r = stepRamp(Step.Time)
    var picking by remember { mutableStateOf(false) }
    var zone by remember { mutableStateOf(core.timeZone()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000L - System.currentTimeMillis() % 1_000L); now = System.currentTimeMillis() } }
    if (picking) {
        SetupBackTarget { picking = false; Cues.play(Sound.ScreenOut) }
        PickZone(r, now, onBack = { picking = false }, onPick = { id ->
            if (core.saveTimeZone(id)) zone = Zones.of(id)
            picking = false
            Cues.play(Sound.Commit)
        })
        return
    }
    val tz = TimeZone.getTimeZone(zone.id)
    val date = SimpleDateFormat("EEE d MMM", Locale.UK).apply { timeZone = tz }.format(Date(now)).lowercase()
    val time = SimpleDateFormat("HH:mm", Locale.UK).apply { timeZone = tz }.format(Date(now))
    val m = rememberTextMeasurer(cacheSize = 4)
    SetupStepPage(Step.Time, setup, "skip", { core.setupNext(skip = true) }, over = { KnobKey(Glyphs.Check, r, onClick = { Cues.play(Sound.Commit); core.setupNext() }) }) {
        StillPull(core::setupBack, r) {
            Canvas(Modifier.fillMaxWidth().height(230.px)) {
                pageScale {
                    text(m, 240f, 80f, date, BeamType.Value, SUB, Anchor.Middle)
                    text(m, 240f, 200f, time, BeamType.Big, INK, Anchor.Middle)
                }
            }
            Column(Modifier.padding(top = 240.px)) {
                ShelfRow(zone.city, r, focused = true, value = zone.offset, chevron = true, onClick = { picking = true; Cues.play(Sound.ScreenIn) })
            }
        }
    }
}

/** The zones by city: the letters typed, the cities that match with the time there now. */
@Composable
private fun PickZone(r: Ramp, now: Long, onBack: () -> Unit, onPick: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val zones = remember(typed) { Zones.search(typed).take(40) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { requester.requestFocus() }
    val rows = rememberRows(zones.size, onBack, itemOf = { it + 1 })
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    PageTop("time", r) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(10.px))
            Field(typed, { typed = it; rows.focus = 0 }, r, Modifier.focusRequester(requester), placeholder = "a city",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            ShelfList(rows, r) {
                item { Spacer(Modifier.height(10.px)) }
                items(zones.size, key = { zones[it].id }) { i ->
                    val z = zones[i]
                    val t = clock.apply { timeZone = TimeZone.getTimeZone(z.id) }.format(Date(now))
                    ShelfRow(z.city, r, focused = rows.focus == i, value = t, onClick = { onPick(z.id) })
                }
                item { Spacer(Modifier.height(24.px)) }
            }
        }
    }
}
