package com.bleural.croissant.ui.beam

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WeatherView
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.dayWord
import com.bleural.croissant.ui.daylight
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.hourLabel
import com.bleural.croissant.ui.nowTemp
import com.bleural.croissant.ui.pageSwipe
import com.bleural.croissant.ui.placeName
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.shownPlace
import com.bleural.croissant.ui.skyIcon
import com.bleural.croissant.ui.skyWord
import com.bleural.croissant.ui.smoothLine
import com.bleural.croissant.ui.tappable
import com.bleural.croissant.ui.todayHours
import com.bleural.croissant.ui.weekDays
import com.bleural.croissant.weather.Forecasts
import com.bleural.croissant.weather.Found
import com.bleural.croissant.weather.Place
import com.bleural.croissant.weather.Weather
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/*
 * Weather in Beam (today as the curve, picked 2026-09-29). Every number is a page pixel on the
 * 480 x 640 canvas, y from the very top. What is shown is the shared words and hours
 * (ui/Weather.kt); the places and the search are Core's.
 */

/** One screen of content under the header: today, and the week a turn of the wheel under it. */
private const val PAGE = 640f - CONTENT_TOP

// The chart: the hours from x 40 to 440, now and every third hour labelled; the
// line about y 480, 8.5 a degree at most and 80 high at most; rain from 540 up, 0.9 a percent.
private const val CHART_X0 = 40f
private const val CHART_X1 = 440f
private const val CHART_MID = 480f
private const val CHART_TALL = 80f
private const val PER_DEGREE = 8.5f
private const val RAIN_FOOT = 540f

// The week: a row every 66 from 142, the range bar from 240 to 400.
private const val WEEK_TOP = 142f
private const val WEEK_PITCH = 66f
private const val BAR_X0 = 240f
private const val BAR_X1 = 400f

// ---- the router ---------------------------------------------------------------------------

/** Weather's pushed pages: its places and adding one. */
@Composable
internal fun WeatherPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.WeatherPlaces -> {
            val f by core.weather.forecasts.collectAsStateWithLifecycle()
            PlacesPage(
                f,
                onShow = { WeatherView.shown = it.id; nav.pop() },
                onAdd = { nav.push(Screen.WeatherAdd) },
                onPin = core.weather::pin, onRemove = core.weather::remove, onBack = nav::pop,
            )
        }
        Screen.WeatherAdd -> {
            val found by core.weather.found.collectAsStateWithLifecycle()
            DisposableEffect(Unit) { onDispose { core.weather.search("") } }
            AddPlacePage(found, onSearch = core.weather::search, onBack = nav::pop, onPick = {
                WeatherView.shown = core.weather.add(it)
                // Straight to its page: the places under this one go with it.
                nav.drop(Screen.WeatherPlaces)
                nav.pop(Sound.Commit)
            })
        }
        else -> Unit
    }
}

// ---- the page -----------------------------------------------------------------------------

/**
 * The Weather page (the week under it): the place's name, the GPS arrow after it for here and the
 * pin for the pinned place, the places as dots on the right; now big with the sky beside it, the
 * sky's word and the day's high and low; the next twelve hours as a line in the hue with the
 * degrees every third hour and now white, the chance of rain as soft columns under it and a rain
 * cloud under each hour it is likely. The week is a turn of the wheel under it: each day's sky, its
 * low, its range on the week's own scale and its high, today in the hue.
 *
 * A swipe sideways goes to the next place (the left edge stays the way back); the wheel goes
 * down to the week and back, and up at the top is the step back through the pull, as a finger
 * pulled down is. A tap on the name opens the places.
 */
