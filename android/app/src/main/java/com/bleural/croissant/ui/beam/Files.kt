package com.bleural.croissant.ui.beam

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bleural.croissant.net.DocBlock
import com.bleural.croissant.net.DocSpan
import com.bleural.croissant.net.DocTable
import com.bleural.croissant.net.FileKind
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.PullState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberPull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * A file viewed: a picture on the whole canvas, and a file's page by its kind: markdown rendered on
 * black, a table (a CSV, or one in markdown) that slides sideways under its held first column, text
 * as lines on a faint card, anything else a card with its name and size. An agent's session's files
 * open here (AgentFiles.kt). Page pixels; the content from y 66.
 */

/** The side a print's picture is asked at (a 4:3 one still covers the 142 square), and a whole one's (the canvas's long side). */
internal const val PRINT_SIDE = 284
private const val FULL_SIDE = 640

/** A file's size as a value: "0.4 kB", "38 kB", "1.2 MB". */
internal fun fileSize(bytes: Long): String = when {
    bytes < 10_240 -> String.format(Locale.UK, "%.1f kB", bytes / 1024.0)
    bytes < 1_048_576 -> "${bytes / 1024} kB"
    else -> String.format(Locale.UK, "%.1f MB", bytes / 1_048_576.0)
}

/**
 * A picture on the whole canvas, as Photos' ([FullPicture]), read by [image] at a side: its print's
 * at once, the whole one once it comes; any tap, drag or wheel tick puts it away, but send (right
 * edge 456, top 570), [onSend], when it has one, which takes its own tap.
 */
@Composable
internal fun WholePicture(r: Ramp, file: Any, shown: Boolean, image: suspend (Int) -> Bitmap?, onDismiss: () -> Unit, onSend: (() -> Unit)?) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = image(PRINT_SIDE)
        image(FULL_SIDE)?.let { value = it }
    }
    FullPicture(bitmap, shown, r, onDismiss) {
        if (onSend != null) Pill("send", r.hue, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 570.px, end = 24.px), onSend)
    }
}

// ---- a file's page --------------------------------------------------------------------------

/** The hue a file's markdown marks its bullets and quotes in: its page's ([FilePage] sets it). */
internal val LocalFileHue = staticCompositionLocalOf { INK }

/** What a file's page shows: opening, its source failing, its card, or the file read as its kind. */
private sealed interface Shown {
    data object Opening : Shown
    data object Failed : Shown
    data object Card : Shown
    class Lines(val lines: List<String>) : Shown
    class Markdown(val blocks: List<MdBlock>) : Shown
    class Table(val table: DocTable, val cols: TableCols) : Shown
}

/** Where a file's page reads its file: its [text] (null when it is not text), and its [size] for the card. */
internal class FileSource(val text: suspend () -> String?, val size: suspend () -> Long?)

/**
 * A file's page, in [r] under the header [word], read from [source] (remembered by the caller: a
 * new one reads the file again): its name in Select at x 24 on baseline 112, one line in 300, send
 * on its line (top 79) when it has one ([onSend]), and the file under it, clipped from y 140, by
 * its kind ([FileKind]): markdown ([MarkdownView]), a table ([CsvView]), lines ([LinesView]);
 * anything else, or what the source says is not text, its card alone ([FileCard]). The dots while
 * it opens; the source failing, why in danger ([away]). The wheel scrolls; up at the top is the
 * pull back.
 */
@Composable
internal fun FilePage(word: String, r: Ramp, path: String, source: FileSource, away: String, onSend: (() -> Unit)?, onBack: () -> Unit) {
    val work = rememberWorkMeasurer()
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    val shown by produceState<Shown>(Shown.Opening, source, path) { value = open(source, path, work, unit, r.hue) }
    val pull = rememberPull(onBack)
    val name = path.substringAfterLast('/')
    if (shown == Shown.Card) return FileCard(word, r, name, source, pull, onSend)
    PageTop(word, r) {
        Text(name, style = BeamType.Select, color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.px).widthIn(max = if (onSend != null) 300.px else 432.px).paddingFromBaseline(top = 46.px))
        if (onSend != null) Pill("send", r.hue, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 13.px, end = 24.px), onSend)
        Box(Modifier.fillMaxSize().padding(top = 74.px).clipToBounds()) {
            when (val s = shown) {
                Shown.Opening -> Still(pull) { ThinkingDots(r, Modifier.padding(start = 26.px, top = 34.px)) }
                Shown.Failed -> Still(pull) {
                    Text(away, style = BeamType.Value, color = DANGER, modifier = Modifier.padding(start = 24.px).wordsAt(50f))
                }
                is Shown.Lines -> LinesView(s.lines, pull)
                is Shown.Markdown -> CompositionLocalProvider(LocalFileHue provides r.hue) { MarkdownView(s.blocks, pull) }
                is Shown.Table -> CsvView(s.table, s.cols, pull)
                Shown.Card -> Unit
            }
        }
    }
}

