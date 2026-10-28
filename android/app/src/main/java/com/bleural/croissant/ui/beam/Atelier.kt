package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.atelier.AppSource
import com.bleural.croissant.atelier.AppTool
import com.bleural.croissant.atelier.Atelier
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.atelier.StagedApp
import com.bleural.croissant.atelier.Use
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.PullState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.held
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.rememberAppGlyph
import com.bleural.croissant.ui.rememberPull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Atelier: small apps anyone writes, Beam's alone. The opened feature is the pegboard (no dots), or
 * what Atelier holds while it is empty; the install sheet asks over it. Adding one, an app's page
 * and its info are pushed over it (AtelierAdd.kt, AtelierApp.kt, AtelierInfo.kt). Page pixels, on
 * the lifted page (the header on 52, the content from 66).
 */

/** Atelier's own ramp: the coral, for its pages, its knob and the add flow. */
internal val ATELIER: Ramp get() = rampOf(Feature.Atelier)

private val appRamps = HashMap<Int, Ramp>()

/** A disabled app's: grey where its hue would be. */
private val OFF: Ramp by lazy { ramp(DIM) }

/** An app's ramp: its own hue from croissant.json, lifted to carry black words ([liftHue]). Main thread only. */
internal fun appRamp(hue: Int): Ramp = appRamps.getOrPut(hue) { ramp(Color(0xFF000000.toInt() or liftHue(hue))) }

internal fun appRamp(app: InstalledApp): Ramp = appRamp(app.manifest.hue)

/** The ramp under an app's own pages (its page, its info), for the keys, the control centre and the sheet; null for any other screen. */
internal fun atelierRampOf(s: Screen, apps: List<InstalledApp>): Ramp? = when (s) {
    is Screen.AtelierApp -> apps.firstOrNull { it.id == s.id }?.let(::appRamp)
    is Screen.AtelierInfo -> apps.firstOrNull { it.id == s.id }?.let(::appRamp)
    else -> null
}

/** What each use is drawn with on the info page: the globe, the chat, the mic. */
internal fun useGlyph(use: Use): ImageVector = when (use) {
    Use.Network -> Glyphs.Globe
    Use.Model -> Glyphs.Chat
    Use.Words -> Glyphs.Ask
}

/** Atelier's pushed pages. */
@Composable
internal fun AtelierPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.AtelierAdd -> AtelierAddPage(core, nav)
        Screen.AtelierScan -> AtelierScanPage(core, nav)
        is Screen.AtelierApp -> AtelierAppPage(screen.id, core, nav)
        is Screen.AtelierInfo -> AtelierInfoPage(screen.id, core, nav)
        else -> Unit
    }
}

// ---- the pegboard ---------------------------------------------------------------------------

/** A tool's cell: 152 square, three across from x 12, its middle on the columns 88, 240 and 392 (`AT_COLS`). */
private const val TOOL = 152f
private const val MID = TOOL / 2f
/** The first row's cells from y 74 (the first glyph's middle on 150). */
private const val BOARD_TOP = 8f
/** The focused tool's disc and glyph; the others' glyph. */
private const val TOOL_DISC = 42f
private const val GLYPH_ON = 50f
private const val GLYPH_OFF = 60f
/** A tool's name: its baseline 70 under the glyph's middle. */
private const val NAME_BASE = MID + 70f
/** The rows that fit over the knob: a fourth and on scroll. */
private const val ROWS_SHOWN = 3
/** Room under the last row when it scrolls, so it comes clear of the knob as the third does. */
private const val BOARD_FOOT = 112f

private val TOOL_SPRING = spring<Float>(dampingRatio = 0.72f, stiffness = 450f)
private val NAME_ON = BeamType.Label.copy(fontWeight = FontWeight.Medium)
/** Nothing, while an app's glyph loads (the wheel's too). */
internal val NO_GLYPH = ImageVector.Builder("none", 24.dp, 24.dp, 24f, 24f).build()