@Composable
internal fun WeatherPage(f: Forecasts, onPlaces: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Weather)
    val twentyFour = DateFormat.is24HourFormat(LocalContext.current)
    val minute by rememberMinuteTick()
    val now = remember(minute) { LocalDateTime.now() }
    val nowMs = System.currentTimeMillis()
    val place = shownPlace(f)
    val w = f.of(place)
    val at = f.places.indexOf(place)
    val scroll = rememberScrollState()
    val pull = rememberPull(onBack)
    val scope = rememberCoroutineScope()
    WheelTarget { delta ->
        when {
            delta < 0 && scroll.value == 0 -> pull.stepBack()
            delta < 0 -> scope.launch { scroll.animateScrollTo(0) }
            else -> scope.launch { scroll.animateScrollTo(scroll.maxValue) }
        }
        false
    }
    val x = remember { Animatable(0f) }
    val text = rememberTextMeasurer(cacheSize = 32)
    val here = rememberVectorPainter(Glyphs.Here)
    val pin = rememberVectorPainter(Glyphs.Pin)
    Box(Modifier.fillMaxSize().background(r.ground)) {
        Header("weather", r)
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = CONTENT_TOP.px)) {
            val h = maxHeight
            Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                Box(
                    Modifier.fillMaxWidth().height(if (w == null) h else h * 2)
                        .pageSwipe(x, can = { (at + it) in f.places.indices }, step = { WeatherView.shown = f.places[at + it].id }),
                ) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = x.value }) {
                        pageScale {
                            translate(0f, -DRAWN_TOP) {
                                val u = size.width / 480f
                                // The dots say which place this is, so they stay where they are while the page moves.
                                translate(-x.value / u, 0f) { placeDots(f.places.size, at, r) }
                                top(text, place, w, place.id == f.pinned, now, nowMs, here, pin, r)
                                if (w != null) {
                                    // The chart stays at the foot, where it was under the top bar; the rest is [LIFT] higher.
                                    translate(0f, LIFT) { today(text, todayHours(w, now, nowMs), twentyFour, r) }
                                    translate(0f, PAGE) { week(text, weekDays(w, now.toLocalDate()), now, r) }
                                }
                            }
                        }
                    }
                    // The name: a tap opens the places.
                    Box(Modifier.offset(y = (104f - DRAWN_TOP).px).size(380.px, 60.px).tappable(onPlaces))
                }
            }
        }
    }
}

/** The places as dots at the right: this one larger in the hue, the rest faint. One place alone says nothing. */
private fun DrawScope.placeDots(n: Int, at: Int, r: Ramp) {
    if (n < 2) return
    for (j in 0 until n) dot(456f - (n - 1 - j) * 20f, 137f, if (j == at) 6f else 5f, if (j == at) r.hue else FAINT)
}

/** The place, now and the day; without a forecast, why not in sub where now would be. */
private fun DrawScope.top(m: TextMeasurer, p: Place, w: Weather?, pinned: Boolean, now: LocalDateTime, nowMs: Long, here: Painter, pin: Painter, r: Ramp) {
    val name = fit(m, placeName(p).lowercase(Locale.UK), BeamType.Row, 320f)
    val nw = text(m, 24f, 150f, name, BeamType.Row, r.ink)
    if (p.here) glyph(here, 24f + nw + 12f, 124f, 26f, r.hue)
    if (pinned) glyph(pin, 24f + nw + 12f, 124f, 26f, r.hue)
    if (w == null) {
        text(m, 24f, 230f, if (p.here && p.lat.isNaN()) "no location yet" else "no forecast yet", BeamType.Body, r.sub)
        return
    }
    val day = daylight(w, now)
    text(m, 14f, 300f, "${nowTemp(w, now, nowMs)}°", BeamType.Big, r.ink)
    sky(skyGlyph(w.sky, day), 318f, 174f, 132f, r.hue)
    val ww = text(m, 24f, 350f, skyWord(w, day), BeamType.Value, r.ink)
    weekDays(w, now.toLocalDate()).firstOrNull()?.takeIf { it.date == now.toLocalDate() }?.let { d ->
        text(m, 24f + ww + 20f, 350f, "${d.max}° / ${d.min}°", BeamType.Value, r.sub)
    }
}

/**
 * Today as the curve: the line through every hour in the hue, 4 wide, smoothed (Catmull-Rom); a dot
 * and the degrees every third hour, now white and larger; rain as rounded columns 16 wide under it
 * from a chance of 20, taller and stronger with it; a rain cloud under each hour of half or more;
 * now and every third hour's time at the foot.
 */
