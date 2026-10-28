package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.net.ModelEntry
import com.bleural.croissant.net.ModelList
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.tappable
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/*
 * Picking a model (2026-10-10): two levels. First, the one in use, its card open and ticked as the
 * page opens, a small line under its name saying where it runs (its place's laptop or cloud and the
 * place's name); then a row a place: what the server's own machine runs (Ollama, its Whisper) under
 * the server's name as Settings' server card says it, a laptop before it, and each provider added
 * with a key, a cloud before it (a free tier a place of its own). A place's row has how many models
 * it holds, a chevron, and under its name the span of what its rows say (memory on the server's
 * machine, months at your pace on a provider) or "free tier". A place holding one model is that
 * model's row instead, the place and its month under it; a place holding only the one in use is
 * gone. The wheel steps the card and the places, the place it is on ringed; a tap opens it.
 *
 * Second, a place opened: its models alone, its name the header word, the one in use first and
 * ticked (no where line: the header says it), then the rest cheapest first as the server sorts
 * them, the edges fading; each row the model's name, its month (or memory) and a bar to the place's
 * own scale (free a dot). The row the wheel is on opens into a card: the month, the bar, the rates
 * per 1M tokens each with its dollar sign (an hour of speech, a picture; what a model here takes in
 * memory), "no thinking" on a chat model that does not think, and on a free tier what it asked
 * today, its limit said once above the list. Speech has live words first while the Mac's Whisper is
 * in use; a cloud one's card says the words come at the release instead (no live words: each pass
 * is billed).
 *
 * A tap on the one in use steps back. A tap on another asks first: a card in the middle over the
 * page dimmed, the model and where it runs, cancel and use side by side, the one the wheel is on
 * filled; use picks it from the next hold on and returns to the server's page; a tap off the card
 * or the edge drag cancels.
 */

/** A row: 70 tall, the name (34) on 38, its bar 12 under. */
private const val LINE_H = 70f

/** What the one in use at the top adds to its row for where it runs: the line on 76, the bar 12 under it. */
private const val WHERE_H = 38f

/** The same in its card: the line 40 under the name. */
private const val WHERE_CARD = 40f

/** The card's gap above and below, inside its row. */
private const val CARD_GAP = 6f

/** How long a row takes to open into its card, and close back. */
private const val OPEN_MS = 180

/** A place's row: 108 tall, its name (38) on 48, the span 40 under; ringed, the top 102. */
private const val PLACE_H = 108f

/** The card that asks first: 214 tall, centred on the screen's 320. */
private const val ASK_H = 214f
private const val ASK_TOP = 320f - ASK_H / 2

/** The card's room for words: 36 to 444. */
private const val CARD_ROOM = 408f

/** Where models run: the server's own machine, or one provider's key (a free tier apart from a paid one). */
private class Place(val key: String, val name: String, val local: Boolean, val free: Boolean, val models: List<ModelEntry>) {
    /** Its header word once opened: the server's name, or the provider's last word ("gemini"). */
    val word = if (local) name else name.substringAfterLast(' ')
    /** The dearest month here: the full bar on this place's scale. */
    val dearest = models.maxOfOrNull { it.month ?: 0.0 }?.takeIf { it > 0 }
    /** Under its name: "free tier", or the span of what its rows say at the right; null when they say nothing. */
    val span: String? = when {
        free -> "free tier"
        local -> models.mapNotNull { it.bytes }.takeIf { it.isNotEmpty() }?.let { b ->
            if (gigabytes(b.min()) == gigabytes(b.max())) gigabytes(b.max())
            else "${gigabytes(b.min()).removeSuffix(" GB")} to ${gigabytes(b.max())}"
        }
        else -> models.mapNotNull { it.month?.takeIf { m -> m > 0 } }.takeIf { it.isNotEmpty() }?.let { m ->
            if (dollars(m.min()) == dollars(m.max())) dollars(m.max()) else "${dollars(m.min())} to ${dollars(m.max())}"
        }
    }
}

