package com.bleural.croissant.ui.beam

import android.text.format.DateFormat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Place
import com.bleural.croissant.ui.RoomState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.clock
import com.bleural.croissant.ui.daylight
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.fresh
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.rememberSecondTick
import com.bleural.croissant.ui.tappable
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/*
 * Beam's Home (2026-10-27): the date with the weather now beside it, the time big, the croissant
 * mascot under it with its ears in the foot of the digits, and the app dots at the bottom. Nothing
 * else. A tap opens the day in place (HomeDay.kt). Every number is a page pixel on the 480 x 640
 * canvas, y from the very top. The weather is Core's in the shared words (ui/Weather.kt).
 */

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.UK)

/** The date as Home writes it, all lowercase: the day and its number, "wed 23". */
private fun dateOf(now: OffsetDateTime): String = now.format(DATE).lowercase()

// Home, centred on x 240: the date line (Value) on 150, the clock ([BeamType.Clock], 160 Light)
// on 300. The day: both at the top left from 24, the date line on 56, the clock on 124 from 20
// at 0.4 of its size, which is [BeamType.Mid]'s 64.
private const val MID = 240f
private const val DATE_BASE = 150f
private const val CLOCK_BASE = 300f
private const val DAY_DATE_X = 24f
private const val DAY_DATE_BASE = 56f
private const val DAY_CLOCK_X = 20f
private const val DAY_CLOCK_BASE = 124f
private const val DAY_CLOCK_SCALE = 0.4f
// The date line: the date in sub, the sky's glyph after it in the weather's hue, the temperature
// in ink after that. Without a forecast, the date alone.
private const val SKY = 30f
private const val SKY_GAP = 18f
private const val TEMP_GAP = 8f
/** The glyph's top over the line's baseline. */
private const val SKY_RISE = 25f
/** A 12-hour clock's period after its digits, at its own size however small the digits go. */
private const val PERIOD_GAP = 12f

// The croissant on Home: centred on 240, its left ear's tip on 258 so only the ears reach into the
// foot of the digits, 260 wide, its outline 7 (4.6 read too thin on the device). On the day: in the
// top right corner, centred on 392 from 26, 124 wide, its outline 5, as 7 crowds it that small.
private const val CROISSANT_X = 240f
private const val CROISSANT_TOP = 258f
private const val CROISSANT_W = 260f
private const val CROISSANT_INK = 7f
private const val DAY_CROISSANT_X = 392f
private const val DAY_CROISSANT_TOP = 26f
private const val DAY_CROISSANT_W = 124f
private const val DAY_CROISSANT_INK = 5f
/** Around the croissant in its corner, what a tap there takes as the croissant. */
private const val DAY_CROISSANT_REACH = 12f

// The move: the dots rise to the group heads from a tenth of the way, the rest of the row is
// gone by 0.6, and each group comes up over the last 0.35, one after another 0.08 apart.
private const val DOTS_FROM = 0.1f
private const val ROW_GONE_BY = 0.6f
private const val GROUP_FROM = 0.55f
private const val GROUP_STAGGER = 0.08f
private const val GROUP_OVER = 0.35f
/** The day's move, ease-in-out-quart; Ask's croissant moves to its corner on it too. */
internal val DAY_MOVE = CubicBezierEasing(.76f, 0f, .24f, 1f)

// The left edge puts the day away as a page's does (ui/Motion.kt, roomGestures): the same
// 28 dp, the same 70% of the width for the whole way, let go under 0.66 and it goes.
private val EDGE = 28.dp
private const val EDGE_SLOP = 12f
private const val EDGE_TRAVEL = 0.7f
private const val EDGE_KEEP = 0.66f

// The features as dots: r 7 in each hue, from x 42 to 438 (44 apart for the ten the page drew,
// 39.6 for eleven), on y 596. Each owns a slice a pitch wide from the dots' row to the bottom,
// 44 above it: [tileAt]. The room's own places from Ask down, the pinned apps among them, never
// the recents above Ask (2026-10-17), so the pitch is the room's.
private const val DOT_X = 42f
private const val DOT_END = 438f
private fun dotPitch(n: Int) = (DOT_END - DOT_X) / maxOf(1, n - 1)
private const val DOT_Y = 596f
private const val DOT_R = 7f
private const val DOT_REACH = 44f

/**
 * Home and, a tap on it, its day. The croissant plays idle v3 all the while (CroissantMoves.kt) and
 * looks down at the day.
 *
 * A dot goes to its panel through the room's own tap ([tileAt], read by the shell's
 * `roomGestures`), so leaving Home sounds once. A tap on the rest opens the day; on the day, a
 * tap on a group opens its feature and a tap on the croissant puts the day away, as the
 * elastic pull and the left edge do.
 */
