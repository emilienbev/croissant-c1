package com.bleural.croissant.ui.beam

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.ServerModels
import com.bleural.croissant.ui.tappable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.Prefs
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Links
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.bluetooth.BluetoothRepo
import com.bleural.croissant.bluetooth.BluetoothUi
import com.bleural.croissant.tailscale.TailscaleRepo
import com.bleural.croissant.tailscale.TailscaleUi
import com.bleural.croissant.tailscale.TsUpgrade
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.SettingsInfo
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.wifiLines
import com.bleural.croissant.wifi.WifiNet
import com.bleural.croissant.wifi.WifiQr
import com.bleural.croissant.wifi.WifiRepo
import com.bleural.croissant.wifi.WifiUi

/*
 * Settings, the first half: the opened list, Wi-Fi and joining a network, Bluetooth, Tailscale and
 * Server, every one in the Settings hue, drawn in the kit (Settings > ask is AskSettings.kt). The
 * pages under the list start as it does, their first row 4 px under the header (y 104), and
 * separate their groups by space and a label, nothing round them. The app's words are lowercase; a
 * network's, a device's or a server's name keeps its case. Server is Beam's own: the server and
 * what it runs.
 */

private val SETTINGS_ROWS = listOf(Screen.Wifi, Screen.Esim, Screen.Tailscale, Screen.Server, Screen.AskSettings, Screen.Usage, Screen.Device, Screen.Update, Screen.Power)

/**
 * Settings: the nine rows from y 74, each a glyph in the hue, its value in sub and a chevron; Power
 * in danger. No group label. A value says what fits: the network's name (its own case) or off, the
 * card's profile, Tailscale's state (up or off, not the address), the server's name, the chat model
 * in use ([ask]), the last 30 days' cost ([usage]), the version ([update]: the installed one in
 * sub, or a newer one in the hue while it is out, downloading or ready); Device and Power have
 * none.
 */
@Composable
internal fun SettingsPage(device: DeviceStatus, info: SettingsInfo, sim: String?, onOpen: (Screen) -> Unit, onBack: () -> Unit,
                          ask: String? = null, usage: String? = null, update: Pair<String, Boolean>? = null) {
    val r = rampOf(Feature.Settings)
    val n = SETTINGS_ROWS.size
    // Lazy item 0 is the gap under the header, so row i is item i + 1 and the gap stays above row 0.
    val rows = rememberRows(n, onBack, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    PageTop("settings", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(8.px)) }
            items(n) { i ->
                val s = SETTINGS_ROWS[i]
                val (glyph, title, value) = when (s) {
                    Screen.Wifi -> Triple(Glyphs.Wifi, "wi-fi", if (device.wifiEnabled) info.ssid else "off")
                    Screen.Esim -> Triple(Glyphs.Sim, "esim", sim?.let(::simWord))
                    Screen.Tailscale -> Triple(Glyphs.Tailnet, "tailscale", if (device.tailnetUp) "up" else "off")
                    Screen.Server -> Triple(Glyphs.Server, "server", info.serverLabel.ifBlank { null })
                    Screen.AskSettings -> Triple(Glyphs.Ask, "ask", ask)
                    Screen.Usage -> Triple(Glyphs.Usage, "usage", usage)
                    Screen.Device -> Triple(Glyphs.Device, "device", null)
                    Screen.Update -> Triple(Glyphs.Download, "update", update?.first)
                    else -> Triple(Glyphs.Power, "power", null)
                }
                ShelfRow(
                    title, r, focused = rows.focus == i, onClick = { rows.focus = i; onOpen(s) },
                    glyph = glyph, value = value, chevron = true, danger = s == Screen.Power, edge = shelfEdge(i, n),
                    valueColour = if (s == Screen.Update && update?.second == true) r.hue else null,
                )
            }
            item { Spacer(Modifier.height(22.px)) }
        }
    }
}

