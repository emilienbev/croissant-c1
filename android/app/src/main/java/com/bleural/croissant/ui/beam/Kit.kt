package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.PullState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.held
import com.bleural.croissant.ui.keepInView
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.rememberValueText
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The Beam kit: every screen is assembled from these, with an earlier design's names and signatures
 * and Beam's look. Numbers are page pixels on the 480 x 640 canvas (1 sp = 1.75 px). Canvases draw
 * in page pixels ([pageScale]); layouts use `.px`. Nothing is drawn round a group: no shelves, no
 * cards, no hairlines, no pills. The only fills are the hue (keys, badges, toggle tracks, meters)
 * and faint tracks; a focused row is only its words in the hue.
 */

// ---- drawing in page pixels ----------------------------------------------------------

/** Draws [block] in page pixels: 480 across whatever the canvas really is. */
internal inline fun DrawScope.pageScale(crossinline block: DrawScope.() -> Unit) {
    val u = size.width / 480f
    scale(u, u, pivot = Offset.Zero) { block() }
}

internal fun DrawScope.dot(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(c, r, Offset(cx, cy))

internal fun DrawScope.rect(x: Float, y: Float, w: Float, h: Float, c: Color, r: Float = 0f) =
    if (r == 0f) drawRect(c, Offset(x, y), Size(w, h)) else drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r))

internal enum class Anchor { Start, Middle, End }

/** [s] with its baseline at [y], as SVG places text, from [x] or centred on it. Returns its width. */
internal fun DrawScope.text(m: TextMeasurer, x: Float, y: Float, s: String, style: TextStyle, c: Color, anchor: Anchor = Anchor.Start): Float {
    val laid = m.measure(s, style)
    val w = laid.size.width.toFloat()
    val left = when (anchor) { Anchor.Start -> x; Anchor.Middle -> x - w / 2; Anchor.End -> x - w }
    drawText(laid, c, Offset(left, y - laid.firstBaseline))
    return w
}

/** [s] as it fits in [room] px of [style], cut with an ellipsis when it does not. */
internal fun fit(m: TextMeasurer, s: String, style: TextStyle, room: Float): String {
    fun w(t: String) = m.measure(t, style).size.width
    if (w(s) <= room) return s
    var lo = 0
    var hi = s.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (w(s.take(mid).trimEnd() + "…") <= room) lo = mid else hi = mid - 1
    }
    return s.take(lo).trimEnd() + "…"
}

/** A glyph of the set (ui/Icons.kt) at [s] px with its top left at ([x], [y]). */
internal fun DrawScope.glyph(p: Painter, x: Float, y: Float, s: Float, c: Color) {
    translate(x, y) { with(p) { draw(Size(s, s), colorFilter = ColorFilter.tint(c)) } }
}

// ---- header -------------------------------------------------------------------------

/**
 * The header: the feature's word, lowercased here, in [BeamType.Header] (34 Medium) in ink at x 22
 * on baseline 52. [CONTENT_TOP] px, where a page's content starts. The header line is the word
 * alone: [value] and [valueColour] are an earlier design's and are drawn nowhere (a value the page
 * needs goes on the page). It was on baseline 86 with the content from 100 while the top bar took
 * the first 46; without the bar (2026-10-01) everything under it is 34 higher, which leaves the
 * word as far from the top as from the side.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Header(word: String, r: Ramp, value: String? = null, valueColour: Color = r.sub) {
    Box(Modifier.fillMaxWidth().height(CONTENT_TOP.px)) {
        Text(word.lowercase(), style = BeamType.Header, color = r.ink, maxLines = 1,
            modifier = Modifier.padding(start = 22.px, end = 22.px).paddingFromBaseline(top = HEADER_BASE.px))
    }
}

/** The header's baseline. */
internal const val HEADER_BASE = 52f

/** Where a page's content starts under the [Header]: y 66. */
internal const val CONTENT_TOP = 66f

/**
 * How much higher everything under the header is than in the layout with the top bar, which put
 * the header on baseline 86 and the content from 100. A page y in that layout less this is where
 * the page draws it.
 */
internal const val LIFT = 34f

/** Where the top bar's layout started a page's content, under its header: a y there less this is a y in the content. */
internal const val DRAWN_TOP = CONTENT_TOP + LIFT

/**
 * A Beam page: the black ground, its [Header], and [content] from y 66.
 * [value] is accepted and drawn nowhere.
 */
@Composable
internal fun PageTop(word: String, r: Ramp, value: String? = null, valueColour: Color = r.sub, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(r.ground)) {
        Header(word, r, value, valueColour)
        Box(Modifier.fillMaxSize().padding(top = CONTENT_TOP.px), content = content)
    }
}

// ---- groups: what is left of an earlier design's shelf and pill -------------------------

/**
 * An earlier design's sliding focus shape. Beam has none (a focused row is its words in the hue),
 * so this only keeps the API the lists were written against: it is placed and left, and
 * never drawn.
 */
@Stable
internal class PillState {
    private var owner: Any? = null
    private var rowY = 0f
    private var tall = 0f