@Composable
internal fun Home(room: RoomState, d: PanelDeps, apps: List<InstalledApp>, home: HomeState) {
    val r = HOME
    val twentyFour = DateFormat.is24HourFormat(LocalContext.current)
    val minute by rememberMinuteTick()
    // A running timer's ring counts every second while the day is open; nothing else moves.
    val second = if (home.open && d.timers.isNotEmpty()) rememberSecondTick().value else 0L
    val now = remember(minute, second) { OffsetDateTime.now() }
    val nowMs = now.toInstant().toEpochMilli()
    val w = fresh(d.forecasts.home(nowMs)?.weather, nowMs)
    val glyph = skyGlyph(w?.sky, daylight(w, now.toLocalDateTime()))
    val temp = w?.let { "${it.temp}°" }
    val day = remember(d.events, d.reminders, d.timers, now, twentyFour) { dayOf(d.events, d.reminders, d.timers, now, twentyFour) }
    val groups = remember(day) { dayGroups(day) }
    val dots = room.places.drop(room.entry)
    val text = rememberTextMeasurer(cacheSize = 32)
    PlayCroissant(home.croissant)
    DayMove(home)
    // Home left puts the day away, so Home comes back as Home.
    LaunchedEffect(room, home) { snapshotFlow { room.homeTarget > 0.5f }.collect { if (!it) home.snapShut() } }
    val pull = rememberPull { home.shutDay(Sound.ScreenOut) }
    // While the day is open the wheel is its own: up is the pull, down has nothing to scroll.
    if (home.open) WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    Box(Modifier.fillMaxSize().edgeShutsDay(home)) {
        // The pull hears a finger only through a scroll, so the day has one that never moves.
        val page = if (home.open) Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
        Box(page) {
            Box(Modifier.fillMaxWidth().height(640.px)) {
                Canvas(Modifier.fillMaxSize()) {
                    pageScale {
                        val p = home.progress
                        dateLine(text, dateOf(now), temp, glyph, r, p)
                        clockAt(text, now, twentyFour, r, p)
                        homeDots(dots, apps, groups, p)
                        if (p > 0f) drawDay(text, day, groups, { g -> ((p - GROUP_FROM - g * GROUP_STAGGER) / GROUP_OVER).coerceIn(0f, 1f) }, r)
                    }
                }
                // Its own canvas on its own layer, so a frame of the loop records the croissant
                // again and nothing else: without the layer the page's words were recorded too.
                Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                    pageScale {
                        val p = home.progress
                        croissant(
                            home.croissant.pose(held = Gazes.DOWN, weight = p),
                            lerp(CROISSANT_X, DAY_CROISSANT_X, p), lerp(CROISSANT_TOP, DAY_CROISSANT_TOP, p),
                            lerp(CROISSANT_W, DAY_CROISSANT_W, p), lerp(CROISSANT_INK, DAY_CROISSANT_INK, p), r.ink, r.ground,
                        )
                    }
                }
                if (!home.open) {
                    // Everything over the dots is the way into the day; it ends clear of the dots' slices.
                    Box(Modifier.size(480.px, (DOT_Y - DOT_REACH).px).tappable { if (room.homeTarget > 0.5f) home.openDay() })
                } else {
                    val left = DAY_CROISSANT_X - DAY_CROISSANT_W / 2 - DAY_CROISSANT_REACH
                    val height = DAY_CROISSANT_W * CROISSANT_ART_HEIGHT / CROISSANT_ART_WIDTH + 2 * DAY_CROISSANT_REACH
                    Box(
                        Modifier.offset(left.px, (DAY_CROISSANT_TOP - DAY_CROISSANT_REACH).px)
                            .size((DAY_CROISSANT_W + 2 * DAY_CROISSANT_REACH).px, height.px)
                            .tappable { home.shutDay(Sound.ScreenOut) },
                    )
                    groups.forEach { g ->
                        Box(Modifier.offset(0.px, g.top.px).size(480.px, (g.bottom - g.top).px).tappable { openFeature(room, g.feature) })
                    }
                }
            }
        }
    }
}

/** The day's move: [HomeState.progress] after [HomeState.open], unless a finger on the edge has it. */
@Composable
private fun DayMove(home: HomeState) {
    LaunchedEffect(home.open, home.settle) {
        if (home.scrubbing) return@LaunchedEffect
        val to = if (home.open) 1f else 0f
        if (home.progress == to) return@LaunchedEffect
        animate(home.progress, to, animationSpec = tween(if (home.open) DAY_IN_MS else DAY_OUT_MS, easing = DAY_MOVE)) { v, _ -> home.progress = v }
    }
}

/** A feature from its group on the day: Home left for its panel, opened, as the camera key does it. */
private fun openFeature(room: RoomState, f: Feature) {
    if (room.homeTarget <= 0.5f) return
    room.goTo(room.placeOf(f))
    room.leaveHome()
    room.openPanel()
    Cues.play(Sound.ScreenIn)
}

