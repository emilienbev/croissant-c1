package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.ControlState
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.HomeLayer
import com.bleural.croissant.ui.KeyboardState
import com.bleural.croissant.ui.LocalLayerVisible
import com.bleural.croissant.ui.LocalSheet
import com.bleural.croissant.ui.METRONOME_PAGES
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.OpenedLayer
import com.bleural.croissant.ui.OwnKeyboard
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Place
import com.bleural.croissant.ui.RoomLine
import com.bleural.croissant.ui.RoomLoop
import com.bleural.croissant.ui.RoomState
import com.bleural.croissant.ui.RoomWheel
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.SheetState
import com.bleural.croissant.ui.controlEdge
import com.bleural.croissant.ui.feature
import com.bleural.croissant.ui.Slides
import com.bleural.croissant.ui.tapAwayClosesKeys
import com.bleural.croissant.ui.panelDeps
import com.bleural.croissant.ui.power
import com.bleural.croissant.ui.rememberRoomLines
import com.bleural.croissant.ui.roomGestures

/**
 * The whole of Beam: the stack with the room at its root and the pages over it
 * (Pages.kt), all on the one black ground and on the whole canvas, with no top bar
 * (2026-10-01: the state it showed is in the control centre); and what hangs over everything:
 * the control centre, pulled from the top edge, and the sheet (ControlCentre.kt), in the ramp
 * of what is under them, and a ringing timer, the screen flooded in the timers' hue
 * (Timers.kt).
 */
@Composable
internal fun BeamShell(core: Core, nav: NavState, room: RoomState, home: HomeState) {
    val panels = panelDeps(core, nav, closePanel = room::closePanel)
    val device = panels.device
    val ringing by core.ringing.collectAsStateWithLifecycle()
    // Leaving Ask ends the exchange, however it was left.
    LaunchedEffect(room) {
        snapshotFlow { room.openTarget > 0.5f && room.last == Feature.Ask }
            .collect { onAsk -> if (!onAsk) core.leaveAsk() }
    }
    // The metronome plays only on its own screen and the pages it opens that are not the
    // tuner (METRONOME_PAGES): anything else in front stops it. The screen going dark is not a leave.
    LaunchedEffect(room) {
        snapshotFlow { room.openTarget > 0.5f && room.last == Feature.Metronome && nav.current in METRONOME_PAGES }
            .collect { on -> if (!on) core.metronome.stop() }
    }
    // A hold translates only while the Translator's own screen is in view (Core.translator).
    LaunchedEffect(room) {
        snapshotFlow { room.openTarget > 0.5f && room.last == Feature.Translator && nav.current == Screen.Home }
            .collect { core.translator(it) }
    }
    // The Agents page (Agents.kt): the bridge's socket is open while the page or one of its
    // pages is in front, and closed when they go.
    LaunchedEffect(room) {
        snapshotFlow {
            (room.last == Feature.Agents && room.openTarget > 0.5f && room.homeTarget < 0.5f && nav.current == Screen.Home) ||
                nav.current.feature == Feature.Agents
        }.collect { core.agents.page(it) }
    }
    // A session's page (AgentChat.kt): open on the bridge while it is in front, a hold there its next prompt.
    LaunchedEffect(room) {
        snapshotFlow { nav.current }.collect { s ->
            when (s) {
                is Screen.AgentChat -> core.agentChat(s.agent, s.session, s.cwd)
                is Screen.AgentNew -> core.agentChat(s.agent, null, s.cwd)
                else -> core.agentChat(null, null, null)
            }
        }
    }
    // An Atelier app runs while its page is the one in front (AtelierApp.kt); Core starts and
    // stops it, and a hold there brings it the words when its page takes them. Its page coming
    // up, from the pegboard, the wheel or Ask, is the app opened, for the recents (2026-10-17).
    LaunchedEffect(room) {
        snapshotFlow { (nav.current as? Screen.AtelierApp)?.id }.collect { id ->
            core.atelierApp(id)
            if (id != null) core.opened(Place.App(id).key)
        }
    }
    // An agent's sessions page coming up, from the Agents page or the wheel, is the agent opened, for the recents.
    LaunchedEffect(room) {
        snapshotFlow { (nav.current as? Screen.AgentSessions)?.agent }.collect { id -> if (id != null) core.opened(Place.Agent(id).key) }
    }
    // A feature opened is first among the recents; Ask never counts, it is right under them.
    LaunchedEffect(room) {
        snapshotFlow { room.lastPlace.takeIf { room.openTarget > 0.5f } }
            .collect { p -> if (p != null && p.feature != Feature.Ask) core.opened(p.key) }
    }
    // An app's place on the wheel opens its page, as a tap on its peg does (Atelier.kt), the
    // room left on its place under it so back comes to it; one turned off opens its info. An
    // agent's opens its sessions, as its card on the Agents page does.
    LaunchedEffect(room) {
        room.opens = { p ->
            when (p) {
                is Place.App -> {
                    val on = core.atelier.apps.value.firstOrNull { it.id == p.id }?.enabled == true
                    nav.push(if (on) Screen.AtelierApp(p.id) else Screen.AtelierInfo(p.id), sound = null)
                    true
                }
                is Place.Agent -> { nav.push(Screen.AgentSessions(p.id), sound = null); true }
                else -> false
            }
        }
    }
    // The room's places (Places.kt, 2026-10-17): laid out again on Home, arrived, and at the
    // start, with the recents as they are then, so the room under a hand never shifts. Away from
    // Home a pin or an app going or coming re-lays all but the recents, which stay as laid.
    LaunchedEffect(room) {
        val onHome = snapshotFlow { room.homeTarget > 0.5f && room.home > 0.99f }
        combine(onHome, core.recents, core.pins, core.atelier.apps, core.agentPins) { h, r, p, a, g ->
            val top = if (h) beamRecents(r, a) else room.places.take(room.entry)
            beamRoom(top, p, a, g)
        }.collect { places -> room.lay(places, places.indexOf(Place.Of(Feature.Ask))) }
    }
    val sheet = remember { SheetState() }
    val transforming = remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val control = remember { ControlState(scope) }
    // Anything that changes what is under them puts the control centre and the sheet away.
    val under by remember { derivedStateOf { Triple(nav.current, room.open > 0.5f, room.home > 0.5f) } }
    LaunchedEffect(under) { control.close(null) }
    // The control centre and the sheet take the ramp of what is under them: the page on top
    // (an Atelier app's, its own hue), Home's, or the panel or feature the room is on.
    val apps = core.atelier.apps.collectAsStateWithLifecycle()
    val underRamp by remember {
        derivedStateOf {
            if (nav.current != Screen.Home) atelierRampOf(nav.current, apps.value) ?: rampOf(nav.current)
            else if (room.home > 0.5f) HOME else rampOf(room.lastPlace, apps.value)
        }
    }
    // The first setup, over everything but a ringing timer; while it covers the screen, what
    // is under it is out of view, so it gives up the wheel and the camera.
    val setupCovers = remember { mutableStateOf(false) }
    // Beam's own keyboard (Keyboard.kt): while a field has it, the pages and the first setup
    // stand above it, as the system's keyboard resized the window; the control centre and the
    // sheet still cover the whole screen, the keys too. A tap anywhere but a field or the keys
    // puts them away.
    val keyboard = remember { KeyboardState() }
    val keys = if (keyboard.open) keysHeight(keyboard.rows.size) else 0.dp
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize().tapAwayClosesKeys(keyboard, focus)) {
        OwnKeyboard(keyboard) {
            CompositionLocalProvider(LocalSheet provides sheet, LocalOpenSetup provides core::openSetup, LocalLayerVisible provides !setupCovers.value) {
                Box(Modifier.fillMaxSize().padding(bottom = keys).controlEdge(control)) {
                    Slides(nav) { screen ->
                        if (screen == Screen.Home) Room(room, panels, core, home) else BeamPage(screen, core, nav, transforming)
                    }
                }
                // The keys take the wheel (the caret) even while the first setup covers the pages.
                if (keyboard.open) CompositionLocalProvider(LocalLayerVisible provides true) {
                    BeamKeyboard(keyboard, underRamp, Modifier.align(Alignment.BottomCenter))
                }
                BeamControlCenter(
                    control, device, core.volume, core.brightness, core.muted, underRamp,
                    onVolume = core::saveVolume, onBrightness = core::saveBrightness, onMute = core::saveMuted,
                    onWifi = core.wifi::setEnabled, onBluetooth = core.bluetooth::setEnabled, onTailnet = core::setTailnet,
                    onOpenWifi = { control.close(null); nav.push(Screen.Wifi) },
                    onOpenBluetooth = { control.close(null); nav.push(Screen.Bluetooth) },
                    onOpenTailnet = { control.close(null); nav.push(Screen.Tailscale) },
                    onOpenBattery = { control.close(null); nav.push(Screen.Battery) },
                    onPower = { core.power(it) },
                )
            }
            BeamSheetHost(sheet, under, underRamp)
            Box(Modifier.fillMaxSize().padding(bottom = keys)) { BeamSetup(core, device, setupCovers) }
        }
        BeamRinging(ringing, core::dismissRing, onSettle = room::snap)
        // An update installing: over everything, a ringing timer included (Update.kt).
        BeamUpdateLock(core)
    }
}