/** A slot's places in the server's order: what runs on its machine under [here], each provider's after. */
private fun placesOf(list: ModelList, here: String): List<Place> =
    list.entries.groupBy { if (it.local) "here" else if (it.free) "${it.provider}, free" else it.provider }
        .map { (key, models) -> models[0].let { Place(key, if (it.local) here else it.provider, it.local, !it.local && models.all { m -> m.free }, models) } }

/** What every drawing on these pages draws with, made once. */
private class Pens(val text: TextMeasurer, val check: Painter, val laptop: Painter, val cloud: Painter) {
    fun of(p: Place) = if (p.local) laptop else cloud
}

@Composable
private fun rememberPens(): Pens {
    val text = rememberTextMeasurer(cacheSize = 64)
    val check = rememberVectorPainter(Glyphs.Check)
    val laptop = rememberVectorPainter(Glyphs.Laptop)
    val cloud = rememberVectorPainter(Glyphs.Cloud)
    return remember(text, check, laptop, cloud) { Pens(text, check, laptop, cloud) }
}

/**
 * Whose model a picker picks: the server's for one slot, or the next test's. [word] is the first level's
 * header word, [top] the one in use, [use] what picking one does, [screen] the picker's page at
 * a place (null, the first level).
 */
private class Pick(val slot: ModelSlot, val word: String, val r: Ramp, val places: List<Place>, val top: ModelEntry?,
                   val use: (ModelEntry) -> Unit, val screen: (String?) -> Screen)

/**
 * The model for [slot], from Settings > ask: [place] null, the first level, the one in use and the
 * places; else that place's key, its models. Every slot's are the C1's providers' (Ollama's and any
 * a day old listed again as it opens); speech's, pictures' and transform's the Mac's own first,
 * under the server's name. Chat's header word is its row's there, "model"; with none listed, it
 * says so with providers under it.
 */
@Composable
internal fun ModelPickPage(slot: ModelSlot, place: String?, core: Core, nav: NavState) {
    LaunchedEffect(Unit) {
        if (core.models == null) core.refreshModels()
        core.refreshListings()
    }
    val list = core.modelList(slot)
    if (slot == ModelSlot.Chat && list.entries.isEmpty()) return NoModels(nav)
    val here = core.server.name.ifBlank { core.server.host }
    val places = remember(list, here) { placesOf(list, here) }
    val word = if (slot == ModelSlot.Chat) "model" else wordOf(slot)
    val pick = Pick(slot, word, rampOf(Feature.Settings), places, list.resolve(core.modelPicks[slot]),
        use = { core.pickModel(slot, it.id) }, screen = { Screen.ModelPick(slot, it) })
    PickPages(pick, place, core, nav)
}

/**
 * The test's model: the chat model's picker as it is, for the next test alone; picking one sets
 * that test's model and changes no pick. The one the test would run on is at the top.
 */
@Composable
internal fun TestModelPage(place: String?, core: Core, nav: NavState) {
    LaunchedEffect(Unit) { core.refreshListings() }
    val list = core.modelList(ModelSlot.Chat)
    val here = core.server.name.ifBlank { core.server.host }
    val places = remember(list, here) { placesOf(list, here) }
    val pick = Pick(ModelSlot.Chat, "model", rampOf(Feature.Settings), places, list.resolve(NextTest.model(core)),
        use = { NextTest.pickModel(core, it.id) }, screen = { Screen.TestModel(it) })
    PickPages(pick, place, core, nav)
}