/** The card's summary as the row says it: a profile's name keeps its case, the app's own words go lowercase. */
private fun simWord(s: String): String = if (s == "No profile" || s == "None in use") s.lowercase() else s

/**
 * The Settings pages this file draws, by their [Screen]; every other page under Settings is
 * the second half's (SettingsDevice.kt). Each branch collects from [core] only what its page reads.
 */
@Composable
internal fun SettingsPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.Wifi -> {
            val wifi by core.wifi.state.collectAsStateWithLifecycle()
            WifiPage(core.wifi, wifi, onJoin = { nav.push(Screen.WifiJoin(it)) }, onScan = { nav.push(Screen.WifiScan) }, onBack = nav::pop)
        }
        is Screen.WifiJoin -> WifiJoinPage(screen.net, onJoin = { pw -> Links.touched(); core.wifi.connect(screen.net, pw); nav.pop() },
            onScan = { nav.replace(Screen.WifiScan) })
        Screen.WifiScan -> QrScanPage(
            "wi-fi", rampOf(Feature.Settings), WifiQr::parse, "point at the network's code", "that code is not a wi-fi network",
            knob = Glyphs.Lock, onKnob = nav::pop, onBack = nav::pop,
            onCode = { qr -> core.joinWifi(qr); Cues.play(Sound.Commit); nav.pop() },
        )
        Screen.Bluetooth -> {
            val bt by core.bluetooth.state.collectAsStateWithLifecycle()
            BluetoothPage(core.bluetooth, bt, onBack = nav::pop)
        }
        Screen.Tailscale -> {
            val ts by core.tailscale.state.collectAsStateWithLifecycle()
            TailscalePage(core.tailscale, ts, onBack = nav::pop)
        }
        Screen.Server -> ServerPage(core, nav)
        Screen.ServerWays -> PageTop("server", rampOf(Feature.Settings)) {
            ServerWays(rampOf(Feature.Settings), nav::pop, onScan = { nav.push(Screen.ServerScan) }, onPick = { nav.push(Screen.ServerFind) })
        }
        Screen.ServerScan -> ScanServer(rampOf(Feature.Settings), core, onBack = nav::pop, onKeep = { name, host, port, _ ->
            core.saveServer(Prefs.Server(name, host, port)); nav.drop(Screen.ServerWays); nav.pop(Sound.Commit)
        })
        Screen.ServerFind -> ServerFindPage(core, nav)
        Screen.ServerAddress -> TypeServer(rampOf(Feature.Settings), core, onKeep = { host, port, _ ->
            core.saveServer(Prefs.Server("", host, port)); nav.drop(Screen.ServerFind); nav.drop(Screen.ServerWays); nav.pop(Sound.Commit)
        })
        Screen.AskSettings, is Screen.ModelPick, Screen.ModelTest, Screen.Prompts, Screen.PromptAdd, is Screen.Prompt,
        is Screen.PromptRename, is Screen.PromptEdit, is Screen.PromptWhole, is Screen.TestModel, Screen.TestPrompt, Screen.Testing,
        is Screen.TestGroup, Screen.Providers, Screen.ProviderAdd, is Screen.Provider, is Screen.ProviderKey, is Screen.ProviderAddress,
        Screen.ProviderOwn, is Screen.OnPhone, is Screen.FixedPrompt -> AskPages(screen, core, nav)
        Screen.Usage -> UsagePage(core, nav)
        Screen.Update -> UpdatePage(core, nav)
        Screen.UpdateSource -> UpdateSourcePage(core, nav)
        else -> SettingsDevicePages(screen, core, nav)
    }
}

/** The first row of a page with no label over it: 4 px under the header (y 104), as the Settings list starts. */
private val FIRST_SHELF = 4.px

/** The space between two groups with no label between them. */
internal val SETTINGS_GAP = 24.px