/** The file from its source, read as its kind off the main thread; its card without asking for one that is not text by its name. */
private suspend fun open(source: FileSource, path: String, work: TextMeasurer, unit: Float, hue: Color): Shown {
    val kind = FileKind.of(path)
    if (kind == FileKind.Image || kind == FileKind.Other) return Shown.Card
    val text = try {
        source.text()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return Shown.Failed
    }
    if (text == null) return Shown.Card
    return withContext(Dispatchers.Default) {
        when (kind) {
            FileKind.Markdown -> Shown.Markdown(markdown(text, work, unit, hue))
            FileKind.Table -> DocTable.csv(text, if (path.endsWith(".tsv", ignoreCase = true)) '\t' else ',')
                .let { Shown.Table(it, TableCols.of(it, work, unit)) }
            else -> Shown.Lines(text.trimEnd().lines().flatMap { if (it.length > LINE_MAX) it.chunked(LINE_MAX) else listOf(it) })
        }
    }
}

/** A text measurer of its own for work off the main thread, never shared with a draw. */
@Composable
internal fun rememberWorkMeasurer(): TextMeasurer {
    val fonts = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(fonts, density, direction) { TextMeasurer(fonts, density, direction, cacheSize = 0) }
}

/** What does not scroll: the wheel up and a finger's pull still step back, as everywhere. */
@Composable
internal fun Still(pull: PullState, content: @Composable ColumnScope.() -> Unit) {
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(rememberScrollState()), content = content)
}

/** The wheel on a list (or a grid): [step] page px a tick; a tick up at its top steps back through the pull. */
@Composable
internal fun LazyWheel(list: ScrollableState, pull: PullState, step: Float) {
    val scope = rememberCoroutineScope()
    val px = with(LocalDensity.current) { step.px.toPx() }
    WheelTarget { delta ->
        if (delta < 0 && !list.canScrollBackward) pull.stepBack()
        else scope.launch { list.animateScrollBy(delta * px) }
        false
    }
}

/** How far a list has scrolled, as far as a fade needs to know: device px, far once past its first item. */
internal fun LazyListState.scrolled(): Float = if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()

/** The same for a grid: far once its first row is past. */
internal fun LazyGridState.scrolled(): Float = if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()

/**
 * Any other file: no name line, one faint card from y 74, 448 x 300, radius 16: the file glyph 56
 * in the hue at (36, 98), its name in Second white on 230, its size in sub on 276 (asked of its
 * source), send on the card (right edge 444, top 308).
 */
@Composable
private fun FileCard(word: String, r: Ramp, name: String, source: FileSource, pull: PullState, onSend: (() -> Unit)?) {
    val size by produceState<Long?>(null, source) {
        value = try { source.size() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    PageTop(word, r) {
        Still(pull) {
            Box(Modifier.padding(start = 16.px, end = 16.px, top = 8.px).fillMaxWidth().height(300.px).clip(RoundedCornerShape(16.px)).background(FAINT)) {
                Icon(Glyphs.File, null, Modifier.padding(start = 20.px, top = 24.px).size(56.px), r.hue)
                Text(name, style = BeamType.Second, color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 18.px, end = 20.px).paddingFromBaseline(top = 156.px))
                size?.let { Text(fileSize(it), style = BeamType.Value, color = SUB, modifier = Modifier.padding(start = 20.px).paddingFromBaseline(top = 202.px)) }
                if (onSend != null) Pill("send", r.hue, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 234.px, end = 20.px), onSend)
            }
        }
    }
}

// ---- lines ----------------------------------------------------------------------------------