/** No chat model listed: the page says so where the one in use would be, providers the row under it. */
@Composable
private fun NoModels(nav: NavState) {
    val r = rampOf(Feature.Settings)
    val rows = rememberRows(1, nav::pop, itemOf = { it + 1 })
    PageTop("model", r) {
        ShelfList(rows, r) {
            // As Ask's "no server yet" sits over its row: the words at 112, the row's top at 152.
            item { Text("no models yet", style = BeamType.Body, color = r.sub, modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 46.px, bottom = 40.px)) }
            item { ShelfRow("providers", r, focused = rows.focus == 0, glyph = Glyphs.Cloud, chevron = true, onClick = { rows.focus = 0; nav.push(Screen.Providers) }) }
        }
    }
}

/** [place] null: the first level; else that place's key, its models; and the card asking first over either. */
@Composable
private fun PickPages(pick: Pick, place: String?, core: Core, nav: NavState) {
    val pens = rememberPens()
    var asking by remember { mutableStateOf<Pair<ModelEntry, Place>?>(null) }
    val keep = { e: ModelEntry -> pick.use(e); nav.pop(Sound.Commit) }
    val ask = { e: ModelEntry, p: Place -> asking = e to p; Cues.play(Sound.SheetIn) }
    val cancel = { asking = null; Cues.play(Sound.SheetDismiss) }
    Box(Modifier.fillMaxSize().edgeLeave({ asking != null }) { cancel() }) {
        if (place == null) Places(pick, core, nav, pens, keep, ask)
        else PlaceModels(pick, pick.places.firstOrNull { it.key == place }, nav, pens, keep, ask)
        asking?.let { (e, p) ->
            PickAsk(e, p, pens, pick.r, onCancel = cancel, onUse = {
                asking = null
                pick.use(e)
                if (place != null) nav.drop(pick.screen(null))
                nav.pop(Sound.Commit)
            })
        }
    }
}

/** The first level: live words on speech, the one in use as its card, then a row a place. */
@Composable
private fun Places(pick: Pick, core: Core, nav: NavState, pens: Pens, keep: (ModelEntry) -> Unit, ask: (ModelEntry, Place) -> Unit) {
    val slot = pick.slot
    val places = pick.places
    val top = pick.top
    val r = pick.r
    // Live words are the Mac's Whisper's alone: a cloud model hears the hold at its release.
    val speech = slot == ModelSlot.Stt && top?.local != false
    val first = if (speech) 1 else 0
    val lead = first + if (top != null) 1 else 0
    val home = places.firstOrNull { p -> p.models.any { it.id == top?.id } }
    val shown = remember(places, top) { places.filter { p -> !(p.models.size == 1 && p.models[0].id == top?.id) } }
    val rows = rememberRows(lead + shown.size, nav::pop, itemOf = { it + 1 })
    LaunchedEffect(top?.id) { if (top != null) rows.focus = first }
    PageTop(pick.word, r) {
        Box(Modifier.fillMaxSize()) {
            ShelfList(rows, r) {
                item { Spacer(Modifier.height(4.px)) }
                if (speech) item {
                    ShelfRow("live words", r, focused = rows.focus == 0, trailing = { Toggle(core.liveWords, r) }, onClick = {
                        rows.focus = 0
                        val on = !core.liveWords
                        core.setLiveWords(on)
                        Cues.play(if (on) Sound.SwitchOn else Sound.SwitchOff)
                    })
                }
                if (top != null) item(key = "top", contentType = "model") {
                    val m = remember(top, slot, home, pens) { ModelDraw(top, slot, true, home?.dearest, pens.text, where = home?.name) }
                    Column {
                        ModelRow(m, rows.focus == first, home?.let(pens::of) ?: if (top.local) pens.laptop else pens.cloud, pens, r) { rows.focus = first; keep(top) }
                        Spacer(Modifier.height(4.px))
                    }
                }
                items(shown.size, key = { "place " + shown[it].key }, contentType = { "place" }) { i ->
                    val p = shown[i]
                    PlaceRow(p, slot, rows.focus == lead + i, pens, r) {
                        rows.focus = lead + i
                        val one = p.models.singleOrNull()
                        if (one != null) ask(one, p) else nav.push(pick.screen(p.key))
                    }
                }
                item { Spacer(Modifier.height(48.px)) }
            }
            EdgeFades(r.ground, rows)
        }
    }
}