    /** Whether a focused row has placed it. */
    var shown by mutableStateOf(false)
        private set

    /** Row [key] is focused, at [y] and [h] tall. */
    fun place(key: Any, y: Float, h: Float) {
        owner = key; rowY = y; tall = h; shown = true
    }

    /** Row [key] has gone. */
    fun leave(key: Any) {
        if (owner == key) { owner = null; shown = false }
    }

    fun top(): Float = rowY
    fun height(): Float = tall
}

/** The pill the rows under it would slide; kept so a list can still provide one ([ShelfList]). */
internal val LocalPill = staticCompositionLocalOf<PillState?> { null }

/** A pill, which in Beam is never drawn. */
@Composable
internal fun rememberPill(): PillState = remember { PillState() }

/** Rows under [content] with [pill] (drawn nowhere). */
@Composable
internal fun ProvidePill(pill: PillState, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPill provides pill, content = content)

/** An earlier design's chosen shape for what is not a row. Beam draws no shape: it only takes its place. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Pill(r: Ramp, modifier: Modifier = Modifier) {
    Box(modifier)
}

/** An earlier design's shelf under a full-width element. Beam draws nothing round a group, so this is the element as it is. */
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.shelfSurface(r: Ramp, roundTop: Boolean = true, roundBottom: Boolean = true): Modifier = this

/** Where a [ShelfRow] in a list falls in its group; kept for the lists' signatures, drawn nowhere. */
internal enum class ShelfEdge(val top: Boolean, val bottom: Boolean) { Only(true, true), First(true, false), Middle(false, false), Last(false, true) }

/** The edge of row [i] of [n] in one group. */
internal fun shelfEdge(i: Int, n: Int): ShelfEdge = when {
    n <= 1 -> ShelfEdge.Only
    i == 0 -> ShelfEdge.First
    i == n - 1 -> ShelfEdge.Last
    else -> ShelfEdge.Middle
}

/** One group's rows, one under the other. Nothing is drawn round them: groups part by space and a [DotLabel]. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Shelf(r: Ramp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) { content() }
}

// A label belongs to the group under it, and the space says so: the first under the header on
// baseline 132, each later one 40 under the group above, its rows 10 under its baseline.

/** A label's baseline under the bottom of whatever is above it (a group, a text block, a bar): 40. */
internal const val LABEL_ABOVE = 40f

/** From a label's baseline to the top of what it labels (its rows, its text): 10. */
internal const val LABEL_GAP = 10f

/** The first label under the header: baseline 32 under the content's top (132). */
internal const val LABEL_FIRST = 32f

/** The first label under an [ItemTitle]: baseline 40 under its bottom, as under a group. */
internal const val LABEL_UNDER_TITLE = 40f

/** A label with both gaps: from the bottom of what is above to the top of its rows, 50. */
internal const val LABEL_STEP = LABEL_ABOVE + LABEL_GAP

/**
 * A group's name: the word in [BeamType.Label] (26) at x 24 in [colour] (sub, or a feature's hue
 * where a group is a feature's, as on Today), the [count] ending at 456 in sub. Its baseline [top]
 * px down ([LABEL_ABOVE] after anything, [LABEL_FIRST] first under the header), and [LABEL_GAP]
 * under it, where its rows start. [dot] is an earlier design's name for the group's colour: when
 * given, it is [colour]. No dot is drawn. [end] is words in the count's place (a provider's models:
 * "free tier").
 */
@Composable
internal fun DotLabel(text: String, r: Ramp, count: Int? = null, top: Float = LABEL_ABOVE, colour: Color = r.sub, dot: Color? = null,
                      end: String? = null) {
    val c = dot ?: colour
    val right = end ?: count?.toString()
    Box(Modifier.fillMaxWidth()) {
        Text(text, style = BeamType.Label, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.px, end = (if (end != null) 240 else 90).px).paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
        if (right != null) Text(right, style = BeamType.Label, color = r.sub, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = top.px, bottom = LABEL_GAP.px))
    }
}

/** The empty ring a to-do is ticked in: r 13, 3 px, in [colour] (sub; the hue when its row is focused). */
@Composable
internal fun Ring(r: Ramp, modifier: Modifier = Modifier, colour: Color = r.sub) {
    Canvas(modifier.size(32.px)) { drawCircle(colour, 13f / 32f * size.width, style = Stroke(3f / 32f * size.width)) }
}

/** The ring picked: a disc r 16 in the hue with the tick 24 in the ground. */
@Composable
internal fun PickedRing(r: Ramp, modifier: Modifier = Modifier) {
    Box(modifier.size(32.px).clip(CircleShape).background(r.hue), contentAlignment = Alignment.Center) {
        Icon(Glyphs.Check, null, Modifier.size(24.px), r.ground)
    }
}

/** A plain row's height: 64. */
internal const val ROW = 64f

/** A row with a glyph: 72 (a ring row is plain, 64). */
internal const val ROW_GLYPH = 72f