/**
 * Words on the page that are not a row (why something cannot be done, what to do next):
 * [BeamType.Body] from x 24 to 456, wrapping, its first baseline [top] px down from where it
 * starts (32 puts it on the first label's line, y 132, right under the header).
 */
@Composable
internal fun SettingsWords(text: String, colour: androidx.compose.ui.graphics.Color, top: Float = 32f, modifier: Modifier = Modifier) {
    Text(text, style = BeamType.Body, color = colour, modifier = modifier.fillMaxWidth().padding(horizontal = 24.px).paddingFromBaseline(top = top.px))
}

/** A row whose whole job is a switch: its title, what it is doing when that is not what the [Toggle] says ([value]), and the toggle at its end. */
@Composable
private fun ToggleRow(title: String, on: Boolean, r: Ramp, focused: Boolean, edge: ShelfEdge?, value: String? = null, onClick: () -> Unit) {
    ShelfRow(title, r, focused = focused, onClick = onClick, edge = edge, value = value, trailing = { Toggle(on, r) })
}

/**
 * Wi-Fi: the switch at y 104, then the networks by group, connected, saved (out of range)
 * and networks, each under its label with a count (connected has one row and no count). The
 * joined one has the tick; the rest the lock when secured, or their signal. A row's state
 * (connecting, not supported; wrong password and not joined in danger) takes the place of
 * its mark. A tap joins (a secured network asks for its password
 * first); on the joined one or a saved one out of range it asks to Forget, as does a long
 * press on any saved one.
 */