/** A file's lines: a value's 28, 40 a line. */
private val FILE_LINE = BeamType.Value.copy(lineHeight = 40f.pxSp)

/** The longest line one Text lays out: a minified file's single line is cut into lines this long. */
private const val LINE_MAX = 2000

/**
 * Text and code as lines: a faint card from y 140 to the foot, 448 from x 16, its top corners round
 * (16); the lines in [FILE_LINE] white from x 36, 408 across, the first baseline 46 down, a long
 * one carried on under itself (past [LINE_MAX] characters, cut into several). The wheel scrolls 96
 * a tick; scrolled, the card's top fades.
 */
@Composable
private fun LinesView(lines: List<String>, pull: PullState) {
    val list = rememberLazyListState()
    LazyWheel(list, pull, 96f)
    Box(Modifier.padding(start = 16.px, end = 16.px).fillMaxSize().clip(RoundedCornerShape(topStart = 16.px, topEnd = 16.px)).background(FAINT)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().elasticPull(pull, INK, FAINT), contentPadding = PaddingValues(top = 10.px, bottom = 40.px)) {
            items(lines) { l ->
                Text(l.ifEmpty { " " }, style = FILE_LINE, color = INK,
                    modifier = Modifier.padding(start = 20.px, end = 20.px).paddingFromBaseline(top = 36.px, bottom = 4.px))
            }
        }
        TopFade(FAINT) { list.scrolled() }
    }
}

// ---- markdown -------------------------------------------------------------------------------

/** A markdown block as it is set: its words with their spans styled, its gap from the block above ([gap]), a table's columns. */
internal class MdBlock(val block: DocBlock, val text: AnnotatedString, val gap: Float, val cols: TableCols? = null)

/** The markdown's blocks, set off the main thread. */
private fun markdown(text: String, work: TextMeasurer, unit: Float, hue: Color): List<MdBlock> = mdBlocks(DocBlock.markdown(text), work, unit, hue)

/**
 * [blocks] as they are set, each its words styled and its gap from the block above: [after], the
 * one before them in the same text, or none, and then the first's gap is [lead] less (an agent's
 * words under the item above them). A rule is only the space between blocks.
 */
internal fun mdBlocks(blocks: List<DocBlock>, work: TextMeasurer, unit: Float, hue: Color, after: DocBlock? = null, lead: Float = 0f): List<MdBlock> {
    var last: DocBlock? = after
    return blocks.filterNot { it is DocBlock.Rule }.map { b ->
        val words = when (b) {
            is DocBlock.Heading -> styled(b.spans, hue)
            is DocBlock.Paragraph -> styled(b.spans, hue)
            is DocBlock.Item -> styled(b.spans, hue)
            is DocBlock.Quote -> styled(b.spans, hue)
            is DocBlock.Code -> AnnotatedString(b.text)
            is DocBlock.Table, DocBlock.Rule -> AnnotatedString("")
        }
        MdBlock(b, words, gap(b, last) - if (last == null) lead else 0f, (b as? DocBlock.Table)?.let { TableCols.of(it.table, work, unit) }).also { last = b }
    }
}

/** Inline markdown as it is drawn: bold a Medium span, code in the hue; italic and links plain. */
private fun styled(spans: List<DocSpan>, hue: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        if (!s.bold && !s.code) append(s.text)
        else withStyle(SpanStyle(color = if (s.code) hue else Color.Unspecified, fontWeight = if (s.bold) FontWeight.Medium else null)) { append(s.text) }
    }
}

/**
 * From the end of the block above (its last baseline; a card's foot less 4) to [b]'s first
 * baseline, or a card's top: before `#` 56, before `##` 80 (46 first), before a code
 * block or a table 30; between list items 50; after `#` 64, after `##` 54; else 58.
 */
private fun gap(b: DocBlock, last: DocBlock?): Float {
    val g = when {
        b is DocBlock.Heading && b.level == 1 -> 56f
        b is DocBlock.Heading && b.level == 2 -> if (last == null) 46f else 80f
        b is DocBlock.Code || b is DocBlock.Table -> 30f
        b is DocBlock.Item && last is DocBlock.Item -> 50f
        last is DocBlock.Heading && last.level == 1 -> 64f
        last is DocBlock.Heading && last.level == 2 -> 54f
        else -> 58f
    }
    return if (last is DocBlock.Code || last is DocBlock.Table) g - 4f else g
}