/** A wrapped row's line pitch: each line past the first adds this much. */
internal const val ROW_LINE = 46f

/** A row's title baseline from its top: the middle of the row plus 13 (45 plain, 49 with a glyph). */
internal fun rowBase(h: Float): Float = h / 2f + 13f

/** A row's value baseline: the title's (both sit on one). */
internal fun valueBase(h: Float): Float = rowBase(h)

/**
 * A row: 64 tall, 72 with a [glyph], the title in [BeamType.Row] (38) on the middle plus 13 (45; 49
 * with a glyph). No fill and no line. A plain title at x 24; a [glyph] 40 at x 24 in the hue (the
 * danger red for [danger]) or the to-do [ring] centred at x 44 ([PickedRing] when [picked], a row
 * in a selection), the title at 84; a [time] column at 24 in sub, the title at 150 (a wider time, a
 * 12-hour one, pushes it, 14 clear). The [value] (28, sub) ends at 456, at 420 with a [chevron]
 * (x 442); a [trailing] control (a [Toggle]) ends there and the value 14 before it; a [mark] (28)
 * just before the value. The title is cut with an ellipsis to the room the value leaves, keeping at
 * least half the row when it needs it; a value with fewer than three characters of room goes.
 * Everything on the row centres on its first line.
 *
 * Focused, the title, time, ring, mark, value and chevron take the hue and the title goes
 * Medium ([BeamType.RowFocused]); nothing else changes. [dim] (cannot act) and [past] are in
 * sub; [danger] is the red. [wrap] lets the focused row show three lines, 46 a line. [edge]
 * is an earlier design's shelf slice and draws nothing.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun ShelfRow(
    title: String,
    r: Ramp,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
    glyph: ImageVector? = null,
    ring: Boolean = false,
    time: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    mark: ImageVector? = null,
    danger: Boolean = false,
    dim: Boolean = false,
    past: Boolean = false,
    wrap: Boolean = false,
    edge: ShelfEdge? = null,
    onLongClick: (() -> Unit)? = null,
    picked: Boolean = false,
    valueColour: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val quiet = dim || past
    val words = when {
        danger -> r.danger
        focused -> r.hue
        quiet -> r.sub
        else -> r.ink
    }
    val side = if (focused) r.hue else r.sub
    val lead = when {
        danger -> r.danger
        quiet -> r.sub
        else -> r.hue
    }
    val press = when {
        onLongClick != null -> Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onClick ?: {}, onLongClick = { Cues.play(Sound.LongPress); onLongClick() },
        )
        else -> Modifier.tappable(onClick)
    }
    val tall = if (glyph != null) ROW_GLYPH else ROW
    val base = rowBase(tall)
    val open = wrap && focused
    // The width of the value's first three characters: under that, it is not shown.
    val least = if (value != null) {
        val m = rememberTextMeasurer(cacheSize = 4)
        remember(value) { m.measure(value.take(3), BeamType.Value, softWrap = false, maxLines = 1).size.width }
    } else 0
    Layout(
        content = {
            when {
                glyph != null -> Icon(glyph, null, Modifier.layoutId(LEAD).size(40.px), lead)
                ring && picked -> PickedRing(r, Modifier.layoutId(LEAD))
                ring -> Ring(r, Modifier.layoutId(LEAD), colour = if (focused) r.hue else r.sub)
                time != null -> Text(time, style = if (focused) BeamType.RowFocused else BeamType.Row, color = side, maxLines = 1, softWrap = false,
                    modifier = Modifier.layoutId(LEAD).paddingFromBaseline(top = base.px))
            }
            Text(
                title,
                style = if (focused) BeamType.RowFocused else BeamType.Row,
                color = words,
                maxLines = if (open) 3 else 1, softWrap = open, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(TITLE).paddingFromBaseline(top = base.px, bottom = (tall - base).px),
            )
            if (value != null) Text(value, style = BeamType.Value, color = valueColour ?: side, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(VALUE).paddingFromBaseline(top = base.px))
            if (mark != null) Icon(mark, null, Modifier.layoutId(MARK).size(28.px), side)
            if (chevron) Chevron(side, Modifier.layoutId(CHEV))
            if (trailing != null) Row(Modifier.layoutId(TRAIL), verticalAlignment = Alignment.CenterVertically) { trailing() }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = tall.px).then(press),
    ) { ms, c ->
        val p = 1.px.toPx()
        fun at(x: Float) = (x * p).roundToInt()
        val w = c.maxWidth
        val mid = at(base - 13f)
        val leadP = ms.firstOrNull { it.layoutId == LEAD }?.measure(Constraints())
        val xs = when {
            glyph != null || ring -> at(84f)
            time != null -> maxOf(at(150f), at(24f + 14f) + (leadP?.width ?: 0))
            else -> at(24f)
        }
        // From the right: the chevron, then a trailing control, then the value, then a mark.
        var end = w - at(24f)
        val chevP = ms.firstOrNull { it.layoutId == CHEV }?.measure(Constraints())
        if (chevP != null) end = w - at(60f)
        val trailP = ms.firstOrNull { it.layoutId == TRAIL }?.measure(Constraints(maxWidth = maxOf(0, end - xs)))
        val trailX = end - (trailP?.width ?: 0)
        if (trailP != null) end = trailX - at(14f)
        val markP = ms.firstOrNull { it.layoutId == MARK }?.measure(Constraints())
        val zone = maxOf(0, end - xs - if (markP != null) markP.width + at(12f) else 0)
        val titleM = ms.first { it.layoutId == TITLE }
        val need = minOf(titleM.maxIntrinsicWidth(Constraints.Infinity), zone / 2)
        val room = zone - need - at(20f)
        val valueP = if (room >= least) ms.firstOrNull { it.layoutId == VALUE }?.measure(Constraints(maxWidth = room)) else null
        val valueX = end - (valueP?.width ?: 0)
        val markX = (if (valueP != null) valueX - at(12f) else end) - (markP?.width ?: 0)
        val titleRight = when {
            markP != null -> markX - at(20f)
            valueP != null -> valueX - at(20f)
            else -> end
        }
        val titleP = titleM.measure(Constraints(maxWidth = maxOf(0, titleRight - xs)))
        val h = maxOf(c.minHeight, titleP.height)
        layout(w, h) {
            if (leadP != null) when {
                glyph != null -> leadP.place(at(24f), mid - leadP.height / 2)
                ring -> leadP.place(at(44f) - leadP.width / 2, mid - leadP.height / 2)
                else -> leadP.place(at(24f), 0)
            }
            titleP.place(xs, 0)
            valueP?.place(valueX, 0)
            markP?.place(markX, mid - markP.height / 2)
            chevP?.place(at(440f), mid - chevP.height / 2)
            trailP?.place(trailX, mid - trailP.height / 2)
        }
    }
}

private const val LEAD = "lead"
private const val TITLE = "title"
private const val VALUE = "value"
private const val MARK = "mark"
private const val CHEV = "chevron"
private const val TRAIL = "trailing"

/**
 * A right label that is not the row's [value] (a [ShelfRow]'s `trailing`: a failed join, a
 * transcribed tape's length): 28 in [colour], its baseline where a plain row's value's falls.
 */
