package com.bleural.croissant.ui.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bleural.croissant.ui.held
import androidx.compose.ui.text.style.TextOverflow
import com.bleural.croissant.audio.Cues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.bleural.croissant.AgentFolder
import com.bleural.croissant.AgentSessions
import com.bleural.croissant.net.AgentInfo
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Agents
import com.bleural.croissant.AgentsUi
import com.bleural.croissant.BridgeReach
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.net.parseAcpLink
import com.bleural.croissant.net.plain
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.px
import com.bleural.croissant.ui.rememberQrScanner
import com.bleural.croissant.ui.tappable

/*
 * Agents: the coding agents on a computer running acp-bridge. Its page lists them; an agent's page
 * lists its sessions under their folders, each opening its conversation.
 */

internal val AGENTS: Ramp get() = rampOf(Feature.Agents)

/** Agents opened: its pairing while no bridge is paired, else the bridge's agents. */
@Composable
internal fun AgentsRoute(d: PanelDeps, core: Core) {
    if (!core.acpBridge.set) AgentsIntro(onPair = { d.nav.push(Screen.AgentsPair) }, onBack = d::close)
    else AgentsPage(core, d.nav, onBack = d::close)
}

/** Agents' pushed pages. */
@Composable
internal fun AgentsPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.AgentsPair -> {
            val scanner = rememberQrScanner(::parseAcpLink) { b -> core.saveAcpBridge(b); nav.pop(Sound.Commit) }
            QrScanPage("pair", AGENTS, scanner, "point at the qr of acp-bridge pair", "that code is not a bridge's", onBack = nav::pop)
        }
        Screen.AgentsBridge -> AgentsBridgePage(core, nav)
        is Screen.AgentSessions -> AgentSessionsPage(screen.agent, core, nav)
        is Screen.AgentFolder -> AgentFolderPage(screen.agent, screen.cwd, core, nav)
        is Screen.AgentChat -> AgentChatPage(screen.agent, screen.session, core, nav)
        is Screen.AgentNew -> AgentNewPage(screen.agent, screen.cwd, core, nav)
        is Screen.AgentFiles -> AgentFilesPage(screen.agent, screen.cwd, screen.path, core, nav)
        is Screen.AgentFile -> AgentFilePage(screen.agent, screen.cwd, screen.path, core, nav)
        else -> Unit
    }
}

/** No bridge yet: what to run on the computer, and the key that scans its QR. */
@Composable
private fun AgentsIntro(onPair: () -> Unit, onBack: () -> Unit) {
    val r = AGENTS
    val (scroll, pull) = rememberWordsScroll(onBack)
    PageTop("agents", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            SettingsWords("claude code, codex and other agents on your computer, driven from here", r.ink)
            SettingsWords("on the computer, run acp-bridge serve and scan one of its qr codes", r.sub, top = 50f)
            Spacer(Modifier.height(140.px))
        }
        KnobKey(Glyphs.Scan, r, onClick = onPair)
    }
}

/** One line of an Agents page: a label, words that are not a row, or a row, which the wheel stops on when it can [act]. */
internal sealed interface AgentLine {
    /** A group's name; with [onAdd], a + at its end. */
    data class Label(val text: String, val onAdd: (() -> Unit)? = null) : AgentLine
    data class Words(val text: String, val danger: Boolean = false) : AgentLine
    data class Row(val key: String, val title: String, val value: String? = null, val dim: Boolean = false,
                   val danger: Boolean = false, val gap: Boolean = false, val mark: ImageVector? = null,
                   val chevron: Boolean = false, val act: (() -> Unit)? = null) : AgentLine
}

/**
 * [lines] under the header [word]: labels on the label's lines, words, and rows the wheel
 * steps through (those that can act), a row's label kept in view above it. [onNearEnd] is
 * told when the last few lines come into view, for a list that loads in pages.
 */