/** Where a markdown's first block is measured from, under the clip at 140: a `#` lands on 200. */
private const val MD_TOP = 4f

/** How much further in a nested list item sits, a level: its parent's words' start. */
private const val NEST = 28f

/** A code block's lines: a value's 28, 38 a line. */
private val CODE_LINE = BeamType.Value.copy(lineHeight = 38f.pxSp)

/**
 * Markdown rendered on black: a block a list item, so a long file costs only what shows. The first
 * baseline on 200; the wheel scrolls 96 a tick; scrolled, the top fades under the name line.
 */
@Composable
private fun BoxScope.MarkdownView(blocks: List<MdBlock>, pull: PullState) {
    val list = rememberLazyListState()
    LazyWheel(list, pull, 96f)
    val draw = rememberTextMeasurer(cacheSize = TABLE_CACHE)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().elasticPull(pull, INK, GROUND), contentPadding = PaddingValues(top = MD_TOP.px, bottom = 40.px)) {
        items(blocks, contentType = { it.block::class }) { b -> MdBlockView(b, draw) }
    }
    TopFade(GROUND) { list.scrolled() }
}

/**
 * One block: `#` in Second (48, 54 a line) from x 22, `##` in RowFocused (38), deeper in
 * BodyMedium (32); a paragraph in Body white from x 24, 432 across; a list item, a quote, a
 * code block or a table as below. Each starts [MdBlock.gap] over its first baseline and ends
 * on its last, a card on its foot.
 */
@Composable
internal fun MdBlockView(b: MdBlock, draw: TextMeasurer) {
    val words = Modifier.padding(start = 24.px, end = 24.px).wordsAt(b.gap)
    when (val block = b.block) {
        is DocBlock.Heading -> when (block.level) {
            1 -> Text(b.text, style = BeamType.Second, color = INK, modifier = Modifier.padding(start = 22.px, end = 24.px).wordsAt(b.gap))
            2 -> Text(b.text, style = BeamType.RowFocused, color = INK, modifier = words)
            else -> Text(b.text, style = BeamType.BodyMedium, color = INK, modifier = words)
        }
        is DocBlock.Paragraph -> Text(b.text, style = BeamType.Body, color = INK, modifier = words)
        is DocBlock.Item -> MdItem(block, b.text, b.gap)
        is DocBlock.Quote -> MdQuote(b.text, b.gap)
        is DocBlock.Code -> Box(Modifier.padding(start = 16.px, end = 16.px, top = b.gap.px).fillMaxWidth().clip(RoundedCornerShape(16.px)).background(FAINT)) {
            Text(b.text, style = CODE_LINE, color = INK, modifier = Modifier.padding(start = 20.px, end = 20.px).paddingFromBaseline(top = 46.px, bottom = 24.px))
        }
        is DocBlock.Table -> b.cols?.let { Box(Modifier.padding(top = b.gap.px)) { MdTable(block.table, it, draw) } }
        DocBlock.Rule -> Unit
    }
}

/**
 * A list item: a hue dot r 5 centred at x 32 on its first baseline less 11, or its number and "."
 * in sub Body ending at 46 (from about x 24; a "10." reaches out left of it, so the words keep
 * their line); its words in Body white from x 52, 396 across, 42 a line. Each level of nesting
 * [NEST] further in.
 */
@Composable
private fun MdItem(item: DocBlock.Item, text: AnnotatedString, gap: Float) {
    val hue = LocalFileHue.current
    Row(Modifier.fillMaxWidth().padding(start = (24f + item.depth * NEST).px, end = 24.px)) {
        if (item.number == null) Canvas(Modifier.width(28.px).height(gap.px)) {
            val u = 1.px.toPx()
            drawCircle(hue, 5f * u, Offset(8f * u, (gap - 11f) * u))
        } else Text("${item.number}.", style = BeamType.Body, color = SUB, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(end = 6.px).width(22.px).wrapContentWidth(Alignment.End, unbounded = true).wordsAt(gap))
        Text(text, style = BeamType.Body, color = INK, modifier = Modifier.weight(1f).wordsAt(gap))
    }
}