/**
 * The root of the stack: Home, the side wheel and the open feature as one space, moved by the
 * room's springs (ui/Motion.kt), on the black ground (every ramp's);
 * a tap on a disc on the rim opens its feature, anywhere else the one in hand.
 */
@Composable
private fun Room(room: RoomState, d: PanelDeps, core: Core, home: HomeState) {
    RoomLoop(room)
    RoomWheel(room)
    val room0 = rememberRoomLines(d.events, d.reminders, d.timers, d.photos, d.recordings.size, d.recorder, d.elapsed, d.metronome::practiceMs, d.translatorPair, d.forecasts.home(System.currentTimeMillis())?.weather)
    // Atelier's, how many apps it holds, none included, as Photos says its count.
    val apps by core.atelier.apps.collectAsStateWithLifecycle()
    val lines: (Feature) -> RoomLine = { f ->
        when (f) {
            Feature.Atelier -> RoomLine(apps.size.toString(), if (apps.size == 1) "app" else "apps")
            else -> room0(f)
        }
    }
    val live = rememberLive(d)
    Box(
        Modifier.fillMaxSize()
            .drawBehind { drawRect(groundAt(room, apps)) }
            .roomGestures(room, wheel = WHEEL, menuAt = { x, y -> rimAt(room, x, y) ?: tileAt(room, home, x, y) }),
    ) {
        SideWheelRoom(room, apps, lines, live)
        HomeLayer(room) { Home(room, d, apps, home) }
        // The camera's photos key opens Photos in place of the camera, as a jump of the room.
        OpenedLayer(room) { f -> Opened(f, d, core, onGallery = { room.goTo(room.placeOf(Feature.Photos)); Cues.play(Sound.ScreenIn) }) }
    }
}