@Composable
internal fun LinesPage(word: String, r: Ramp, lines: List<AgentLine>, onBack: () -> Unit, onNearEnd: () -> Unit = {}) {
    // Lazy item 0 is the 4 px over the first line, so line i is item i + 1.
    val stops = remember(lines) { lines.indices.filter { (lines[it] as? AgentLine.Row)?.act != null } }
    val rows = rememberRows(stops.size, onBack, itemOf = { stops[it] + 1 },
        topOf = { stops[it].let { at -> if (lines.getOrNull(at - 1) is AgentLine.Label) at else at + 1 } })
    val lit = stops.getOrNull(rows.focus)
    val size by rememberUpdatedState(lines.size)
    val nearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(rows.list) {
        snapshotFlow { rows.list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (it >= size - 6) nearEnd() }
    }
    PageTop(word, r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(4.px)) }
            items(lines.size, key = { i -> lines[i].let { if (it is AgentLine.Row) it.key else "$i" } }) { i ->
                when (val line = lines[i]) {
                    is AgentLine.Label -> AddLabel(line.text, r, top = if (i == 0) LABEL_FIRST else LABEL_ABOVE, onAdd = line.onAdd)
                    is AgentLine.Words -> SettingsWords(line.text, if (line.danger) r.danger else r.sub, top = if (i == 0) 28f else 40f)
                    is AgentLine.Row -> Column {
                        if (line.gap) Spacer(Modifier.height(SETTINGS_GAP))
                        ShelfRow(line.title, r, focused = lit == i, value = line.value, dim = line.dim, danger = line.danger, mark = line.mark, chevron = line.chevron,
                            onClick = line.act?.let { act -> { rows.focus = stops.indexOf(i); act() } })
                    }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/**
 * A [DotLabel] with, given [onAdd], a + in the hue at its end (where a label's count goes):
 * a folder's new chat (`Screen.AgentNew`). The + is 28, centred on the words' middle (9 over
 * the baseline), its target 72 by 38, all within the label's own height.
 */
@Composable
private fun AddLabel(text: String, r: Ramp, top: Float, onAdd: (() -> Unit)?) {
    if (onAdd == null) return DotLabel(text, r, top = top)
    Box(Modifier.fillMaxWidth()) {
        DotLabel(text, r, top = top)
        Box(Modifier.align(Alignment.TopEnd).padding(top = (top - 28f).px).size(72.px, 38.px).tappable(onAdd), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Plus, null, Modifier.padding(start = 20.px).size(28.px), r.hue)
        }
    }
}

/** The bridge's agents, each opening its sessions; one that cannot run there is dim. Then the bridge itself. */
@Composable
private fun AgentsPage(core: Core, nav: NavState, onBack: () -> Unit) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    if (ui.agents.isNotEmpty() && ui.reach != BridgeReach.Refused) return AgentCards(core, nav, ui, onBack)
    val lines = agentsLines(ui, onAgent = { nav.push(Screen.AgentSessions(it)) }, onRetry = core.agents::refresh,
        onPair = { nav.push(Screen.AgentsPair) }, onBridge = { nav.push(Screen.AgentsBridge) })
    LinesPage("agents", AGENTS, lines, onBack)
}

/** A card's height, and the space under it. */
private const val CARD_H = 120f
private const val CARD_GAP = 12f

/**
 * The bridge's agents as cards (2026-10-21), each filled with its own hue ([agentLook]), its
 * glyph and name in black, and under the name what it is now: on the wheel, how many of its
 * sessions run, or not set up (dim, when the bridge's computer cannot run it). A tap opens its
 * sessions; a long press pins it to the wheel or takes it off. The wheel steps the cards and
 * then the bridge row; the focused card has a black ring inside its edge.
 */