/** A quote: a 4 px hue bar (radius 2) at x 24 from its first baseline less 32 to 12 under its last; the words in sub Body from x 46, 408 across. */
@Composable
private fun MdQuote(text: AnnotatedString, gap: Float) {
    val hue = LocalFileHue.current
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val u = 1.px.toPx()
            val top = (gap - 32f) * u
            drawRoundRect(hue, Offset(24f * u, top), Size(4f * u, size.height + 12f * u - top), CornerRadius(2f * u))
        },
    ) { Text(text, style = BeamType.Body, color = SUB, modifier = Modifier.padding(start = 46.px, end = 26.px).wordsAt(gap)) }
}

// ---- tables ---------------------------------------------------------------------------------

/** A table's row and its words' baseline in it. */
private const val TABLE_ROW = 52f
private const val TABLE_BASE = 35f

/** The widest a column gets; a longer cell is cut. */
private const val TABLE_COL_MAX = 320f

/** A table's cells laid out at once: a screen of rows, five across, and some. */
private const val TABLE_CACHE = 96

/**
 * A table's columns, measured once, off the main thread: each as wide as its widest cell (of its
 * longest by length, and its name) plus its padding, 24 left on the first and 12 on every other
 * side, at most [TABLE_COL_MAX]; a column of numbers is set to its right. Page px.
 */
@Immutable
internal class TableCols private constructor(val widths: FloatArray, val numeric: BooleanArray) {
    val lefts = FloatArray(widths.size).also { l -> var x = 0f; for (i in widths.indices) { l[i] = x; x += widths[i] } }
    val total = widths.sum()
    /** The held column's width. */
    val first = widths.firstOrNull() ?: 0f

    companion object {
        /** How many of a column's longest cells are measured for its widest. */
        private const val LONGEST = 12

        /** [t]'s columns, measured with [m] (device px, [unit] a page px). */
        fun of(t: DocTable, m: TextMeasurer, unit: Float): TableCols {
            val n = t.header.size
            val numeric = BooleanArray(n) { i -> t.rows.any { it[i].isNotBlank() } && t.rows.all { it[i].isBlank() || it[i].trim().replace(",", "").toDoubleOrNull() != null } }
            val widths = FloatArray(n) { i ->
                val longest = t.rows.map { it[i] }.sortedByDescending { it.length }.take(LONGEST) + t.header[i]
                val widest = longest.maxOf { m.measure(it, BeamType.Value, softWrap = false, maxLines = 1).size.width } / unit
                minOf(widest + if (i == 0) 36f else 24f, TABLE_COL_MAX)
            }
            return TableCols(widths, numeric)
        }
    }
}

/** A table's sideways slide, device px from 0 to [max] (the columns past the canvas): by a finger only ([slid]). */
@Stable
private class Slide(val max: Float) {
    var dx by mutableFloatStateOf(0f)
        private set
    val scroll = ScrollableState { d -> val was = dx; dx = (was - d).coerceIn(0f, max); was - dx }
}

@Composable
private fun rememberSlide(cols: TableCols): Slide {
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    return remember(cols, unit) { Slide(((cols.total - 480f) * unit).coerceAtLeast(0f)) }
}

/**
 * A finger slides [slide], flinging on, but not from the left 28 dp: that is the step back's
 * (ui/Slides.kt, Slides), and a table edge to edge would take it first.
 */
@Composable
private fun Modifier.slid(slide: Slide): Modifier {
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    return pointerInput(slide, fling) {
        val edge = 28.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x <= edge) return@awaitEachGesture
            scope.launch { slide.scroll.stopScroll() }
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); slide.scroll.dispatchRawDelta(over) }
                ?: return@awaitEachGesture
            horizontalDrag(drag.id) { c -> tracker.addPointerInputChange(c); slide.scroll.dispatchRawDelta(c.positionChange().x); c.consume() }
            val v = tracker.calculateVelocity().x
            scope.launch { slide.scroll.scroll { with(fling) { performFling(v) } } }
        }
    }
}

/**
 * A CSV or a TSV: the table from y 140 to the foot, its first row the header band, held at the top,
 * the rest under it in a lazy list; a finger slides it sideways under the held first column. The
 * wheel moves two rows (104) a tick.
 */