@Composable
internal fun WifiPage(repo: WifiRepo, ui: WifiUi, onJoin: (WifiNet) -> Unit, onScan: () -> Unit, onBack: () -> Unit) {
    DisposableEffect(repo) { repo.start(); onDispose { repo.stop() } }
    val r = rampOf(Feature.Settings)
    var forgetting by remember { mutableStateOf<WifiNet?>(null) }
    val forget = { net: WifiNet -> forgetting = net; Cues.play(Sound.SheetIn) }
    val lines = remember(ui.enabled, ui.networks) { wifiLines(ui) }
    val rowAt = remember(lines) { lines.indices.filter { lines[it] is WifiNet } }
    // Row 0 the switch, row 1 scan a code, then the networks; lazy item 0 holds both of the first.
    val rows = rememberRows(
        2 + rowAt.size, onBack,
        itemOf = { if (it <= 1) 0 else rowAt[it - 2] + 1 },
        topOf = { if (it <= 1) 0 else rowAt[it - 2].let { i -> if (lines[i - 1] is String) i else i + 1 } },
    )
    Box(Modifier.fillMaxSize()) {
        PageTop("wi-fi", r) {
            ShelfList(rows, r) {
                item(key = "toggle") {
                    Column {
                        Spacer(Modifier.height(FIRST_SHELF))
                        ToggleRow("wi-fi", ui.enabled, r, focused = rows.focus == 0, edge = ShelfEdge.Only, onClick = {
                            rows.focus = 0; Links.touched(); repo.setEnabled(!ui.enabled); Cues.play(if (ui.enabled) Sound.SwitchOff else Sound.SwitchOn)
                        })
                        ShelfRow("scan a code", r, focused = rows.focus == 1, glyph = Glyphs.Scan, onClick = { rows.focus = 1; onScan() })
                    }
                }
                items(lines.size, key = { i -> lines[i].let { if (it is String) "g$it" else (it as WifiNet).ssid } }) { i ->
                    when (val line = lines[i]) {
                        is String -> {
                            val count = lines.drop(i + 1).takeWhile { it is WifiNet }.size
                            DotLabel(line.lowercase(), r, count = if (line == "Connected") null else count)
                        }
                        is WifiNet -> {
                            val net = line
                            val at = rowAt.indexOf(i) + 2
                            // Its place in its own group: the rows after its group's label.
                            val start = (i downTo 0).first { lines[it] is String }
                            val size = lines.drop(start + 1).takeWhile { it is WifiNet }.size
                            val failed = ui.failed?.takeIf { it.ssid == net.ssid && !net.connected }
                            val state = when {
                                ui.connecting == net.ssid && !net.connected -> "connecting"
                                failed != null -> null
                                !net.joinable -> "not supported"
                                else -> null
                            }
                            val mark: ImageVector? = when {
                                state != null || failed != null -> null
                                net.connected -> Glyphs.Check
                                net.secured -> Glyphs.Lock
                                net.level == 3 -> Glyphs.Signal3
                                net.level == 2 -> Glyphs.Signal2
                                net.level >= 0 -> Glyphs.Signal1
                                else -> null
                            }
                            ShelfRow(
                                net.ssid, r, focused = rows.focus == at,
                                onClick = {
                                    rows.focus = at
                                    when {
                                        net.connected || (net.saved && net.level < 0) -> forget(net)
                                        net.saved -> { Links.touched(); repo.connect(net); Cues.play(Sound.Commit) }
                                        !net.joinable -> Unit
                                        net.secured -> onJoin(net)
                                        else -> { Links.touched(); repo.connect(net); Cues.play(Sound.Commit) }
                                    }
                                },
                                onLongClick = if (net.saved) ({ forget(net) }) else null,
                                value = state, mark = mark, edge = shelfEdge(i - start - 1, size),
                                trailing = if (failed != null) ({
                                    RowLabel(if (failed.wrongPassword) "wrong password" else "not joined", r.danger)
                                }) else null,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(22.px)) }
            }
        }
        forgetting?.let { net ->
            Sheet(
                r, "forget",
                onCancel = { forgetting = null; Cues.play(Sound.SheetDismiss) },
                onAct = { forgetting = null; Links.touched(); repo.forget(net); Cues.play(Sound.Destroy) },
                colour = DANGER,
            )
        }
    }
}

/**
 * Joining a secured network: the network's name (its own case) as the label over the password
 * [Field], so the page needs no word for "password" and fits over the keyboard; hide and show
 * as a [Selector] under it; the knob to join, lit at eight characters (the keyboard's Go joins
 * too). The keyboard comes up on its own and the knob rides above it; the wheel does nothing
 * here, and back is the edge drag.
 */
@Composable
internal fun WifiJoinPage(net: WifiNet, onJoin: (String) -> Unit, onScan: () -> Unit) {
    val r = rampOf(Feature.Settings)
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val ready = password.length >= 8
    val join = { if (ready) onJoin(password) }
    WheelTarget { false }
    PageTop("wi-fi", r) {
        Box(Modifier.fillMaxSize()) {
            Column {
                DotLabel(net.ssid, r, top = LABEL_FIRST)
                Field(
                    password, { password = it }, r, Modifier.focusRequester(requester), secret = !show,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { join() }),
                )
                Spacer(Modifier.height(26.px))
                Selector(listOf("hide", "show"), if (show) 1 else 0, r, Modifier.padding(start = 24.px)) { i ->
                    val next = i == 1
                    if (next != show) { show = next; Cues.play(if (show) Sound.SwitchOn else Sound.SwitchOff) }
                }
            }
            KnobKey(Glyphs.Check, r, onClick = { join() }, enabled = ready)
            // Or its code: a phone sharing the network, or the router's sticker.
            QuietKey(Glyphs.Scan, r, onClick = onScan, slot = 1)
        }
    }
}

/** Bluetooth, laid out as Wi-Fi: the switch at y 104, then the paired devices (their own names) under "paired", the tick on a connected one. */
@Composable
internal fun BluetoothPage(repo: BluetoothRepo, ui: BluetoothUi, onBack: () -> Unit) {
    DisposableEffect(repo) { repo.start(); onDispose { repo.stop() } }
    val r = rampOf(Feature.Settings)
    val shown = if (ui.enabled) ui.devices else emptyList()
    val rows = rememberRows(1 + shown.size, onBack, itemOf = { if (it == 0) 0 else it + 1 }, topOf = { if (it == 1) 1 else if (it == 0) 0 else it + 1 })
    PageTop("bluetooth", r) {
        ShelfList(rows, r) {
            item(key = "toggle") {
                Column {
                    Spacer(Modifier.height(FIRST_SHELF))
                    ToggleRow("bluetooth", ui.enabled, r, focused = rows.focus == 0, edge = ShelfEdge.Only, onClick = {
                        rows.focus = 0; repo.setEnabled(!ui.enabled); Cues.play(if (ui.enabled) Sound.SwitchOff else Sound.SwitchOn)
                    })
                }
            }
            if (shown.isNotEmpty()) {
                item(key = "paired") { DotLabel("paired", r, count = shown.size) }
                items(shown.size, key = { shown[it].address }) { i ->
                    val d = shown[i]
                    ShelfRow(
                        d.name, r, focused = rows.focus == i + 1, onClick = { rows.focus = i + 1 },
                        mark = if (d.connected) Glyphs.Check else null, edge = shelfEdge(i, shown.size),
                    )
                }
            }
            item { Spacer(Modifier.height(22.px)) }
        }
    }
}

/**
 * Tailscale, from the daemon's own status. Up: the switch at y 104, "connecting" beside it
 * while it is on and not yet online, then the facts (node, address, the server's round trip,
 * unreachable in danger, the relay, the version) as rows with their values; sign out in danger
 * at the bottom, through the sheet. A newer tailscale out, the version row says "update to"
 * it in the hue and a tap upgrades: the row then says each step (downloading with its percent,
 * installing, restarting) and how it ended, a failure in danger, a tap trying again. Signed
 * out: the login URL under "sign in on the Mac" until the login completes there, and sign in
 * at the bottom. Not reachable: those words in danger. The wheel steps the switch, the version
 * row when it can upgrade, and the bottom row; a tick up from the first steps back through
 * the pull.
 */
@Composable
internal fun TailscalePage(repo: TailscaleRepo, ui: TailscaleUi, onBack: () -> Unit) {
    DisposableEffect(repo) { repo.start(); onDispose { repo.stop() } }
    val r = rampOf(Feature.Settings)
    val hasToggle = ui.available == true && !ui.needsLogin
    val hasBottom = ui.available == true
    val canUpgrade = hasToggle && ui.newer && !ui.upgrading
    val count = (if (hasToggle) 1 else 0) + (if (canUpgrade) 1 else 0) + (if (hasBottom) 1 else 0)
    var focus by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    val pull = rememberPull(onBack)
    val pill = rememberPill()
    WheelTarget { delta -> if (delta < 0 && focus == 0) { pull.stepBack(); false } else moved(focus, delta, count - 1) { focus = it } }
    val bottomFocused = hasBottom && focus == count - 1

    Box(Modifier.fillMaxSize()) {
        PageTop("tailscale", r) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = maxHeight
                ProvidePill(pill) {
                    Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().height(h)) {
                            Column(Modifier.fillMaxWidth()) {
                                when {
                                    ui.available == null -> Unit
                                    ui.available == false -> SettingsWords("not reachable", r.danger)
                                    ui.needsLogin -> ui.authUrl?.let { LoginCode(it) } ?: SettingsWords("signed out", r.sub)
                                    else -> {
                                        Spacer(Modifier.height(FIRST_SHELF))
                                        Shelf(r) {
                                            ToggleRow("tailscale", ui.running, r, focused = focus == 0, edge = null,
                                                value = if (ui.running && !ui.online) "connecting" else null, onClick = {
                                                    focus = 0
                                                    if (!ui.busy) { Links.touched(); repo.setUp(!ui.running); Cues.play(if (ui.running) Sound.SwitchOff else Sound.SwitchOn) }
                                                })
                                        }
                                        val unreachable = ui.running && ui.serverMs == null
                                        val facts = listOf(
                                            "node" to ui.hostname,
                                            "address" to ui.ip,
                                            "server" to (if (ui.running) ui.serverMs?.let { "$it ms" } ?: "" else null),
                                            "relay" to ui.relay,
                                            "version" to versionWords(ui),
                                        ).filter { it.second != null }
                                        if (facts.isNotEmpty()) {
                                            Spacer(Modifier.height(SETTINGS_GAP))
                                            Shelf(r) {
                                                facts.forEach { (k, v) ->
                                                    when {
                                                        k == "server" && unreachable -> ShelfRow(k, r, trailing = { RowLabel("unreachable", r.danger) })
                                                        k == "version" -> ShelfRow(
                                                            k, r, value = v, focused = canUpgrade && focus == 1,
                                                            valueColour = when (ui.upgrade) {
                                                                TsUpgrade.NotDownloaded, TsUpgrade.NotStarted -> r.danger
                                                                TsUpgrade.Updated -> null
                                                                else -> if (ui.upgrading || ui.newer) r.hue else null
                                                            },
                                                            onClick = if (canUpgrade) { { focus = 1; Links.touched(); repo.upgrade(); Cues.play(Sound.Commit) } } else null,
                                                        )
                                                        else -> ShelfRow(k, r, value = v)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (hasBottom) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 16.px)) {
                                Shelf(r) {
                                    if (ui.needsLogin) ShelfRow("sign in", r, focused = bottomFocused, onClick = {
                                        focus = count - 1
                                        if (!ui.busy) { Links.touched(); repo.setUp(true); Cues.play(Sound.Commit) }
                                    })
                                    else ShelfRow("sign out", r, focused = bottomFocused, danger = true, onClick = {
                                        focus = count - 1
                                        if (!ui.busy) { asking = true; Cues.play(Sound.SheetIn) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (asking) Sheet(
            r, "sign out",
            onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
            onAct = { asking = false; Links.touched(); repo.logout(); Cues.play(Sound.Destroy) },
            colour = DANGER,
        )
    }
}

/** The version row's value: the running version, a newer one to update to, an upgrade's step or how it ended. */
private fun versionWords(ui: TailscaleUi): String? = when (ui.upgrade) {
    TsUpgrade.Downloading -> "downloading ${ui.percent}%"
    TsUpgrade.Installing -> "installing"
    TsUpgrade.Restarting -> "restarting"
    TsUpgrade.Updated -> "updated to ${ui.version}"
    TsUpgrade.NotDownloaded -> "download failed"
    TsUpgrade.NotStarted -> "failed, kept ${ui.version}"
    null -> if (ui.newer) "update to ${ui.latest}" else ui.version
}

// ---- server ----------------------------------------------------------------------------

/** A slot as the R1 shows it: its word, the server's own page's too, and its glyph. */
internal data class SlotLook(val slot: ModelSlot, val word: String, val glyph: ImageVector)

/** What the server runs, in Settings > ask's order; Usage and the model picker use the same words and glyphs. */
internal val SLOTS = listOf(
    SlotLook(ModelSlot.Chat, "chat", Glyphs.Chat),
    SlotLook(ModelSlot.Stt, "speech", Glyphs.Ask),
    SlotLook(ModelSlot.TextToImage, "pictures", Glyphs.Generated),
    SlotLook(ModelSlot.ImageToImage, "transform", Glyphs.Photos),
)

/** [slot]'s word. */
internal fun wordOf(slot: ModelSlot): String = SLOTS.first { it.slot == slot }.word

/**
 * Server: the helper, as a card in the hue, its name and how fast it answers (a tap offers another:
 * scan its code or pick a tailnet device); then under "it runs" what runs on its machine, a row a
 * job: its speech models, its picture models (the transform's among them, said once). No chevrons,
 * nothing to pick here: the picks are Settings > ask's (AskSettings.kt). The server is asked each
 * time the page opens. No answer: the card red with the address, and nothing else.
 */
@Composable
internal fun ServerPage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    val server = core.server
    var ms by remember(server) { mutableStateOf<Long?>(null) }
    var away by remember(server) { mutableStateOf(false) }
    LaunchedEffect(server) {
        ms = if (server.set) core.checkServer(server.host, server.port) else null
        away = ms == null
        if (ms != null) core.refreshModels()
    }
    val runs = if (ms == null) emptyList() else macRuns(core.models)
    val rows = rememberRows(0, nav::pop, itemOf = { it + 1 })
    PageTop("server", r) {
        ShelfList(rows, r) {
            item {
                Column {
                    ServerCard(server, ms, away, r) { nav.push(Screen.ServerWays) }
                    Spacer(Modifier.height(SETTINGS_GAP))
                }
            }
            if (runs.isNotEmpty()) item { DotLabel("it runs", r) }
            items(runs.size) { i ->
                val (look, names) = runs[i]
                ShelfRow(look.word, r, glyph = look.glyph, value = names)
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** What the server's machine runs, as [models] lists it: speech's, then the pictures' and the transform's together, a row a job. */
private fun macRuns(models: ServerModels?): List<Pair<SlotLook, String>> {
    fun local(vararg slots: ModelSlot) = slots.flatMap { s -> models?.list(s)?.entries.orEmpty().filter { it.local }.map { it.label } }.distinct()
    fun look(slot: ModelSlot) = SLOTS.first { it.slot == slot }
    return listOf(look(ModelSlot.Stt) to local(ModelSlot.Stt), look(ModelSlot.TextToImage) to local(ModelSlot.TextToImage, ModelSlot.ImageToImage))
        .filter { it.second.isNotEmpty() }.map { (look, names) -> look to names.joinToString(", ") }
}

/**
 * The server's card: 448 by 128 from y 74, its name (or address) in black on the hue and "answers
 * in [ms] ms" under it; [away], "no answer" and the address on red. Tapped, [onClick]: another
 * server.
 */
@Composable
private fun ServerCard(server: Prefs.Server, ms: Long?, away: Boolean, r: Ramp, onClick: () -> Unit) {
    val fill by animateColorAsState(if (away) DANGER else r.hue, tween(300), label = "serverCard")
    val (top, under) = when {
        away -> "no answer" to server.host
        else -> server.name.ifBlank { server.host } to ms?.let { "answers in $it ms" }
    }
    Box(
        Modifier.padding(start = 16.px, end = 16.px, top = 8.px).fillMaxWidth().height(128.px)
            .clip(RoundedCornerShape(16.px)).background(fill).tappable(onClick),
    ) {
        Text(top, style = BeamType.Second, color = GROUND, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.px, end = 20.px).paddingFromBaseline(top = 58.px))
        if (under != null) Text(under, style = BeamType.Value, color = GROUND, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.px, end = 20.px).paddingFromBaseline(top = 104.px))
    }
}

/**
 * Another server: the tailnet asked as the setup does, the ones that answer first with how fast,
 * the one in use ticked; a tap uses it and goes back to the server page. Type an address is the
 * setup's page.
 */
@Composable
internal fun ServerFindPage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    val search by core.servers.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { core.servers.search() }
    DisposableEffect(Unit) { onDispose { core.servers.stop() } }
    PageTop("server", r) {
        ServerList(
            search, r, nav::pop, value = { "${it.ms} ms" }, ticked = { it.peer.ip == core.server.host },
            onTap = { c -> core.saveServer(Prefs.Server(c.peer.name, c.peer.ip, Prefs.DEFAULT_PORT)); nav.drop(Screen.ServerWays); nav.pop(Sound.Commit) },
            onType = { nav.push(Screen.ServerAddress) }, foot = 24.px,
        )
    }
}