@Composable
internal fun RowLabel(text: String, colour: Color) {
    Text(text, style = BeamType.Value, color = colour, maxLines = 1, softWrap = false,
        modifier = Modifier.paddingFromBaseline(top = rowBase(ROW).px, bottom = (ROW - rowBase(ROW)).px))
}

/** A row's chevron, 10 x 18, 3 px, round ends, in [colour]. */
@Composable
internal fun Chevron(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.px, 22.px)) {
        val u = size.width / 14f
        val p = Path().apply { moveTo(2f * u, 2f * u); lineTo(12f * u, 11f * u); lineTo(2f * u, 20f * u) }
        drawPath(p, colour, style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---- controls ---------------------------------------------------------------------------

/**
 * The toggle, 68 x 38, radius 19: on, the track in the hue and a white knob (r 14) at the right;
 * off, a faint track and a sub knob at the left. It springs.
 */
@Composable
internal fun Toggle(on: Boolean, r: Ramp, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 700f), label = "toggle")
    // On the white ramp the track is white, so its knob is black.
    val knob = if (r.hue == r.ink) r.ground else r.ink
    Canvas(modifier.size(68.px, 38.px)) {
        val u = size.width / 68f
        drawRoundRect(lerp(r.deep, r.hue, t), cornerRadius = CornerRadius(19f * u))
        drawCircle(lerp(r.sub, knob, t), 14f * u, Offset((19f + 30f * t) * u, 19f * u))
    }
}

/**
 * A vertical level (the control centre's): 8 stacked segments, each 22 / 232 of the height with a
 * pitch of 30 / 232 (56 x 22 and 30 apart at the control centre's 232), radius 9, lit from the
 * bottom in the hue (fill), the rest faint. Any value over nothing lights one.
 */
@Composable
internal fun Level(value: Float, r: Ramp, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val seg = h * 22f / 232f
        val pitch = h * 30f / 232f
        val cr = CornerRadius(minOf(seg * 9f / 22f, w / 2))
        val lit = ceil(value.coerceIn(0f, 1f) * LEVEL_SEGMENTS - 0.001f).toInt()
        for (i in 0 until LEVEL_SEGMENTS) {
            drawRoundRect(if (i < lit) r.fill else r.deep, Offset(0f, h - seg - i * pitch), Size(w, seg), cr)
        }
    }
}

private const val LEVEL_SEGMENTS = 8

/**
 * A meter: rounded segments 14 wide, as many as fit about 24 apart, spread to fill the width
 * exactly so the last one ends on the right edge; lit from the left in [lit] (the hue) by [level],
 * the rest in [track] (faint). 36 tall ([thick]), 12 thin, or [height] px. [level] is read in the
 * draw.
 */