/**
 * The opened feature: every app installed on the pegboard, three across in the order installed,
 * each its glyph in its hue with its name under it in sub. The one the wheel is on sits on a disc
 * of its hue, its glyph black, its name in the hue. The wheel steps them one by one, rows of three,
 * the board scrolling past nine; up from the first is the elastic pull back. A tap opens an app, a
 * press and hold its info; the coral knob adds one. Nothing installed: what Atelier holds and where
 * an app comes from. An app found by the add page is asked about here, the kit's sheet over the
 * board.
 */
@Composable
internal fun AtelierRoute(d: PanelDeps, core: Core) {
    val apps by core.atelier.apps.collectAsStateWithLifecycle()
    val adding by core.atelier.adding.collectAsStateWithLifecycle()
    val found = (adding as? Atelier.Adding.Found)?.app
    // The sheet goes with Atelier: closed with it up, it is not there when Atelier opens again.
    DisposableEffect(Unit) { onDispose { if (core.atelier.adding.value is Atelier.Adding.Found) core.atelier.dismiss() } }
    // The tool in hand is an app, not a place; when it goes, the one in its place is in hand.
    var hand by remember { mutableStateOf<String?>(null) }
    val place = remember { IntArray(1) }
    val at = apps.indexOfFirst { it.id == hand }.let { if (it >= 0) it else place[0].coerceIn(0, maxOf(0, apps.lastIndex)) }
    place[0] = at
    val pull = rememberPull(d::close)
    val scroll = rememberScrollState()
    WheelTarget { delta ->
        if (delta < 0 && at <= 0) { pull.stepBack(); return@WheelTarget false }
        moved(at, delta, apps.lastIndex) { hand = apps[it].id }
    }
    // The row in hand stays clear of the header and of the knob: past the third, the board scrolls.
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    LaunchedEffect(at, apps.size) {
        val row = at / 3
        val lo = (row - (ROWS_SHOWN - 1)) * TOOL
        val hi = row * TOOL
        val now = scroll.value / unit
        val to = now.coerceIn(lo, hi).coerceAtLeast(0f)
        if (to != now) scroll.animateScrollTo((to * unit).roundToInt(), TOOL_SPRING)
    }
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PageTop("atelier", ATELIER) {
            if (apps.isEmpty()) Empty(pull)
            else Board(apps, if (found != null) -1 else at, pull, scroll,
                onOpen = { hand = it.id; d.nav.push(Screen.AtelierApp(it.id)) },
                onInfo = { hand = it.id; d.nav.push(Screen.AtelierInfo(it.id)) })
            // On the ground's black, so a row scrolled under it never shows round it.
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.px, bottom = 16.px).size(100.px).clip(CircleShape).background(GROUND))
            KnobKey(Glyphs.Plus, ATELIER, onClick = { d.nav.push(Screen.AtelierAdd) })
        }
        if (found != null) InstallPage(
            found,
            onInstall = {
                if (!installing) {
                    installing = true
                    scope.launch {
                        val app = runCatching { core.atelier.install() }.getOrNull()
                        if (app != null) { hand = app.id; Cues.play(Sound.Commit) }
                        else { core.atelier.dismiss(); Cues.play(Sound.ExchangeFailed) }
                        installing = false
                    }
                }
            },
            onCancel = { core.atelier.dismiss(); Cues.play(Sound.SheetDismiss) },
        )
    }
}

/** The board: the tools in rows of three, [at] the one in hand (-1 none, under the sheet). */
@Composable
private fun Board(
    apps: List<InstalledApp>, at: Int, pull: PullState, scroll: ScrollState,
    onOpen: (InstalledApp) -> Unit, onInfo: (InstalledApp) -> Unit,
) {
    val rows = (apps.size + 2) / 3
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(scroll)) {
            Spacer(Modifier.height(BOARD_TOP.px))
            for (row in 0 until rows) {
                Row(Modifier.padding(start = 12.px)) {
                    for (col in 0 until 3) {
                        val i = row * 3 + col
                        val app = apps.getOrNull(i) ?: break
                        // A disabled app does not open: a tap goes to its info, where it is turned on again.
                        key(app.id) { Tool(app, i == at, onClick = { if (app.enabled) onOpen(app) else onInfo(app) }, onLongClick = { onInfo(app) }) }
                    }
                }
            }
            if (rows > ROWS_SHOWN) Spacer(Modifier.height(BOARD_FOOT.px))
        }
        TopFade(ATELIER, scroll)
    }
}