/**
 * A place's row: its laptop (44) or cloud (54) in the hue, its name (38) at 92, how many models at
 * 414 and the chevron (in the hue when the wheel is on it), the span under the name; ringed when
 * the wheel is on it. A place of one model is that model's row: its name, the place under it and
 * its month (or memory) at the right, no chevron.
 */
@Composable
private fun PlaceRow(p: Place, slot: ModelSlot, focused: Boolean, pens: Pens, r: Ramp, onTap: () -> Unit) {
    val one = p.models.singleOrNull()
    val right = remember(one, slot) { one?.let { shortOf(it) } }
    Canvas(Modifier.fillMaxWidth().height(PLACE_H.px).tappable(onTap)) {
        pageScale {
            val base = 48f
            val t = pens.text
            if (focused) ring(r, 0f, PLACE_H - 6f)
            when {
                p.local -> glyph(pens.laptop, 28f, base - 36f, 44f, r.hue)
                else -> glyph(pens.cloud, 22f, base - 49.6f, 54f, r.hue)
            }
            if (one != null) {
                text(t, 92f, base, fit(t, one.label, BeamType.Row, 444f - 92f), BeamType.Row, r.ink)
                var room = 444f - 92f
                if (right != null) room -= text(t, 444f, base + 40f, right, BeamType.Value, r.sub, Anchor.End) + 20f
                val under = if (p.free) "${p.name}, free tier" else p.name
                text(t, 92f, base + 40f, fit(t, under, BeamType.Label, room), BeamType.Label, r.sub)
            } else {
                val count = text(t, 414f, base, p.models.size.toString(), BeamType.Value, r.sub, Anchor.End)
                chevron(436f, base - 13f, if (focused) r.hue else r.sub)
                text(t, 92f, base, fit(t, p.name, BeamType.Row, 414f - count - 20f - 92f), BeamType.Row, r.ink)
                p.span?.let { text(t, 92f, base + 40f, fit(t, it, BeamType.Label, 444f - 92f), BeamType.Label, r.sub) }
            }
        }
    }
}