@Composable
internal fun Meter(
    level: () -> Float,
    r: Ramp,
    modifier: Modifier = Modifier,
    thick: Boolean = true,
    track: Color = r.deep,
    lit: Color = r.fill,
    height: Float? = null,
    /** A band of ink passing along the lit segments, 0 to 1 across them (something going over what is there); none when null. */
    glint: (() -> Float)? = null,
) {
    Canvas(modifier.fillMaxWidth().height((height ?: if (thick) 36f else 12f).px)) {
        val u = 1.px.toPx()
        val seg = 14f * u
        val n = maxOf(1, floor((size.width - seg) / (24f * u)).toInt() + 1)
        val pitch = if (n > 1) (size.width - seg) / (n - 1) else 0f
        val on = (level().coerceIn(0f, 1f) * n).roundToInt()
        val cr = CornerRadius(minOf(seg, size.height) / 2)
        // The band two segments either side of its middle, which starts and ends off the meter.
        val band = glint?.let { it() * (n + 4) - 2 }
        for (i in 0 until n) {
            val base = if (i < on) lit else track
            val c = if (band != null && i < on) lerp(base, r.ink, (1f - abs(i - band) / 2f).coerceIn(0f, 1f)) else base
            drawRoundRect(c, Offset(i * pitch, 0f), Size(seg, size.height), cr)
        }
    }
}

/**
 * A text field: the value in [BeamType.FieldText] (36) in ink at x 24, its baseline 44 down, over a
 * 2 px rule 14 under the baseline, 432 wide: in sub, in the hue with a hue caret while the field
 * has the focus. [secret] shows a dot (r 7, 24 apart) for each character, the caret among them;
 * [placeholder] is in sub while it is empty. Typed on Beam's own keyboard (Keyboard.kt); the value
 * stays the caller's, the field's state holds the caret the keys type at. [action], when given, is
 * the action key's word in place of the one its `imeAction` says (Atelier's add page: "find").
 * [refused]: the rule in the danger red, whatever the focus (a key a provider refused).
 */
@Composable
internal fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    r: Ramp,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    action: String? = null,
    refused: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val line = if (refused) r.danger else if (focused) r.hue else r.sub
    val state = rememberValueText(value, onValueChange)
    if (action != null) DisposableEffect(state, action) {
        ACTION_WORDS[state] = action
        onDispose { ACTION_WORDS.remove(state) }
    }
    Column(modifier.fillMaxWidth().padding(start = 24.px, end = 24.px)) {
        HueSelection(r) {
            OwnTextField(
                state,
                // A secret's characters are laid out unseen and drawn as dots; the caret follows the dots.
                textStyle = BeamType.FieldText.copy(color = if (secret) Color.Transparent else r.ink),
                cursorBrush = SolidColor(if (secret) Color.Transparent else r.hue),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (it.isFocused && !focused) Cues.play(Sound.FieldFocus)
                    focused = it.isFocused
                },
                decorator = { inner ->
                    Box(
                        Modifier.fillMaxWidth().height(58.px).drawBehind {
                            if (!secret) return@drawBehind
                            val u = 1.px.toPx()
                            val cy = 33f * u
                            for (i in 0 until state.text.length) drawCircle(r.ink, 7f * u, Offset((10f + 24f * i) * u, cy))
                            if (focused) drawRect(r.hue, Offset((10f + 24f * state.selection.start - 4f) * u, cy - 19f * u), Size(3f * u, 38f * u))
                        },
                    ) {
                        Box(Modifier.paddingFromBaseline(top = 44.px)) {
                            if (state.text.isEmpty() && placeholder != null) Text(placeholder, style = BeamType.FieldText, color = r.sub, maxLines = 1)
                            inner()
                        }
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(2.px).background(line))
    }
}

/**
 * A line of words to choose from: each in [BeamType.Select] (34), 36 apart; the chosen one in the
 * hue with a 4 px underline 12 under its baseline, the rest in sub. 52 tall, the baseline 34 down.
 */
@Composable
internal fun Selector(words: List<String>, selected: Int, r: Ramp, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(36.px)) {
        words.forEachIndexed { i, w ->
            val on = i == selected
            Text(
                w, style = BeamType.Select, color = if (on) r.hue else r.sub, maxLines = 1, softWrap = false,
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

/**
 * The confirmation: the page dimmed to 22% under a black scrim, and the choice as two big words at
 * the bottom left, [word] in its [colour] (the danger red for a delete, else the hue) over `cancel`
 * in ink. A tap on the scrim cancels.
 */
@Composable
internal fun Sheet(r: Ramp, word: String, onCancel: () -> Unit, onAct: () -> Unit, colour: Color? = null) =
    Sheet(r, listOf(SheetBand(word, colour, onAct)), onCancel)

/** One choice of a [Sheet]: its word, its [colour] when it has one (the danger red), and its tap. */
@Immutable
internal class SheetBand(val word: String, val colour: Color? = null, val onClick: () -> Unit)

/**
 * The confirmation with a choice (Power's sleep, restart, power off): the page under a black scrim
 * at 78% (it shows at 22%), and the choices as words in [BeamType.Second] (48) Medium, centred, 66
 * apart, `cancel` in ink last on baseline 590 and the [actions] stacked upward over it in their
 * order, each in its colour or the hue. [question] and [line] under it, when given, say what is
 * asked (Body, ink and sub, centred) 40 over the first word. The wheel moves between the words,
 * starting on `cancel`; the focused one is underlined (2026-09-28: the words bold and centred, so
 * weight no longer marks the focus). A tap on a word does it, on the scrim cancels. [top], when
 * given, is drawn centred over the question (Atelier's install sheet: the app's glyph).
 */
@Composable
internal fun Sheet(
    r: Ramp, actions: List<SheetBand>, onCancel: () -> Unit, question: String? = null, line: String? = null,
    top: (@Composable () -> Unit)? = null,
) {
    var focus by remember { mutableIntStateOf(actions.size) }
    WheelTarget { delta -> moved(focus, delta, actions.size) { focus = it } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(r.ground.copy(alpha = SCRIM)).tappable(onCancel))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 36.px), horizontalAlignment = Alignment.CenterHorizontally) {
            top?.invoke()
            if (question != null) {
                Text(question, style = BeamType.BodyMedium, color = r.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 32.px))
                if (line != null) Text(line, style = BeamType.Body, color = r.sub, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 42.px))
                Box(Modifier.height(24.px))
            }
            actions.forEachIndexed { i, a -> SheetWord(a.word, a.colour ?: r.hue, focus == i, a.onClick) }
            SheetWord("cancel", r.ink, focus == actions.size, onCancel)
        }
    }
}