/**
 * One tool: its glyph 60 in its hue, its name under it in sub; in hand ([on]), a disc r 42 of its
 * hue with the glyph 50 in black on it, its name in the hue, Medium. The disc comes and goes on a
 * spring, read in the draw. A disabled app is all in grey, [DIM] for its hue, its name dim too:
 * kept, inactive.
 */
@Composable
private fun Tool(app: InstalledApp, on: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val r = if (app.enabled) appRamp(app) else OFF
    val k = animateFloatAsState(if (on) 1f else 0f, TOOL_SPRING, label = "tool")
    val vector = rememberAppGlyph(app.manifest.glyph)
    val painter = rememberVectorPainter(vector ?: NO_GLYPH)
    Box(
        Modifier.size(TOOL.px).held(onClick, onLongClick).drawBehind {
            val u = size.width / TOOL
            scale(u, u, pivot = Offset.Zero) {
                val s = k.value
                if (s > 0.01f) dot(MID, MID, TOOL_DISC * s, r.hue)
                val t = s.coerceIn(0f, 1f)
                val g = GLYPH_OFF + (GLYPH_ON - GLYPH_OFF) * t
                if (vector != null) glyph(painter, MID - g / 2f, MID - g / 2f, g, lerp(r.hue, GROUND, t))
            }
        },
    ) {
        Text(
            app.manifest.name.lowercase(), style = if (on) NAME_ON else BeamType.Label,
            color = if (on) r.hue else if (app.enabled) SUB else DIM,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 4.px, end = 4.px).paddingFromBaseline(top = NAME_BASE.px),
        )
    }
}

/** Nothing installed, in coral: what Atelier holds, big, and where an app comes from in sub. */
@Composable
private fun Empty(pull: PullState) {
    Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(rememberScrollState())) {
        // "small apps," on 150 and "made by anyone" 54 under it; the three lines under that from 270, 42 apart.
        Baselines(listOf(150f - CONTENT_TOP, 270f - 204f), bottom = 112f) {
            Text("small apps,\nmade by anyone", style = BeamType.Second, color = INK, modifier = Modifier.padding(start = 22.px, end = 22.px))
            Text(HELLO, style = BeamType.Body, color = SUB, modifier = Modifier.padding(start = 24.px, end = 24.px))
        }
    }
}

private const val HELLO = "add one from github:\ntype its owner/repo,\nor scan its qr code"

// ---- installing -----------------------------------------------------------------------------

/** A tool's name to the model, over its block. */
private val TOOL_NAME = BeamType.Value.copy(fontWeight = FontWeight.Medium)

/** An argument's line in the block, 38 a line. */
private val ARG = BeamType.Value.copy(lineHeight = 38f.pxSp)

/**
 * Installing an app found on GitHub (picked 2026-10-16): the sheet unrolled into a page over the
 * board, under Atelier's header. The app's glyph at 84 in its hue; "install name?", where it comes
 * from and that nobody has checked it; may use, a row each; adds to ask, each tool as Ask gets it
 * ([ToolBlock]). Install in the app's hue over cancel close the page, so install is reached only
 * past every tool; a page shorter than the screen has them at its foot, where the sheet had them.
 * The wheel scrolls it; at the end it moves between the two words, starting on cancel, as the
 * sheet's did. Up at the top is the pull back, which cancels.
 */