private fun DrawScope.today(m: TextMeasurer, hours: List<com.bleural.croissant.weather.Hour>, twentyFour: Boolean, r: Ramp) {
    if (hours.size < 2) return
    fun xOf(k: Int) = CHART_X0 + (CHART_X1 - CHART_X0) * k / 12f
    val lo = hours.minOf { it.temp }
    val hi = hours.maxOf { it.temp }
    val k = if (hi == lo) PER_DEGREE else minOf(PER_DEGREE, CHART_TALL / (hi - lo))
    val mid = (hi + lo) / 2f
    fun yOf(t: Int) = CHART_MID + (mid - t) * k
    hours.forEachIndexed { j, h ->
        if (h.rainChance >= 20) {
            val tall = h.rainChance * 0.9f
            rect(xOf(j) - 8f, RAIN_FOOT - tall, 16f, tall, r.hue.copy(alpha = (0.15f + h.rainChance / 250f).coerceAtMost(1f)), 8f)
        }
    }
    val pts = hours.mapIndexed { j, h -> Offset(xOf(j), yOf(h.temp)) }
    drawPath(smoothLine(pts), r.hue, style = Stroke(4f, cap = StrokeCap.Round))
    for (j in pts.indices step 3) {
        val p = pts[j]
        dot(p.x, p.y, if (j == 0) 8f else 6f, if (j == 0) r.ink else r.hue)
        text(m, p.x, p.y - 18f, "${hours[j].temp}°", BeamType.Label, r.ink, Anchor.Middle)
        text(m, p.x, 604f, if (j == 0) "now" else hourLabel(hours[j].at, twentyFour), BeamType.Label, if (j == 0) r.ink else r.sub, Anchor.Middle)
    }
    hours.forEachIndexed { j, h -> if (h.rainChance >= 50) sky(SkyGlyph.Rain, xOf(j) - 11f, 550f, 22f, r.hue) }
}

/** The week: "this week", then each day's word (today in the hue), its sky, its low, its range on the week's own scale, its high. */
private fun DrawScope.week(m: TextMeasurer, days: List<com.bleural.croissant.weather.Day>, now: LocalDateTime, r: Ramp) {
    if (days.isEmpty()) return
    text(m, 24f, 132f, "this week", BeamType.Label, r.sub)
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }
    fun xOf(t: Int) = if (hi == lo) BAR_X0 else BAR_X0 + (BAR_X1 - BAR_X0) * (t - lo) / (hi - lo).toFloat()
    val today = now.toLocalDate()
    days.forEachIndexed { j, d ->
        val base = WEEK_TOP + j * WEEK_PITCH + 45f
        text(m, 24f, base, dayWord(d.date, today), BeamType.Row, if (d.date == today) r.hue else r.ink)
        sky(skyGlyph(d.sky, true), 150f, base - 30f, 36f, r.hue)
        text(m, 226f, base - 2f, "${d.min}°", BeamType.Value, r.sub, Anchor.End)
        rect(BAR_X0, base - 16f, BAR_X1 - BAR_X0, 8f, FAINT, 4f)
        val a = xOf(d.min)
        rect(a, base - 16f, maxOf(8f, xOf(d.max) - a), 8f, r.hue, 4f)
        text(m, 456f, base - 2f, "${d.max}°", BeamType.Value, r.ink, Anchor.End)
    }
}

// ---- places -------------------------------------------------------------------------------

/**
 * Places: here first, then the ones added, each its name (the GPS arrow after here's, the pin after
 * the pinned one's), its sky and its degrees at the right; "add a place" with the plus at the foot.
 * The wheel steps them; a tap shows a place's page, a long press on an added one brings its sheet:
 * pin to home (unpin when it is), remove in the danger red, cancel. Here has no sheet: it cannot be
 * pinned or removed.
 */