/** How much of the page a [Sheet]'s scrim hides: it shows at 22%. */
internal const val SCRIM = 0.78f

/**
 * One word of a [Sheet] (and of Atelier's install page), Medium, 66 tall with its baseline 52
 * down, its tap on the word and 24 round it; the focused one underlined 3 px, 6 under its
 * baseline.
 */
@Composable
internal fun SheetWord(word: String, colour: Color, focused: Boolean, onClick: () -> Unit) {
    Box(Modifier.tappable(onClick).padding(start = 24.px, end = 24.px)) {
        Text(word, style = BeamType.Second.copy(fontWeight = FontWeight.Medium),
            color = colour, maxLines = 1, softWrap = false,
            modifier = Modifier.paddingFromBaseline(top = 52.px, bottom = 14.px).drawBehind {
                if (focused) drawRect(colour, Offset(0f, size.height - 8.px.toPx()), Size(size.width, 3.px.toPx()))
            })
    }
}

// ---- text and prints --------------------------------------------------------------------

/**
 * Words that are not a row: no shelf, only the text, from [x] (24) with as much room on the right,
 * its first baseline [top] (32) down, [pitch] (42) a line, [bottom] (10) under the last baseline;
 * in [BeamType.Body] (32), Medium with [medium] or [cream] (the thing in hand), in [colour] (ink;
 * Ask's question is in the hue).
 */
@Composable
internal fun TextShelf(
    text: String,
    r: Ramp,
    modifier: Modifier = Modifier,
    cream: Boolean = false,
    medium: Boolean = false,
    colour: Color = r.ink,
    x: Float = 24f,
    top: Float = 32f,
    bottom: Float = 10f,
    pitch: Float = 42f,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().tappable(onClick)) {
        Text(
            text,
            style = (if (medium || cream) BeamType.BodyMedium else BeamType.Body).copy(lineHeight = pitch.pxSp),
            color = colour, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = x.px, end = x.px).paddingFromBaseline(top = top.px, bottom = bottom.px),
        )
    }
}

/**
 * An item's name with its details under it: the title in [BeamType.Second] (48) in ink at x 22, its
 * first baseline 52 down, 54 a line, wrapping to three; the [details] in [BeamType.Value] (28) in
 * sub at x 24, 44 under the title's last baseline. It ends 20 under its last baseline, so a
 * [DotLabel] with `top = LABEL_UNDER_TITLE` follows as under a group. Put it first in [PageTop]'s
 * content. [onClick] is a tap on the title (a tape's rename).
 */
@Composable
internal fun ItemTitle(title: String, r: Ramp, details: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Layout(
        content = {
            Text(title, style = BeamType.Second, color = r.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tappable(onClick))
            if (details != null) Text(details, style = BeamType.Value, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.fillMaxWidth(),
    ) { ms, c ->
        val p = 1.px.toPx()
        fun at(x: Float) = (x * p).roundToInt()
        val w = c.maxWidth
        val tP = ms[0].measure(Constraints(maxWidth = maxOf(0, w - at(44f))))
        val tFirst = tP[FirstBaseline]
        val tLast = tP[LastBaseline]
        val tY = at(52f) - tFirst
        var last = tY + tLast
        val dP = ms.getOrNull(1)?.measure(Constraints(maxWidth = maxOf(0, w - at(48f))))
        val dY = if (dP != null) last + at(44f) - dP[FirstBaseline] else 0
        if (dP != null) last += at(44f)
        layout(w, last + at(20f)) {
            tP.place(at(22f), tY)
            dP?.place(at(24f), dY)
        }
    }
}

/**
 * A photo print: [content] clipped square to radius 14, nothing round it; when [focused] a 4 px
 * ring in the hue 8 px out, radius 22, drawn outside its bounds. Its width comes from [modifier];
 * it keeps square.
 */
@Composable
internal fun Print(
    r: Ramp,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val press = if (onClick == null && onLongClick == null) Modifier else Modifier.held(onClick ?: {}, onLongClick)
    Box(
        modifier.aspectRatio(1f)
            .drawBehind {
                if (focused) {
                    val u = 1.px.toPx()
                    drawRoundRect(r.hue, Offset(-8f * u, -8f * u), Size(size.width + 16f * u, size.height + 16f * u), CornerRadius(22f * u), style = Stroke(4f * u))
                }
            }
            .then(press),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.px)), content = content)
    }
}