@Composable
private fun CsvView(table: DocTable, cols: TableCols, pull: PullState) {
    val list = rememberLazyListState()
    LazyWheel(list, pull, 2 * TABLE_ROW)
    val slide = rememberSlide(cols)
    val draw = rememberTextMeasurer(cacheSize = TABLE_CACHE)
    Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).slid(slide)) {
        TableRow(table.header, cols, header = true, { slide.dx }, draw)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.px)) {
                items(table.rows.size, contentType = { 0 }) { i -> TableRow(table.rows[i], cols, header = false, { slide.dx }, draw) }
            }
            TableShades(cols, { slide.dx }, slide.max, Modifier.fillMaxSize())
        }
    }
}

/** A table in markdown: edge to edge, the header band then every row, slid sideways on its own; the band goes up with the page. */
@Composable
private fun MdTable(table: DocTable, cols: TableCols, draw: TextMeasurer) {
    val slide = rememberSlide(cols)
    Column(Modifier.fillMaxWidth().slid(slide)) {
        TableRow(table.header, cols, header = true, { slide.dx }, draw)
        Box {
            Column { for (row in table.rows) TableRow(row, cols, header = false, { slide.dx }, draw) }
            TableShades(cols, { slide.dx }, slide.max, Modifier.matchParentSize())
        }
    }
}

/**
 * One row of a table, [TABLE_ROW] tall: the header's on a faint band edge to edge, its words in
 * sub; a row's on black, in white; no lines. The first cell is held; the others are one layer slid
 * by [dx] (device px, read in that layer only) and clipped at the held column's edge, so a slide
 * redraws nothing; the row is a layer too, so a scroll re-records none of it.
 */
@Composable
private fun TableRow(cells: List<String>, cols: TableCols, header: Boolean, dx: () -> Float, draw: TextMeasurer) {
    val colour = if (header) SUB else INK
    Box(
        Modifier.fillMaxWidth().height(TABLE_ROW.px).graphicsLayer()
            .drawWithContent {
                if (header) drawRect(FAINT)
                clipRect(left = cols.first * 1.px.toPx()) { this@drawWithContent.drawContent() }
                cells.firstOrNull()?.let { cell(draw, cols, 0, it, colour) }
            },
    ) {
        Spacer(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(cols.total.px).fillMaxHeight()
                .graphicsLayer { translationX = -dx() }
                .drawBehind { for (i in 1 until cells.size) cell(draw, cols, i, cells[i], colour) },
        )
    }
}

/** One cell's words in [colour], Value 28 on the row's baseline: from its column's left (24 in on the first, 12 on the rest), or a number ending 12 in from its right; cut to the room. */
private fun DrawScope.cell(m: TextMeasurer, cols: TableCols, i: Int, s: String, colour: Color) {
    if (s.isEmpty()) return
    val u = 1.px.toPx()
    val pad = if (i == 0) 24f else 12f
    val room = ((cols.widths[i] - pad - 12f) * u).toInt().coerceAtLeast(0)
    val laid = m.measure(s, BeamType.Value, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = room))
    val x = if (cols.numeric[i]) (cols.lefts[i] + cols.widths[i] - 12f) * u - laid.size.width else (cols.lefts[i] + pad) * u
    drawText(laid, colour, Offset(x, TABLE_BASE * u - laid.firstBaseline))
}

/**
 * Over a table's rows: once slid, a 24 px shadow (black 70% to nothing) at the held column's edge;
 * while more columns lie to the right, a 48 px fade to black over x 432 to 480. Each comes in over
 * its width of slide, as a layer's alpha read from [dx].
 */
@Composable
private fun TableShades(cols: TableCols, dx: () -> Float, max: Float, modifier: Modifier) {
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    Box(modifier) {
        Box(
            Modifier.padding(start = cols.first.px).width(24.px).fillMaxHeight()
                .graphicsLayer { alpha = (dx() / (24f * unit)).coerceIn(0f, 1f) }
                .background(Brush.horizontalGradient(listOf(GROUND.copy(alpha = 0.7f), GROUND.copy(alpha = 0f)))),
        )
        Box(
            Modifier.align(Alignment.TopEnd).width(48.px).fillMaxHeight()
                .graphicsLayer { alpha = ((max - dx()) / (48f * unit)).coerceIn(0f, 1f) }
                .background(Brush.horizontalGradient(listOf(GROUND.copy(alpha = 0f), GROUND))),
        )
    }
}
