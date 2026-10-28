package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Place
import com.bleural.croissant.ui.RoomLine
import com.bleural.croissant.ui.RoomState
import com.bleural.croissant.ui.SideWheel
import com.bleural.croissant.ui.glance
import com.bleural.croissant.ui.headAlpha
import com.bleural.croissant.ui.headDy
import com.bleural.croissant.ui.rememberAppGlyph
import com.bleural.croissant.ui.rememberMinuteTick
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The room as the side wheel: Beam's skin on the shared geometry (ui/SideWheel.kt). The features'
 * glyphs round the rim, the one in hand at nine o'clock a disc in its hue, its name as big as fits
 * beside it and its live line under that. Every number is a page pixel on the 480 x 640 canvas, y
 * from the very top. The wheel runs off the top edge as it does off the bottom; it stopped at y 58,
 * under the top bar, until the bar went (2026-10-01).
 */

/** The wheel, at the page's numbers; how many places it has is the room's (2026-10-17: recents and pins come and go). */
internal val WHEEL = SideWheel(count = PLACES.size)

/**
 * Where the wheel is turned to: the room's, one feature further back on Home, so leaving Home
 * turns Ask up into the slot as a scroll down would.
 */
internal fun wheelPos(room: RoomState): Float = room.pos - room.home

/** The feature whose disc on the rim is under a point in page pixels, or null; null too on Home. */
internal fun rimAt(room: RoomState, x: Float, y: Float): Int? =
    if (room.homeTarget > 0.5f) null else WHEEL.at(wheelPos(room), x, y, n = room.count)

/** The wheel's edge past the apps' circle, and its knurl's depth inward from there. */
private const val EDGE = 52f
private const val KNURL = 12f
/** How far right the wheel sits on Home: all of it off the page. */
private const val SLIDE = 240f
/** The disc in hand, and the rest at the slot's neighbours, 1.2 smaller for each further away. */
private const val ON_R = 44f
private const val OFF_R = 30f
private const val OFF_SHRINK = 1.2f
/**
 * A recent above Ask (2026-10-17), off the slot: its ring, glyph and dot at half their strength,
 * full again as it turns into the slot.
 */
private const val RECENT = 0.5f
/** A live feature's white dot: r 5, 12 clear of its ring. */
private const val LIVE_R = 5f
private const val LIVE_GAP = 12f

// The words: the name from x 24, as big as fits 222 across up to 88, its baseline
// a third of its size under the slot's centre; the live line 52 under it.
private const val WORDS_X = 24f
private const val NAME_ROOM = 222f
private const val NAME_MAX = 88f
private const val LINE_UNDER = 52f
/** The widest the live line runs, clear of the disc in hand. */
private const val LINE_ROOM = 232f
/** Between the value and its words. */
private const val LINE_GAP = 10f

/** What is live, as Home says it (ui/Glance.kt): a timer running, an event ahead today, a to-do. */
@Composable
internal fun rememberLive(d: PanelDeps): Set<Feature> {
    val minute by rememberMinuteTick()
    return remember(d.events, d.reminders, d.timers, minute) {
        val g = glance(d.events, d.reminders, d.timers, OffsetDateTime.now())
        setOfNotNull(
            Feature.Timers.takeIf { g.timer != null },
            Feature.Calendar.takeIf { g.event != null },
            Feature.Reminders.takeIf { g.todo.isNotEmpty() },
        )
    }
}

/**
 * The room: the wheel as tall as the page rising from the right edge, a DIM edge knurled as the
 * real one is, and the features on its rim, turned continuously by the room's `pos`, so a tick or a
 * drag turns it and a disc grows into the slot as it arrives. The one in hand is filled in its hue
 * with its glyph in black; the rest are rings in their hue, smaller further out, a white dot beside
 * one that is [live]. Beside the slot, the name of the feature nearest it lowercase in its hue and
 * its [lines] under it, the value in the hue and its words in white; they fade and drift as a
 * header does (ui/Motion.kt), so one name gives way to the next half way.
 *
 * The room's places (2026-10-17): the recents first, above Ask, dimmed ([RECENT]) until one is
 * in the slot; an Atelier app, pinned or recent, its Lucide glyph in its own hue ([apps]) and its
 * name, as on the pegboard, with no line under it.
 *
 * On Home the wheel sits off the right edge, a feature back: leaving Home slides it in and
 * turns Ask into the slot, as Home fades. It goes as the feature opens. Read in the draw, so a
 * frame of motion recomposes nothing.
 */