/** A [Print] of [image], cropped to fill the square. */
@Composable
internal fun Print(image: Painter, r: Ramp, modifier: Modifier = Modifier, focused: Boolean = false, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) =
    Print(r, modifier, focused, onClick, onLongClick) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }

/** A [Print] of a decoded [image], cropped to fill the square. */
@Composable
internal fun Print(image: ImageBitmap, r: Ramp, modifier: Modifier = Modifier, focused: Boolean = false, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) =
    Print(remember(image) { BitmapPainter(image) }, r, modifier, focused, onClick, onLongClick)

// ---- keys -------------------------------------------------------------------------------

/**
 * The page's act key: 84 round, centred at (414 - 114 [slot], 574), filled in the hue, its glyph 40
 * in black. When it cannot act, a ring and glyph in dim.
 */
@Composable
internal fun BoxScope.KnobKey(glyph: ImageVector, r: Ramp, onClick: () -> Unit, slot: Int = 0, enabled: Boolean = true) =
    RoundKey(glyph, r.hue, filled = true, r, onClick, slot, enabled)

/** A quiet key: a 3 px ring and the glyph in the hue, in the act key's places. */
@Composable
internal fun BoxScope.QuietKey(glyph: ImageVector, r: Ramp, onClick: () -> Unit, slot: Int = 0, enabled: Boolean = true) =
    RoundKey(glyph, r.hue, filled = false, r, onClick, slot, enabled)

/** The one destructive action: the ring and the glyph in the danger red. */
@Composable
internal fun BoxScope.DangerKey(glyph: ImageVector, r: Ramp, onClick: () -> Unit, slot: Int = 0, enabled: Boolean = true) =
    RoundKey(glyph, r.danger, filled = false, r, onClick, slot, enabled)

@Composable
private fun BoxScope.RoundKey(glyph: ImageVector, colour: Color, filled: Boolean, r: Ramp, onClick: () -> Unit, slot: Int, enabled: Boolean) {
    val c = if (enabled) colour else r.dim
    val fill = filled && enabled
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = (24 + slot * 114).px, bottom = 24.px)
            .size(84.px)
            .drawBehind {
                val u = size.width / 84f
                if (fill) drawCircle(c) else drawCircle(c, 40.5f * u, style = Stroke(3f * u))
            }
            .clip(CircleShape).tappable(if (enabled) onClick else null),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(40.px), if (fill) r.ground else c) }
}