/** The left edge scrubs the day back toward Home, and let go under [EDGE_KEEP] puts it away. */
private fun Modifier.edgeShutsDay(home: HomeState): Modifier = pointerInput(home) {
    val edge = EDGE.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!home.open || down.position.x >= edge) return@awaitEachGesture
        val start = home.progress
        var began = false
        drag(down.id) { c ->
            val dx = c.position.x - down.position.x
            if (!began && abs(dx) < EDGE_SLOP) return@drag
            if (!began) { began = true; home.scrubbing = true }
            c.consume()
            home.progress = (start - dx / (size.width * EDGE_TRAVEL)).coerceIn(0f, 1f)
        }
        if (!began) return@awaitEachGesture
        home.scrubbing = false
        if (home.progress < EDGE_KEEP) home.shutDay(Sound.EdgeBack) else home.resettle()
    }
}

/** The date in sub, then the sky's glyph and the temperature when there is a forecast: centred on Home, at the top left on the day. */
private fun DrawScope.dateLine(m: TextMeasurer, date: String, temp: String?, glyph: SkyGlyph, r: Ramp, p: Float) {
    val dw = m.measure(date, BeamType.Value).size.width
    val tw = temp?.let { m.measure(it, BeamType.Value).size.width } ?: 0
    val whole = if (temp == null) dw.toFloat() else dw + SKY_GAP + SKY + TEMP_GAP + tw
    val x = lerp(MID - whole / 2, DAY_DATE_X, p)
    val base = lerp(DATE_BASE, DAY_DATE_BASE, p)
    text(m, x, base, date, BeamType.Value, r.sub)
    if (temp == null) return
    sky(glyph, x + dw + SKY_GAP, base - SKY_RISE, SKY, rampOf(Feature.Weather).hue)
    text(m, x + dw + SKY_GAP + SKY + TEMP_GAP, base, temp, BeamType.Value, r.ink)
}

/**
 * The clock in [BeamType.Clock]: centred on Home on baseline 300, at the top left on the day at
 * [DAY_CLOCK_SCALE] of its size. A 12-hour clock's period follows the digits in sub, at its own
 * size, so it never goes under the page's 26.
 */
private fun DrawScope.clockAt(m: TextMeasurer, now: OffsetDateTime, twentyFour: Boolean, r: Ramp, p: Float) {
    val digits = if (twentyFour) clock(now, true) else now.format(DateTimeFormatter.ofPattern("h:mm", Locale.UK))
    val period = if (twentyFour) null else now.format(DateTimeFormatter.ofPattern("a", Locale.UK)).lowercase()
    val dw = m.measure(digits, BeamType.Clock).size.width
    val pw = period?.let { PERIOD_GAP + m.measure(it, BeamType.Value).size.width } ?: 0f
    val s = lerp(1f, DAY_CLOCK_SCALE, p)
    val left = lerp(MID - (dw + pw) / 2, DAY_CLOCK_X, p)
    val base = lerp(CLOCK_BASE, DAY_CLOCK_BASE, p)
    withTransform({ translate(left, base); scale(s, s, Offset.Zero) }) { text(m, 0f, 0f, digits, BeamType.Clock, r.ink) }
    period?.let { text(m, left + dw * s + PERIOD_GAP, base, it, BeamType.Value, r.sub) }
}

/**
 * The row of dots. On the way to the day, the dot of each feature that heads a group rises to
 * its head and the rest fade; a group whose feature has no dot on the row has its head's dot
 * come up in place.
 */
private fun DrawScope.homeDots(dots: List<Place>, apps: List<InstalledApp>, groups: List<DayGroup>, p: Float) {
    val pitch = dotPitch(dots.size)
    val rise = ((p - DOTS_FROM) / (1f - DOTS_FROM)).coerceIn(0f, 1f)
    val rest = (1f - p / ROW_GONE_BY).coerceIn(0f, 1f)
    dots.forEachIndexed { i, place ->
        val x = DOT_X + i * pitch
        val hue = rampOf(place, apps).hue
        val g = groups.firstOrNull { place == Place.Of(it.feature) }
        if (g == null) { if (rest > 0f) dot(x, DOT_Y, DOT_R, hue.copy(alpha = rest)); return@forEachIndexed }
        dot(lerp(x, HEAD_DOT_X, rise), lerp(DOT_Y, g.head - HEAD_DOT_RISE, rise), DOT_R, hue)
    }
    for (g in groups) {
        if (dots.none { it == Place.Of(g.feature) }) dot(HEAD_DOT_X, g.head - HEAD_DOT_RISE, DOT_R, rampOf(g.feature).hue.copy(alpha = rise))
    }
}

/**
 * The place whose dot on Home is under a point in page pixels, or null; null too away from
 * Home and while the day is open, the dots having gone up to it. Generous: each dot owns its
 * pitch's slice of the row, the outer two to the page's edges, from 44 above the dots to the
 * bottom. The dots start at Ask ([RoomState.entry]).
 */
internal fun tileAt(room: RoomState, home: HomeState, x: Float, y: Float): Int? {
    if (room.homeTarget <= 0.5f || home.open || y < DOT_Y - DOT_REACH) return null
    val n = room.count - room.entry
    val pitch = dotPitch(n)
    val col = floor((x - DOT_X + pitch / 2) / pitch).toInt()
    return room.entry + col.coerceIn(0, n - 1)
}