@Composable
private fun InstallPage(found: StagedApp, onInstall: () -> Unit, onCancel: () -> Unit) {
    val m = found.manifest
    val r = appRamp(m.hue)
    val vector = rememberAppGlyph(m.glyph)
    val from = when (val s = found.source) {
        is AppSource.GitHub -> "from ${s.repo}"
        AppSource.Local -> "from this computer"
    }
    val uses = Use.entries.filter { it in m.uses }
    val scroll = rememberScrollState()
    val pull = rememberPull(onCancel)
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.px.roundToPx() }
    // 0 install, 1 cancel.
    var focus by remember { mutableIntStateOf(1) }
    WheelTarget { delta ->
        val end = scroll.value >= scroll.maxValue
        when {
            end && delta > 0 -> moved(focus, delta, 1) { focus = it }
            end && delta < 0 && focus == 1 -> { focus = 0; true }
            else -> {
                focus = 1
                if (delta < 0 && scroll.value == 0) pull.stepBack() else scope.launch { scroll.glideBy(delta * step) }
                false
            }
        }
    }
    PageTop("atelier", ATELIER) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(scroll)) {
                Column(Modifier.fillMaxWidth().heightIn(min = h)) {
                    // The glyph 84 from y 74 at x 18; the question on 202, its lines 42 apart.
                    Baselines(listOf(8f, 44f, 42f, 42f) + if (uses.isEmpty()) listOf(42f) else emptyList(), bottom = 0f) {
                        Box(Modifier.padding(start = 18.px).size(84.px)) {
                            if (vector != null) Icon(vector, null, Modifier.fillMaxSize(), r.hue)
                        }
                        Text("install ${m.name.lowercase()}?", style = BeamType.BodyMedium, color = INK, modifier = Modifier.padding(start = 24.px, end = 24.px))
                        Text(from, style = BeamType.Body, color = SUB, modifier = Modifier.padding(start = 24.px, end = 24.px))
                        Text("nobody has checked it", style = BeamType.Body, color = SUB, modifier = Modifier.padding(start = 24.px, end = 24.px))
                        if (uses.isEmpty()) Text(USES_NOTHING, style = BeamType.Body, color = SUB, modifier = Modifier.padding(start = 24.px, end = 24.px))
                    }
                    if (uses.isNotEmpty()) {
                        DotLabel("may use", r, top = 50f)
                        uses.forEach { u -> ShelfRow(useWords(u), r, glyph = useGlyph(u)) }
                    }
                    if (m.tools.isNotEmpty()) {
                        DotLabel("adds to ask", r, top = if (uses.isEmpty()) 50f else LABEL_ABOVE)
                        m.tools.forEach { t -> ToolBlock(Atelier.wire(found.id, t.name), t, r) }
                    }
                    // Install's baseline 90 under the last tool's foot (or the rows'), cancel 66 under it, 590 at the end.
                    Spacer(Modifier.height(38.px))
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SheetWord("install", r.hue, focus == 0, onInstall)
                        SheetWord("cancel", INK, focus == 1, onCancel)
                    }
                    Spacer(Modifier.height(36.px))
                }
            }
        }
        TopFade(ATELIER, scroll)
    }
}

/**
 * A tool as it reaches Ask: the name the model reads, Medium in the app's hue; its description and
 * its arguments' as croissant.json has them in a faint block, 16 from the sides, radius 16 (a
 * file's code block, Files.kt: these are the exact words); then each example sentence after a bar
 * in the hue (a file's quote), 18 under the block and 14 apart.
 */
@Composable
private fun ToolBlock(wire: String, t: AppTool, r: Ramp) {
    val args = remember(t.parameters) { argLines(t.parameters) }
    Text(wire, style = TOOL_NAME, color = r.hue, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 30.px, bottom = 20.px))
    Box(Modifier.padding(start = 16.px, end = 16.px).fillMaxWidth().background(FAINT, RoundedCornerShape(16.px))) {
        // The description's first baseline 46 down; the arguments 48 under its last, 38 apart; 22 under the last.
        Baselines(listOf(46f) + args.indices.map { if (it == 0) 48f else 38f }, bottom = 22f) {
            Text(t.description, style = BeamType.Body, color = INK, modifier = Modifier.padding(start = 20.px, end = 20.px))
            args.forEach { Text(it, style = ARG, color = SUB, modifier = Modifier.padding(start = 20.px, end = 20.px)) }
        }
    }
    t.examples.forEachIndexed { i, e ->
        Row(Modifier.padding(start = 24.px, end = 24.px, top = (if (i == 0) 18f else 14f).px).height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.px).fillMaxHeight().background(r.hue, RoundedCornerShape(2.px)))
            Text(e.say, style = BeamType.Body, color = SUB,
                modifier = Modifier.padding(start = 18.px).paddingFromBaseline(top = 32.px, bottom = 12.px))
        }
    }
    Spacer(Modifier.height(14.px))
}