/** A feature's key (an earlier design's Home): a square of [size] px with the feature's glyph in its hue on black; on, the hue with a black glyph. Unused by Beam's Home. */
@Composable
internal fun FeatureKey(f: Feature, size: Float, modifier: Modifier = Modifier, on: Boolean = false, onClick: (() -> Unit)? = null) {
    val q = rampOf(f)
    Box(
        modifier.size(size.px).clip(RoundedCornerShape((size * 0.31f).px)).background(if (on) q.hue else q.ground).tappable(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(f.glyph, null, Modifier.size((size * 0.57f).px), if (on) q.ground else q.hue) }
}

/**
 * A feature's tile (an earlier design's Home), 84 square: the glyph 40 in the feature's hue on black;
 * [badge] is what is live, a [Badge] in the feature's hue with its right edge on the tile's
 * and its top 12 above it. Unused by Beam's Home.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun FeatureTile(f: Feature, r: Ramp, modifier: Modifier = Modifier, badge: String? = null, onClick: (() -> Unit)? = null) {
    val q = rampOf(f)
    Box(modifier.size(84.px)) {
        Box(Modifier.matchParentSize().background(q.ground).tappable(onClick), contentAlignment = Alignment.Center) {
            Icon(f.glyph, null, Modifier.size(40.px), q.hue)
        }
        if (badge != null) Badge(badge, q, Modifier.align(Alignment.TopEnd).offset(y = (-12).px).wrapContentWidth(Alignment.End, unbounded = true))
    }
}

/**
 * A count or a time: black words ([BeamType.Label], Medium) on the hue, as the stock cards are; 40
 * tall, fully round, 12 either side of the words on baseline 29, never narrower than round.
 */
@Composable
internal fun Badge(text: String, r: Ramp, modifier: Modifier = Modifier) {
    Box(modifier.height(40.px).widthIn(min = 40.px).clip(RoundedCornerShape(20.px)).background(r.hue).padding(horizontal = 12.px), contentAlignment = Alignment.TopCenter) {
        Text(text, style = BeamType.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = r.ground, maxLines = 1, softWrap = false,
            modifier = Modifier.paddingFromBaseline(top = 29.px))
    }
}

// ---- lists and scrolling pages -----------------------------------------------------------

/** A list's focus, its scroll, its elastic and its pill, held together: what [rememberRows] gives a page. */
@Stable
internal class Rows(val list: LazyListState, val pull: PullState, val pill: PillState) {
    var focus by mutableIntStateOf(0)
}

/**
 * The wheel on a list of [count] focusable rows: it steps them, keeping row [itemOf] (a list
 * index) in view with [topOf] above it (its group's label), and asking for the row above the
 * first is the step back through the pull, as everywhere (ui/Pull.kt, PullState).
 */
@Composable
internal fun rememberRows(count: Int, onBack: () -> Unit, itemOf: (Int) -> Int = { it }, topOf: (Int) -> Int = itemOf): Rows {
    val list = rememberLazyListState()
    val pull = rememberPull(onBack)
    val pill = rememberPill()
    val rows = remember { Rows(list, pull, pill) }
    LaunchedEffect(count) { rows.focus = rows.focus.coerceIn(0, maxOf(0, count - 1)) }
    val scope = rememberCoroutineScope()
    WheelTarget { delta ->
        if (delta < 0 && rows.focus == 0) { pull.stepBack(); return@WheelTarget false }
        if (!moved(rows.focus, delta, count - 1) { rows.focus = it }) return@WheelTarget false
        scope.launch { list.keepInView(itemOf(rows.focus), top = topOf(rows.focus)) }
        true
    }
    return rows
}

/** A list of groups under a header: the elastic pull at its top, its dots in ink (white) on the black ground. */
@Composable
internal fun ShelfList(rows: Rows, r: Ramp, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    ProvidePill(rows.pill) {
        LazyColumn(state = rows.list, modifier = modifier.fillMaxSize().elasticPull(rows.pull, r.ink, r.ground), content = content)
    }
}

/** A page of words that scroll: the wheel scrolls 96 px a tick and a tick up at the top steps back through the pull. */
@Composable
internal fun rememberWordsScroll(onBack: () -> Unit): Pair<ScrollState, PullState> {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.px.roundToPx() }
    val pull = rememberPull(onBack)
    WheelTarget { delta ->
        if (delta < 0 && scroll.value == 0) pull.stepBack()
        else scope.launch { scroll.glideBy(delta * step) }
        false
    }
    return scroll to pull
}

/**
 * A canvas that fills its place with the elastic pull on it, for a page drawn rather than
 * laid out: the pull hears a finger only through a scroll under it, so this gives it one
 * that never moves. [canvas] is for the canvas's own taps.
 */
@Composable
internal fun PulledCanvas(pull: PullState, r: Ramp, modifier: Modifier = Modifier, canvas: Modifier = Modifier, onDraw: DrawScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight
        Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
            Canvas(canvas.fillMaxWidth().height(h), onDraw = onDraw)
        }
    }
}

// ---- scrolling and the edge ---------------------------------------------------------

/** What scrolls fades into the header: black over the content's first 48, coming in over the first 48 scrolled. */
@Composable
internal fun BoxScope.TopFade(r: Ramp, scroll: ScrollState) = TopFade(r.ground) { scroll.value.toFloat() }

/** The same over a list: [ground] over its first 48, coming in as [scrolled] (device px, read in the layer) passes 48. */
@Composable
internal fun BoxScope.TopFade(ground: Color, scrolled: () -> Float) {
    val reach = with(LocalDensity.current) { 48.px.toPx() }
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth().height(48.px)
            .graphicsLayer { alpha = (scrolled() / reach).coerceIn(0f, 1f) }
            .background(Brush.verticalGradient(0f to ground, 0.08f to ground, 1f to ground.copy(alpha = 0f))),
    )
}

/**
 * While [active], the edge drag leaves what is over the page (a card asking first) rather than
 * the page: a touch from the left edge is taken in the first pass, before the room's own drag
 * (Motion.kt, which gives way to a moved touch already taken), and [onLeave] once it has come
 * 60 dp, as the setup's.
 */
internal fun Modifier.edgeLeave(active: () -> Boolean, onLeave: () -> Unit): Modifier = pointerInput(Unit) {
    val edge = 28.dp.toPx()
    val far = 60.dp.toPx()
    // Under the room's 12 px slop, so it never starts; a tap's jitter stays a tap.
    val slop = 6f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!active() || down.position.x > edge) return@awaitEachGesture
        var dx = 0f
        var taken = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            dx += c.positionChange().x
            if (dx > slop) c.consume()
            if (!taken && dx > far) { taken = true; onLeave() }
        }
    }
}