@Composable
internal fun PlacesPage(f: Forecasts, onShow: (Place) -> Unit, onAdd: () -> Unit, onPin: (String?) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Weather)
    val n = f.places.size
    val rows = rememberRows(n + 1, onBack, itemOf = { it + 1 })
    val minute by rememberMinuteTick()
    val now = remember(minute) { LocalDateTime.now() }
    var asking by remember { mutableStateOf<Place?>(null) }
    // Opened on the place the page was on.
    LaunchedEffect(Unit) { rows.focus = f.places.indexOfFirst { it.id == WeatherView.shown }.coerceAtLeast(0) }
    Box(Modifier.fillMaxSize()) {
        PageTop("places", r) {
            ShelfList(rows, r) {
                item { Spacer(Modifier.height(4.px)) }
                items(n, key = { f.places[it].id }) { i ->
                    val p = f.places[i]
                    PlaceRow(
                        p, f.of(p), pinned = p.id == f.pinned, focused = rows.focus == i, now, r,
                        onClick = { rows.focus = i; onShow(p) },
                        onLongClick = if (p.here) null else ({ rows.focus = i; asking = p; Cues.play(Sound.SheetIn) }),
                    )
                }
                item(key = "add") { ShelfRow("add a place", r, focused = rows.focus == n, glyph = Glyphs.Plus, onClick = { rows.focus = n; onAdd() }) }
                item { Spacer(Modifier.height(22.px)) }
            }
        }
        asking?.let { p ->
            val pinned = p.id == f.pinned
            Sheet(
                r,
                listOf(
                    SheetBand(if (pinned) "unpin" else "pin to home") { asking = null; onPin(if (pinned) null else p.id); Cues.play(Sound.Commit) },
                    SheetBand("remove", DANGER) { asking = null; onRemove(p.id); Cues.play(Sound.Destroy) },
                ),
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
            )
        }
    }
}

/**
 * One place (a row of Places, the kit's 64): the name at 24 (Medium in the hue when focused), the
 * GPS arrow (sub, the hue when focused) or the pin (the hue) 12 after it at 24; at the right its
 * sky at 34 and its degrees (the value's 28), both sub, the hue when focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(p: Place, w: Weather?, pinned: Boolean, focused: Boolean, now: LocalDateTime, r: Ramp, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val side = if (focused) r.hue else r.sub
    val press = if (onLongClick == null) Modifier.tappable(onClick) else Modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = onClick, onLongClick = { Cues.play(Sound.LongPress); onLongClick() },
    )
    Row(Modifier.fillMaxWidth().height(ROW.px).then(press).padding(start = 24.px, end = 24.px)) {
        // The name and its mark take what the sky and the degrees leave, the name cut first.
        Row(Modifier.weight(1f)) {
            Text(
                placeName(p).lowercase(Locale.UK), style = if (focused) BeamType.RowFocused else BeamType.Row,
                color = if (focused) r.hue else r.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).paddingFromBaseline(top = rowBase(ROW).px),
            )
            when {
                p.here -> Icon(Glyphs.Here, null, Modifier.padding(start = 12.px, top = 19.px).size(24.px), side)
                pinned -> Icon(Glyphs.Pin, null, Modifier.padding(start = 12.px, top = 19.px).size(24.px), r.hue)
            }
        }
        if (w != null) {
            Icon(skyIcon(w.sky, daylight(w, now)), null, Modifier.padding(start = 20.px, top = 15.px).size(34.px), side)
            Spacer(Modifier.width(18.px))
            Text("${nowTemp(w, now, System.currentTimeMillis())}°", style = BeamType.Value, color = side, maxLines = 1, softWrap = false,
                modifier = Modifier.paddingFromBaseline(top = rowBase(ROW).px))
        }
    }
}

// ---- adding a place -----------------------------------------------------------------------

/**
 * Add a place: the kit's [Field] with the keyboard up, and the places that match as rows under it,
 * each its name and its country at the right (its region where two share both). A tap adds one and
 * opens its page. The rows stay over the keyboard, but on the R1 it leaves room for none, so its
 * search key puts it away and the matches show; the wheel steps them, up from the first is the step
 * back.
 */
@Composable
internal fun AddPlacePage(found: List<Found>, onSearch: (String) -> Unit, onPick: (Found) -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Weather)
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    val rows = rememberRows(found.size, onBack)
    LaunchedEffect(found) { rows.focus = 0; rows.list.scrollToItem(0) }
    PageTop("add a place", r) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(22.px))
            Field(
                query, { query = it; onSearch(it) }, r, Modifier.focusRequester(requester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            Spacer(Modifier.height(24.px))
            ShelfList(rows, r, Modifier.weight(1f)) {
                items(found.size) { i ->
                    val p = found[i]
                    val twin = found.count { it.name == p.name && it.country == p.country } > 1
                    ShelfRow(
                        p.name.lowercase(Locale.UK), r, focused = rows.focus == i,
                        value = (if (twin && p.region.isNotBlank()) p.region else p.country).lowercase(Locale.UK),
                        onClick = { rows.focus = i; onPick(p) },
                    )
                }
            }
        }
    }
}