/** A row's chevron: 10 by 18 from [x], its middle on [y], 3 round. */
internal fun DrawScope.chevron(x: Float, y: Float, c: Color) {
    val path = Path().apply { moveTo(x, y - 9f); lineTo(x + 10f, y); lineTo(x, y + 9f) }
    drawPath(path, c, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The second level: [p]'s models, its word the header, the one in use first and ticked, the rest
 * as the server sorts them. A free tier says its limit once above the list, baselines 108 and 142,
 * the list 12 under.
 */
@Composable
private fun PlaceModels(pick: Pick, p: Place?, nav: NavState, pens: Pens, keep: (ModelEntry) -> Unit, ask: (ModelEntry, Place) -> Unit) {
    val slot = pick.slot
    val top = pick.top
    val r = pick.r
    val models = remember(p, top) {
        p?.models.orEmpty().let { all -> all.filter { it.id == top?.id } + all.filter { it.id != top?.id } }
    }
    val terms = remember(p) { if (p?.free == true) termsOf(p.models.firstOrNull { it.resets != null || it.shares }) else emptyList() }
    val rows = rememberRows(models.size, nav::pop, itemOf = { it + 1 })
    PageTop(p?.word ?: pick.word, r) {
        Column(Modifier.fillMaxSize()) {
            if (terms.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height((108f - CONTENT_TOP + 34f * (terms.size - 1) + 12f).px)) {
                pageScale {
                    terms.forEachIndexed { i, l ->
                        text(pens.text, 24f, 108f - CONTENT_TOP + 34f * i, fit(pens.text, l, BeamType.Label, 432f), BeamType.Label, r.sub)
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                ShelfList(rows, r) {
                    item { Spacer(Modifier.height((if (terms.isEmpty()) 4f else 0f).px)) }
                    items(models.size, key = { models[it].id }, contentType = { "model" }) { i ->
                        val e = models[i]
                        val inUse = e.id == top?.id
                        val m = remember(e, slot, inUse, p, pens) { ModelDraw(e, slot, inUse, p?.dearest, pens.text, terms = terms.isEmpty()) }
                        ModelRow(m, rows.focus == i, null, pens, r) {
                            rows.focus = i
                            if (inUse) keep(e) else if (p != null) ask(e, p)
                        }
                    }
                    item { Spacer(Modifier.height(48.px)) }
                }
                EdgeFades(r.ground, rows)
            }
        }
    }
}

/**
 * Asking before another model is used (picked over the kit's sheet, 2026-10-10): the page under the
 * sheet's scrim (it shows at 22%), and a card in the middle, ringed in the hue, [ASK_H] tall: the
 * model's name (34) on 52, where it runs on 94 (its place's glyph and name), then cancel and use
 * side by side, 200 by 68 from 32 and 248, their words centred on 39 from the card's foot. The one
 * the wheel is on is filled in the hue, its word Medium in black, the other faint grey; it opens on
 * use. A tap on either takes it, off the card cancels.
 */
@Composable
private fun PickAsk(e: ModelEntry, p: Place, pens: Pens, r: Ramp, onCancel: () -> Unit, onUse: () -> Unit) {
    var focus by remember { mutableIntStateOf(1) }
    WheelTarget { delta -> moved(focus, delta, 1) { focus = it } }
    val y = ASK_TOP
    val keys = y + ASK_H - 84f
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { at ->
            val u = 480f / size.width
            val x = at.x * u
            val ty = at.y * u
            when {
                ty < y || ty > y + ASK_H || x < 16f || x > 464f -> onCancel()
                ty >= keys && ty <= keys + 68f && x in 32f..232f -> onCancel()
                ty >= keys && ty <= keys + 68f && x in 248f..448f -> onUse()
            }
        }
    }) {
        drawRect(r.ground.copy(alpha = SCRIM))
        pageScale {
            val t = pens.text
            ring(r, y, ASK_H)
            text(t, 36f, y + 52f, fit(t, e.label, BeamType.Select, CARD_ROOM), BeamType.Select, r.ink)
            where(t, pens.of(p), p.local, r, 36f, y + 94f, p.name)
            listOf("cancel" to 32f, "use" to 248f).forEachIndexed { i, (word, x) ->
                val on = focus == i
                drawRoundRect(if (on) r.hue else DIM, Offset(x, keys), Size(200f, 68f), CornerRadius(14f))
                text(t, x + 100f, keys + 45f, word, if (on) MEDIUM_SELECT else BeamType.Select, if (on) r.ground else r.ink, Anchor.Middle)
            }
        }
    }
}

private val MEDIUM_SELECT = BeamType.Select.copy(fontWeight = FontWeight.Medium)

/**
 * A model's row, opening into its card as the wheel reaches it: the height grows to the card's
 * while the row fades out and the card, clipped to what is open, fades in. [place] is the glyph
 * of where it runs, for the one in use at the top.
 *
 * The opening is read only where it is laid out and drawn, never in composition: the row and
 * its card are laid out once at the card's full height, the row only says it is [open] of the
 * way there and clips the rest, so each frame of the 180 ms moves one height and two alphas and
 * redraws the card's ring, and the words of both are recorded once (PERFORMANCE.md).
 */
@Composable
private fun ModelRow(m: ModelDraw, focused: Boolean, place: Painter?, pens: Pens, r: Ramp, onTap: () -> Unit) {
    val open = animateFloatAsState(if (focused) 1f else 0f, tween(OPEN_MS), label = "card")
    val shut = LINE_H + if (m.where != null) WHERE_H else 0f
    val full = m.cardHeight + 2 * CARD_GAP
    val height = { shut + (full - shut) * open.value }
    Box(Modifier.fillMaxWidth().clipToBounds().tappable(onTap).layout { content, c ->
        val all = content.measure(Constraints.fixed(c.maxWidth, full.px.roundToPx()))
        layout(all.width, height().px.roundToPx()) { all.place(0, 0) }
    }) {
        Canvas(Modifier.fillMaxWidth().height(shut.px).graphicsLayer {
            alpha = 1f - open.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { pageScale { line(pens.text, pens.check, place, r, m) } }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = open.value }
            .drawBehind { pageScale { ring(r, CARD_GAP, height() - 2 * CARD_GAP) } }
            .graphicsLayer()) { pageScale { card(pens.text, pens.check, place, r, m, CARD_GAP) } }
    }
}

/** One of a card's numbers and the word after it, [w] wide together (the number, 8, the word). */
private class Rate(val num: String, val word: String, val w: Float)

/**
 * What one model shows, worked out once from its entry. [where] is the name of the place it runs
 * in, for the one in use at the top; [terms] whether its card says a free tier's limit (not
 * where the page says it once above the list).
 */
private class ModelDraw(e: ModelEntry, slot: ModelSlot, val inUse: Boolean, dearest: Double?, text: TextMeasurer,
                        val where: String? = null, terms: Boolean = true) {
    val name = e.label
    val local = e.local
    /** The row's right: what it holds in memory on the server's machine, else the month, or free. */
    val short = shortOf(e)
    /** The card's line in the hue. */
    val month = when {
        e.costsNothing -> "free"
        e.month != null -> "≈ ${dollars(e.month)} a month"
        else -> null
    }
    /** The bar: null for none, 0 a dot (free), else its share of the dearest month. */
    val bar: Float? = when {
        e.costsNothing -> 0f
        e.month != null && dearest != null -> (e.month / dearest).toFloat().coerceIn(0f, 1f)
        else -> null
    }
    /** The card's numbers, each with its word after it, every cost with its dollar sign. */
    private val items: List<Pair<String, String>> = when {
        e.free && !e.local -> e.today?.let { listOf(it.toString() to "asked today") } ?: emptyList()
        e.bytes != null -> listOf(gigabytes(e.bytes) to "in memory")
        e.costsNothing -> emptyList()
        slot == ModelSlot.Stt && e.unit != null -> listOf(dollars(e.unit) to "an hour of speech")
        slot != ModelSlot.Chat && e.unit != null -> listOf(dollars(e.unit) to "a picture")
        e.rates != null -> listOfNotNull(
            e.rates.input?.let { rate(it) to "in" }, e.rates.output?.let { rate(it) to "out" },
            e.rates.cached?.let { rate(it) to "cached" },
        )
        else -> emptyList()
    }
    /**
     * Those numbers in lines of the card's room: one line while 20 or more parts them, else the
     * last ones on the next.
     */
    val lines: List<List<Rate>> = run {
        val all = items.map { (num, word) ->
            Rate(num, word, text.measure(num, BeamType.Value).size.width + 8f + text.measure(word, BeamType.Label).size.width)
        }
        val out = mutableListOf<List<Rate>>()
        var cur = mutableListOf<Rate>()
        all.forEach { x ->
            if (cur.isNotEmpty() && cur.sumOf { it.w.toDouble() } + x.w + cur.size * 20f > CARD_ROOM) { out += cur; cur = mutableListOf() }
            cur += x
        }
        if (cur.isNotEmpty()) out += cur
        out
    }
    val note: List<String> = (if (terms) termsOf(e) else emptyList()) +
        listOfNotNull(AT_RELEASE.takeIf { slot == ModelSlot.Stt && inUse && !e.local })
    val noThinking = slot == ModelSlot.Chat && e.thinks == false

    /** The card's height: the name, then each line it has, 26 under the last. */
    val cardHeight: Float = run {
        var y = 50f
        if (where != null) y += WHERE_CARD
        if (month != null) y += 50f
        if (bar != null) y += 20f
        if (lines.isNotEmpty()) y += 46f + 38f * (lines.size - 1)
        y += 34f * note.size
        y + 26f
    }
}

/** What a cloud speech model in use says on its card: it hears a hold only once it is let go. */
private const val AT_RELEASE = "the words come when you let go"

/** A row's right: what a model takes in memory on the server's machine, else its month, or free. */
private fun shortOf(e: ModelEntry): String? = when {
    e.local -> e.bytes?.let { gigabytes(it) }
    e.costsNothing -> "free"
    e.month != null -> dollars(e.month)
    else -> null
}

/**
 * A free tier's limit and terms, in words, as far as the server knows them (the two joined by a
 * semicolon); the provider by its maker's word ("Google").
 */
private fun termsOf(e: ModelEntry?): List<String> {
    if (e == null) return emptyList()
    val said = listOfNotNull(
        e.resets?.let { backAt(it) }?.let { "a daily limit, back at $it" },
        "${e.provider.substringBefore(' ')} may use what you send".takeIf { e.shares },
    )
    return if (said.size == 2) listOf(said[0] + ";", said[1]) else said
}

/** A row: the name, the month right (and the tick), the bar under the name; the one in use at the top has where it runs between them. */
private fun DrawScope.line(text: TextMeasurer, check: Painter, place: Painter?, r: Ramp, m: ModelDraw) {
    var end = 456f
    if (m.inUse) { glyph(check, end - 26f, 16f, 26f, r.hue); end -= 36f }
    var room = end - 24f
    if (m.short != null) room -= text(text, end, 38f, m.short, BeamType.Value, r.sub, Anchor.End) + 20f
    text(text, 24f, 38f, fit(text, m.name, BeamType.Select, room), BeamType.Select, r.ink)
    val under = if (m.where != null) WHERE_H else 0f
    if (m.where != null) where(text, place, m.local, r, 24f, 38f + under, m.where)
    bar(24f, 50f + under, m.bar, r.sub)
}

/**
 * Where a model runs, small on baseline [y]: its place's laptop (28) or cloud (34) and the
 * place's name, in sub.
 */
private fun DrawScope.where(text: TextMeasurer, place: Painter?, local: Boolean, r: Ramp, x: Float, y: Float, name: String) {
    if (place != null) when {
        local -> glyph(place, x, y - 23f, 28f, r.sub)
        else -> glyph(place, x - 2.4f, y - 30.1f, 34f, r.sub)
    }
    text(text, x + 40f, y, fit(text, name, BeamType.Label, 416f - x), BeamType.Label, r.sub)
}

/** The card itself: faint, ringed in the hue, [h] tall from [top] (all of it once open); a provider's row ringed too. */
internal fun DrawScope.ring(r: Ramp, top: Float, h: Float) {
    drawRoundRect(FAINT, Offset(16f, top), Size(448f, h), CornerRadius(16f))
    drawRoundRect(r.hue, Offset(17.5f, top + 1.5f), Size(445f, h - 3f), CornerRadius(15f), style = Stroke(3f))
}

/** The row the wheel is on, opened: the card's words over its [ring], clipped by the rest of the row. */
private fun DrawScope.card(text: TextMeasurer, check: Painter, place: Painter?, r: Ramp, m: ModelDraw, top: Float) {
    var end = 444f
    var room = CARD_ROOM
    if (m.inUse) { glyph(check, end - 28f, top + 24f, 28f, r.hue); end -= 40f; room -= 40f }
    if (m.noThinking) room -= pill(text, end, top + 22f, "no thinking") + 12f
    var y = top + 50f
    text(text, 36f, y, fit(text, m.name, BeamType.Select, room), BeamType.Select, r.ink)
    if (m.where != null) { y += WHERE_CARD; where(text, place, m.local, r, 36f, y, m.where) }
    if (m.month != null) { y += 50f; text(text, 36f, y, m.month, BeamType.FieldText, r.hue) }
    if (m.bar != null) { y += 20f; bar(36f, y - 6f, m.bar, r.hue) }
    if (m.lines.isNotEmpty()) y += 46f
    m.lines.forEachIndexed { i, l ->
        if (i > 0) y += 38f
        // Spread over the room, never more than 34 apart.
        val gap = if (l.size > 1) minOf(34f, (CARD_ROOM - l.sumOf { it.w.toDouble() }.toFloat()) / (l.size - 1)) else 0f
        var x = 36f
        l.forEach {
            val w = text(text, x, y, it.num, BeamType.Value, r.ink)
            text(text, x + w + 8f, y, it.word, BeamType.Label, r.sub)
            x += it.w + gap
        }
    }
    m.note.forEach { y += 34f; text(text, 36f, y, it, BeamType.Label, r.sub) }
}

/** The bar: [share] of 300 (14 at least) in [c], 6 tall; 0 is a dot; null draws nothing. */
private fun DrawScope.bar(x: Float, y: Float, share: Float?, c: Color) {
    when {
        share == null -> Unit
        share == 0f -> dot(x + 4f, y + 3f, 4f, c)
        else -> drawRoundRect(c, Offset(x, y), Size(maxOf(14f, share * 300f), 6f), CornerRadius(3f))
    }
}

/** The mark, grey and round, ending at [xr]; returns its width. */
private fun DrawScope.pill(text: TextMeasurer, xr: Float, y: Float, word: String): Float {
    val w = text.measure(word, BeamType.Label).size.width + 28f
    drawRoundRect(DIM, Offset(xr - w, y), Size(w, 38f), CornerRadius(19f))
    text(text, xr - w / 2, y + 27f, word, BeamType.Label, INK, Anchor.Middle)
    return w
}

/** The list's two edges fading into the ground: the top once it has scrolled, the bottom while there is more. */
@Composable
private fun BoxScope.EdgeFades(ground: Color, rows: Rows) {
    val scrolled by remember { derivedStateOf { rows.list.firstVisibleItemIndex > 0 || rows.list.firstVisibleItemScrollOffset > 0 } }
    val more by remember { derivedStateOf { rows.list.canScrollForward } }
    if (scrolled) Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(40.px)
        .background(Brush.verticalGradient(0f to ground, 1f to ground.copy(alpha = 0f))))
    if (more) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(40.px)
        .background(Brush.verticalGradient(0f to ground.copy(alpha = 0f), 1f to ground)))
}

// ---- money, size and time, as the cards say them ------------------------------------------

/** Dollars as a card says them: above nothing but under a cent, "<$0.01"; past a hundred, whole. */
internal fun dollars(v: Double): String = when {
    v > 0 && v < 0.005 -> "<$0.01"
    v >= 100 -> String.format(Locale.US, "$%.0f", v)
    else -> String.format(Locale.US, "$%.2f", v)
}

/** A rate per 1M tokens with its dollar sign: two decimals, three under a cent. */
private fun rate(v: Double): String = String.format(Locale.US, if (v > 0 && v < 0.01) "$%.3f" else "$%.2f", v)

/** Bytes as gigabytes: one decimal under ten. */
private fun gigabytes(b: Long): String {
    val g = b / 1e9
    return String.format(Locale.US, if (g < 10) "%.1f GB" else "%.0f GB", g)
}

/** When a free tier's day starts again here: the next midnight in [zone], on this device's clock; null for a zone it does not know. */
private fun backAt(zone: String): String? {
    val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return null
    val here = LocalDate.now(z).plusDays(1).atStartOfDay(z).withZoneSameInstant(ZoneId.systemDefault())
    return String.format(Locale.US, "%02d:%02d", here.hour, here.minute)
}