@Composable
private fun AgentCards(core: Core, nav: NavState, ui: AgentsUi, onBack: () -> Unit) {
    val pins by core.agentPins.collectAsStateWithLifecycle()
    val agents = ui.agents
    val r = AGENTS
    val rows = rememberRows(agents.size + 1, onBack, itemOf = { it + 1 })
    PageTop("agents", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(8.px)) }
            items(agents.size, key = { agents[it].id }) { i ->
                val a = agents[i]
                val running = ui.sessions[a.id]?.list?.count { it.running } ?: 0
                val pinned = a.id in pins
                val state = when {
                    !a.ready -> "not set up"
                    running > 0 && pinned -> "on the wheel, $running running"
                    running > 0 -> "$running running"
                    pinned -> "on the wheel"
                    else -> null
                }
                AgentCard(a.name, agentLook(a.id), state, ready = a.ready, focused = rows.focus == i,
                    onOpen = { rows.focus = i; nav.push(Screen.AgentSessions(a.id)) },
                    onPin = { rows.focus = i; core.pinAgent(a.id, !pinned) })
            }
            item {
                Column {
                    Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow("bridge", r, focused = rows.focus == agents.size, value = ui.host,
                        onClick = { rows.focus = agents.size; nav.push(Screen.AgentsBridge) })
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

@Composable
private fun AgentCard(name: String, look: AgentLook, state: String?, ready: Boolean, focused: Boolean, onOpen: () -> Unit, onPin: () -> Unit) {
    val fill = if (ready) look.hue else look.hue.copy(alpha = 0.35f)
    Box(
        Modifier.padding(start = 16.px, end = 16.px, bottom = CARD_GAP.px).fillMaxWidth().height(CARD_H.px)
            .clip(RoundedCornerShape(16.px)).background(fill)
            .drawBehind {
                if (!focused) return@drawBehind
                val u = size.width / 448f
                drawRoundRect(GROUND, Offset(7.5f * u, 7.5f * u), Size(size.width - 15f * u, size.height - 15f * u),
                    CornerRadius(10f * u), style = Stroke(3f * u))
            }
            .held(onOpen, onPin),
    ) {
        Icon(look.glyph, null, Modifier.align(Alignment.CenterStart).padding(start = 24.px).size(40.px), GROUND)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 84.px, end = 24.px)) {
            Text(name, style = if (focused) BeamType.RowFocused else BeamType.Row, color = GROUND, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state != null) Text(state, style = BeamType.Value, color = GROUND.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun agentsLines(ui: AgentsUi, onAgent: (String) -> Unit, onRetry: () -> Unit, onPair: () -> Unit, onBridge: () -> Unit): List<AgentLine> = buildList {
    when {
        ui.reach == BridgeReach.Refused -> {
            add(AgentLine.Words("the bridge refused this r1", danger = true))
            add(AgentLine.Row("pair", "pair again", act = onPair))
        }
        ui.reach == BridgeReach.Away && ui.agents.isEmpty() -> {
            add(AgentLine.Words("the bridge is away: is acp-bridge serve running?", danger = true))
            add(AgentLine.Row("retry", "try again", act = onRetry))
        }
        ui.agents.isEmpty() -> add(AgentLine.Words("reaching the bridge"))
        else -> ui.agents.forEach { a ->
            if (a.ready) add(AgentLine.Row("agent-${a.id}", a.name, act = { onAgent(a.id) }))
            else add(AgentLine.Row("agent-${a.id}", a.name, value = "not set up", dim = true))
        }
    }
    add(AgentLine.Row("bridge", "bridge", value = ui.host, gap = true, act = onBridge))
}

/**
 * The bridges paired (2026-10-21): each computer by its own name, the one in use ticked with
 * the address it answered on, one at a time, never two mixed. A tap on another uses it
 * instead; then pair another computer; forget takes the one in use off the R1.
 */
@Composable
private fun AgentsBridgePage(core: Core, nav: NavState) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    val active = core.acpBridge.token
    val lines = buildList {
        core.acpBridges.forEach { b ->
            val inUse = b.token == active
            val value = if (inUse) (core.agents.client.reached ?: b.hosts.firstOrNull()).takeIf { ui.reach != BridgeReach.Away } ?: "away" else null
            add(AgentLine.Row("bridge-${b.token}", b.name.ifEmpty { b.hosts.firstOrNull().orEmpty() }, value = value,
                mark = if (inUse) Glyphs.Check else null,
                act = { if (!inUse) { Cues.play(Sound.Commit); core.useAcpBridge(b.token) } }))
        }
        add(AgentLine.Row("pair", "pair another", gap = true, act = { nav.push(Screen.AgentsPair) }))
    }
    Box(Modifier.fillMaxSize()) {
        LinesPage("bridges", AGENTS, lines, nav::pop)
        SurePill("forget", onSure = {
            core.forgetAcpBridge(active)
            if (!core.acpBridge.set) nav.pop(Sound.Destroy) else Cues.play(Sound.Destroy)
        })
    }
}

/**
 * An agent's sessions (`session/list` through the bridge) by their folders, each folder a card, the
 * newest first (decided 2026-10-21): its name, the age of its newest session at the end (now while
 * one runs), that session's title under it, a yellow dot before the age while one of them waits on
 * you. The wheel's card lifts and takes the agent's hue. A card opens its folder
 * ([AgentFolderPage]). An agent the bridge's computer cannot run yet has, in their place, what it
 * needs there, in the bridge's own words.
 */
@Composable
private fun AgentSessionsPage(agent: String, core: Core, nav: NavState) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    // Read again each time the page is in front: back from a new chat, it is among them.
    val front = nav.current == Screen.AgentSessions(agent)
    val info = ui.agents.firstOrNull { it.id == agent }
    val missing = info?.ready == false
    LaunchedEffect(agent, front, missing) { if (front && !missing) core.agents.sessions(agent) }
    val s = ui.sessions[agent]
    val name = info?.name ?: agent
    val look = agentLook(agent)
    val waiting = sessionsWaiting(info, ui.host, s, onRetry = { core.agents.sessions(agent) })
    if (waiting != null || s == null) return LinesPage(name, look.ramp, waiting.orEmpty(), nav::pop)
    val folders = remember(s.list) { Agents.folders(s.list) }
    val rows = rememberRows(folders.size, nav::pop, itemOf = { it + 1 })
    PageTop(name, look.ramp) {
        ShelfList(rows, look.ramp) {
            item { Spacer(Modifier.height(8.px)) }
            items(folders.size, key = { folders[it].cwd }) { i ->
                val f = folders[i]
                FolderCard(f, look, focused = rows.focus == i, onOpen = { rows.focus = i; nav.push(Screen.AgentFolder(agent, f.cwd)) })
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** What an agent's sessions page says in their place, or null when it has sessions to show. */
private fun sessionsWaiting(info: AgentInfo?, host: String?, s: AgentSessions?, onRetry: () -> Unit): List<AgentLine>? = when {
    info != null && !info.ready -> listOf(
        AgentLine.Words("not set up on ${host ?: "the computer"}: it needs ${info.missing.joinToString(" and ")}", danger = true),
        AgentLine.Words(plain(info.setup)))
    s == null || (!s.loaded && s.error == null) -> listOf(AgentLine.Words("reading the sessions"))
    s.list.isEmpty() && s.error != null -> listOf(AgentLine.Words(s.error, danger = true), AgentLine.Row("retry", "try again", act = onRetry))
    s.list.isEmpty() -> listOf(AgentLine.Words("no sessions yet"))
    else -> null
}

/** A folder's card (`ag2Stack`): 108 high in the surface, radius 28, 12 apart; focused, 116 by 460, 4 out on every side, in the hue, its words black. */
@Composable
private fun FolderCard(f: AgentFolder, look: AgentLook, focused: Boolean, onOpen: () -> Unit) {
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(LIFT_MS), label = "lift")
    val newest = f.sessions.firstOrNull()
    val age = if (f.sessions.any { it.running }) "now" else Agents.age(newest?.updatedAt)
    val ink = lerp(INK, GROUND, lift)
    val sub = lerp(SUB, GROUND.copy(alpha = 0.6f), lift)
    val out = (4f * lift).px
    Box(Modifier.fillMaxWidth().height(FOLDER_H.px).padding(horizontal = 16.px - out, vertical = (FOLDER_GAP / 2).px - out)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.px)).background(lerp(SURFACE, look.hue, lift)).tappable(onOpen)
                .padding(horizontal = 24.px + out),
        ) {
            Row(Modifier.fillMaxWidth().paddingFromBaseline(top = (46f + 4f * lift).px), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = BeamType.Select, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (f.sessions.any { it.asking }) WaitingDot(Modifier.padding(start = 16.px, end = 12.px), onHue = lift > 0.5f)
                if (age != null) Text(age, style = BeamType.Value, color = sub, maxLines = 1, modifier = Modifier.padding(start = 16.px))
            }
            Text(newest?.title ?: "untitled", style = BeamType.Value, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().paddingFromBaseline(top = (84f + 4f * lift).px))
        }
    }
}

/** A session waiting on you (a permission): a yellow dot, r 7; on the hue, ringed in black. */
@Composable
private fun WaitingDot(modifier: Modifier = Modifier, onHue: Boolean = false) {
    Canvas(modifier.size(14.px)) {
        drawCircle(ASKING)
        if (onHue) drawCircle(GROUND, style = Stroke(3f / 14f * size.width))
    }
}

/**
 * One of an agent's folders: its name the header word, new chat first (a + and its words), then
 * its sessions, newest first, each its title over its age (now while it runs) or, after a yellow
 * dot, "waiting on you". The wheel's row is in the hue. A session opens its conversation
 * (AgentChat.kt); new chat, a blank one in the folder (AgentNewPage).
 */
@Composable
private fun AgentFolderPage(agent: String, cwd: String, core: Core, nav: NavState) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    val front = nav.current == Screen.AgentFolder(agent, cwd)
    LaunchedEffect(agent, front) { if (front) core.agents.sessions(agent) }
    val r = agentLook(agent).ramp
    val sessions = remember(ui.sessions[agent]) { Agents.folders(ui.sessions[agent]?.list.orEmpty()).firstOrNull { it.cwd == cwd }?.sessions.orEmpty() }
    val rows = rememberRows(sessions.size + 1, nav::pop, itemOf = { it + 1 })
    val word = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
    PageTop(word, r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(4.px)) }
            item(key = "new") {
                val on = rows.focus == 0
                Row(Modifier.fillMaxWidth().height(NEW_H.px).tappable { rows.focus = 0; nav.push(Screen.AgentNew(agent, cwd)) }.padding(start = 24.px),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Plus, null, Modifier.size(32.px), if (on) r.hue else r.ink)
                    Text("new chat", style = if (on) BeamType.RowFocused else BeamType.Row, color = if (on) r.hue else r.ink, modifier = Modifier.padding(start = 16.px))
                }
            }
            items(sessions.size, key = { sessions[it].id }) { i ->
                val x = sessions[i]
                val on = rows.focus == i + 1
                Column(Modifier.fillMaxWidth().height(SESSION_H.px).tappable { rows.focus = i + 1; nav.push(Screen.AgentChat(agent, x.id, x.cwd)) }
                    .padding(horizontal = 24.px)) {
                    Text(x.title ?: "untitled", style = if (on) BeamType.RowFocused else BeamType.Row, color = if (on) r.hue else r.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.paddingFromBaseline(top = 44.px))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (x.asking) WaitingDot(Modifier.padding(end = 10.px))
                        Text(if (x.asking) "waiting on you" else if (x.running) "now" else Agents.age(x.updatedAt).orEmpty(),
                            style = BeamType.Value, color = r.sub, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = 38.px))
                    }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** The folders' surface (`AG2_SURF`): the one tone of what is grouped. */
private val SURFACE = Color(0xFF1C1C1E)
private const val FOLDER_H = 120f
private const val FOLDER_GAP = 12f
private const val LIFT_MS = 160
private const val NEW_H = 80f
private const val SESSION_H = 108f