@Composable
internal fun SideWheelRoom(room: RoomState, apps: List<InstalledApp>, lines: (Feature) -> RoomLine, live: Set<Feature>) {
    val places = room.places
    val looks = places.mapIndexed { i, p -> key(i, p) { rememberLook(p, apps, lines) } }
    val text = rememberTextMeasurer(cacheSize = 32)
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - room.open }) {
        pageScale {
            if (room.open > 0.99f || room.home > 0.99f) return@pageScale
            val p = wheelPos(room)
            translate(room.home * SLIDE, 0f) {
                val c = Offset(WHEEL.cx, WHEEL.cy)
                val outer = WHEEL.radius + EDGE
                drawCircle(DIM, outer, c, style = Stroke(3f))
                // The knurl, four ticks an app between the apps' angles, turning with them.
                val q = WHEEL.step / 4f
                val turn = ((p * WHEEL.step) % q + q) % q
                for (k in -24..24) {
                    val t = (180f + q / 2f + k * q + turn) * PI.toFloat() / 180f
                    val u = Offset(cos(t), sin(t))
                    drawLine(DIM, c + u * (outer - KNURL), c + u * outer, 2.5f, StrokeCap.Round)
                }
                for ((i, look) in looks.withIndex()) {
                    val s = WHEEL.spot(i, p)
                    if (s.y < -ON_R || s.y > 640f + ON_R) continue
                    val hue = look.hue
                    val on = (1f - abs(s.d)).coerceIn(0f, 1f)
                    val k = if (i < room.entry) lerp(RECENT, 1f, on) else 1f
                    val r = lerp(OFF_R - abs(s.d) * OFF_SHRINK, ON_R, on)
                    val at = Offset(s.x, s.y)
                    if (on < 1f) drawCircle(hue.copy(alpha = (1f - on) * k), r, at, style = Stroke(3f))
                    if (on > 0f) drawCircle(hue.copy(alpha = on * k), r, at)
                    val g = r * (1f + 0.04f * on)
                    glyph(look.glyph, s.x - g / 2f, s.y - g / 2f, g, lerp(hue, GROUND, on).copy(alpha = k))
                    if (look.live(live) && on < 1f) dot(s.x + r + LIVE_GAP, s.y, LIVE_R, INK.copy(alpha = (1f - on) * k))
                }
            }
            for ((i, look) in looks.withIndex()) {
                val a = room.headAlpha(i)
                if (a <= 0.01f) continue
                val hue = look.hue
                val name = look.name
                val w = text.measure(name, BeamType.Name).size.width
                val size = minOf(NAME_MAX, NAME_MAX * NAME_ROOM / w)
                val style = BeamType.Name.copy(fontSize = size.pxSp, letterSpacing = (-size * 0.03f).pxSp)
                val base = WHEEL.cy + size * 0.34f
                translate(0f, room.headDy(i)) {
                    text(text, WORDS_X, base, name, style, hue.copy(alpha = a))
                    val (value, muted) = look.words
                    var x = WORDS_X
                    value?.let { x += text(text, x, base + LINE_UNDER, it, BeamType.Value, hue.copy(alpha = a)) + LINE_GAP }
                    muted?.let { text(text, x, base + LINE_UNDER, fit(text, it, BeamType.Value, WORDS_X + LINE_ROOM - x), BeamType.Value, INK.copy(alpha = a)) }
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** What the wheel draws for one place: its glyph, hue, name and live line. */
private class Look(val place: Place, val glyph: Painter, val hue: Color, val name: String, val words: Pair<String?, String?>) {
    fun live(live: Set<Feature>) = place is Place.Of && place.feature in live
}

@Composable
private fun rememberLook(p: Place, apps: List<InstalledApp>, lines: (Feature) -> RoomLine): Look = when (p) {
    is Place.Of -> Look(
        p, rememberVectorPainter(p.feature.glyph), rampOf(p.feature).hue, p.feature.word.lowercase(Locale.UK),
        lines(p.feature).let { it.value?.lowercase(Locale.UK) to it.muted?.lowercase(Locale.UK) },
    )
    is Place.App -> {
        val app = apps.firstOrNull { it.id == p.id }
        val vector = rememberAppGlyph(app?.manifest?.glyph)
        Look(p, rememberVectorPainter(vector ?: NO_GLYPH), rampOf(p, apps).hue, (app?.manifest?.name ?: p.id).lowercase(Locale.UK), null to null)
    }
    is Place.Agent -> agentLook(p.id).let { look -> Look(p, rememberVectorPainter(look.glyph), look.hue, p.id.lowercase(Locale.UK), null to null) }
}
