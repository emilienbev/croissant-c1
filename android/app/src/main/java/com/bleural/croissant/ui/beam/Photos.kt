package com.bleural.croissant.ui.beam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.Part
import com.bleural.croissant.Phase
import com.bleural.croissant.ToolCall
import com.bleural.croissant.VoiceState
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Exchange
import com.bleural.croissant.data.Generated
import com.bleural.croissant.data.PhotosStore
import com.bleural.croissant.net.GenerationFetcher
import com.bleural.croissant.send.Addresses
import com.bleural.croissant.send.Share
import com.bleural.croissant.send.ShareServer
import com.bleural.croissant.send.encode
import com.bleural.croissant.tailscale.TailscaleRepo
import com.bleural.croissant.tailscale.TailscaleUi
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.KeepExchange
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PullState
import com.bleural.croissant.ui.Photo
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.SheetLine
import com.bleural.croissant.ui.ShelfLine
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.dayLabel
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.generatedDays
import com.bleural.croissant.ui.generatedStatus
import com.bleural.croissant.ui.keepInView
import com.bleural.croissant.ui.moved
import com.bleural.croissant.ui.photoDays
import com.bleural.croissant.ui.rememberGeneratedThumb
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.rememberThumb
import com.bleural.croissant.ui.rowOfShelfTile
import com.bleural.croissant.ui.rowOfTile
import com.bleural.croissant.ui.sheetLines
import com.bleural.croissant.ui.shelfLines
import com.bleural.croissant.ui.showsPhoto
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Photos in Beam: the prints, picking several, one photo and its full view, Generated and one
 * generated picture, Transform, and Send. Black ground, the words lowercase, the photos cyan only
 * where it matters: the glyph, the focused print's ring, a question, a key. Send and Transform are
 * filed under Settings by `Screen.feature`, but are drawn here in the Photos ramp.
 */

/** The Photos ramp, every page of the feature's, Send and Transform included. */
private val PHOTOS: Ramp get() = rampOf(Feature.Photos)

/** A big picture's frame: radius 14, nothing round it. */
internal fun Modifier.shotFrame(): Modifier = clip(RoundedCornerShape(14.px))

/**
 * Every page Photos pushes, by its [Screen]: one photo, Transform, Send, Generated and one
 * generated picture. Each takes from [core] only what it reads.
 */
@Composable
internal fun PhotosPages(screen: Screen, core: Core, nav: NavState, transforming: MutableState<String?>) {
    when (screen) {
        Screen.Generated -> {
            val items by core.generated.items.collectAsStateWithLifecycle()
            GeneratedPage(
                items,
                onOpen = { nav.push(Screen.GeneratedItem(it.id)) },
                onSend = { nav.push(Screen.Send(it)) },
                onDelete = { going -> going.forEach { core.forgetGeneration(it.id); core.generated.delete(it.id) } },
                onBack = nav::pop,
            )
        }
        is Screen.GeneratedItem -> {
            val items by core.generated.items.collectAsStateWithLifecycle()
            val photos by core.photos.items.collectAsStateWithLifecycle()
            val item = items.firstOrNull { it.id == screen.id }
            if (item != null) {
                GeneratedItemPage(
                    item, photos, asking = item.id in core.asking,
                    onFetch = { g -> core.fetchGeneration(g.id) },
                    onAgain = core::generateAgain,
                    onSource = { f -> nav.push(Screen.Photo(f.path)) },
                    onSend = { item.file?.let { f -> nav.push(Screen.Send(listOf(f.path))) } },
                    onDelete = { g -> core.forgetGeneration(g.id); core.generated.delete(g.id); nav.pop(Sound.Destroy) },
                    onBack = nav::pop,
                )
            } else {
                // Its picture went while the page was open: it leaves once, while it is the top.
                Box(Modifier.fillMaxSize().background(PHOTOS.ground))
                LaunchedEffect(screen) { if (nav.current == screen) nav.pop() }
            }
        }
        is Screen.Photo -> {
            val voice by core.voice.collectAsStateWithLifecycle()
            val level = core.level.collectAsStateWithLifecycle()
            PhotoPage(
                File(screen.path), voice, { level.value }, making = transforming.value == screen.path,
                onPhoto = { core.attachPhoto(it?.jpeg, it?.file) },
                onTransform = { nav.push(Screen.Transform(screen.path)) },
                onSend = { nav.push(Screen.Send(listOf(screen.path))) },
                onMade = { transforming.value = null },
                onDelete = { core.photos.delete(it); nav.pop(Sound.Destroy) },
                onBack = nav::pop,
            )
        }
        is Screen.Transform -> {
            val voice by core.voice.collectAsStateWithLifecycle()
            val level = core.level.collectAsStateWithLifecycle()
            TransformPage(
                File(screen.path), voice, { level.value },
                onTransform = core::setTransform,
                onStarted = { transforming.value = screen.path; if (nav.current == screen) nav.pop(sound = Sound.ToolRan) },
                onBack = nav::pop,
            )
        }
        is Screen.Send -> {
            val ts by core.tailscale.state.collectAsStateWithLifecycle()
            val share = remember(screen) { Share(screen.paths.map { File(it) }) }
            SendPage(share, if (share.pieces == 1) "1 photo" else "${share.pieces} photos", PHOTOS, core.tailscale, ts, onBack = nav::pop)
        }
        else -> Unit
    }
}

// ---- the prints -----------------------------------------------------------------------------

/** Between two prints, across and down (three across from x 16, 141.3 each). */
private const val PRINT_GAP = 12f

/** From a day's label baseline to its first prints (222 to 240), clear of the focus ring. */
private const val PRINTS_UNDER_LABEL = 18f

/** From the bottom of what is above to a day's label baseline (172 to 222, 381 to 430). */
private const val PRINTS_LABEL_ABOVE = 50f

/** The first day's label on a page with nothing above it: the kit's first label, baseline 132. */
private const val PRINTS_LABEL_FIRST = 32f

/** Where the list ends while keys sit over it, so its last row scrolls clear of them. */
internal const val UNDER_KEYS = 124f

/**
 * A day's name over its prints: the word lowercase in `Label` sub at x 24, its count ending at 456,
 * the baseline [top] under what is above and the prints [PRINTS_UNDER_LABEL] under it. Drawn here
 * rather than with the kit's label because the prints sit further under it than a row does, clear
 * of the focus ring.
 */
@Composable
private fun DayLabel(text: String, r: Ramp, count: Int, top: Float) {
    Box(Modifier.fillMaxWidth()) {
        Text(text.lowercase(), style = BeamType.Label, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.px, end = 90.px).paddingFromBaseline(top = top.px, bottom = PRINTS_UNDER_LABEL.px))
        Text(count.toString(), style = BeamType.Label, color = r.sub, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = top.px, bottom = PRINTS_UNDER_LABEL.px))
    }
}

/**
 * A day's prints, three across from x 16, [PRINT_GAP] apart and [PRINT_GAP] under a row that
 * is not its day's last; [cell] draws one.
 */
private fun <T> LazyListScope.printRow(items: List<T>, key: String, last: Boolean, cell: @Composable (Int, T, Modifier) -> Unit) {
    item(key = key) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.px, end = 16.px, bottom = if (last) 0.px else PRINT_GAP.px),
            horizontalArrangement = Arrangement.spacedBy(PRINT_GAP.px),
        ) {
            for ((k, t) in items.withIndex()) cell(k, t, Modifier.weight(1f))
            repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A print picked in a selection: a hue disc, 40 across, with the tick 28 in black, 8 in from its top right corner. */
@Composable
private fun BoxScope.Picked(r: Ramp) {
    Box(
        Modifier.align(Alignment.TopEnd).padding(8.px).size(40.px).clip(CircleShape).background(r.hue),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyphs.Check, null, Modifier.size(28.px), r.ground) }
}

/**
 * While prints are picked, the line where Generated was: how many, in `Row` 38 in the hue at
 * x 24, on Generated's baseline (72 tall, 49 down), so nothing under it moves. The header is
 * the word alone.
 */
@Composable
internal fun PickedLine(count: Int, r: Ramp) {
    Box(Modifier.fillMaxWidth().height(72.px)) {
        Text("$count picked", style = BeamType.Row, color = r.hue, maxLines = 1,
            modifier = Modifier.padding(start = 24.px).paddingFromBaseline(top = 49.px))
    }
}

/**
 * A quiet key (the kit's `QuietKey`) where the kit's slots do not reach: 84 round, a 3 px ring and
 * the glyph 40 in the hue (dim when it cannot act), centred at ([cx], [cy]) on the page. Placed
 * from the page's foot, so it holds whatever the content's top.
 */
@Composable
internal fun BoxScope.QuietKeyAt(glyph: ImageVector, r: Ramp, cx: Float, cy: Float, onClick: () -> Unit, enabled: Boolean = true) {
    val c = if (enabled) r.hue else r.dim
    Box(
        Modifier.align(Alignment.BottomStart).padding(start = (cx - 42f).px, bottom = (640f - cy - 42f).px).size(84.px)
            .clip(CircleShape).tappable(if (enabled) onClick else null),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 84f
            drawCircle(c, 40.5f * u, style = Stroke(3f * u))
        }
        Icon(glyph, null, Modifier.size(40.px), c)
    }
}

/**
 * Black rising behind the keys, from nothing at page y 510 (under a picture's details) to the
 * ground at 562, so words and prints scrolling under the keys fade out rather than showing
 * through the rings.
 */
@Composable
internal fun BoxScope.KeyShade(r: Ramp) {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.px)
            .background(Brush.verticalGradient(0f to r.ground.copy(alpha = 0f), 0.4f to r.ground)),
    )
}

/**
 * Photos: Generated first, a glyph row with its count and chevron; then the prints by day, each day
 * a lowercase label with its count, three across, the one in focus ringed in the hue 8 px out. The
 * wheel steps from Generated through the prints; a tap opens one. A press and hold selects:
 * taps then pick, each picked print with a hue tick in its corner, how many are
 * picked where Generated was, and Delete, Send and the way out as keys; a step back empties the
 * selection first.
 */
@Composable
internal fun PhotosPage(files: List<File>, generated: Int, onOpen: (File) -> Unit, onGenerated: () -> Unit, onSend: (List<String>) -> Unit, onDelete: (List<File>) -> Unit, onBack: () -> Unit) {
    val r = PHOTOS
    val days = remember(files) { photoDays(files, LocalDate.now()) }
    val flat = remember(days) { days.flatMap { it.photos } }
    val lines = remember(days) { sheetLines(days) }
    val rowOf = remember(lines) { rowOfTile(lines, flat.size) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    val chosen = remember(flat, picked) { flat.filter { it.path in picked } }
    LaunchedEffect(flat) { picked = picked.intersect(flat.map { it.path }.toSet()) }
    // Lazy item 0 is Generated, or the picked line in its place: the prints' lines follow it.
    val lead = if (selecting) 0 else 1
    var focus by remember { mutableIntStateOf(0) }
    LaunchedEffect(flat.size, lead) { focus = focus.coerceIn(0, maxOf(0, lead + flat.size - 1)) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<List<File>?>(null) }
    fun head(tile: Int): Int = rowOf.getOrElse(tile) { 0 }.let { if (it > 0 && lines[it - 1] is SheetLine.Day) it - 1 else it }
    fun cancel() { picked = emptySet(); Cues.play(Sound.ScreenOut) }
    val pull = rememberPull { if (picked.isNotEmpty()) cancel() else onBack() }
    WheelTarget { delta ->
        if (delta < 0 && focus == 0) { pull.stepBack(); return@WheelTarget false }
        if (!moved(focus, delta, lead + flat.size - 1) { focus = it }) return@WheelTarget false
        if (lead == 1 && focus == 0) { scope.launch { list.keepInView(0, top = 0) }; return@WheelTarget true }
        val tile = focus - lead
        scope.launch { list.keepInView(rowOf[tile] + 1, top = head(tile) + 1) }
        true
    }
    Box(Modifier.fillMaxSize()) {
        PageTop("photos", r) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground)) {
                item(key = "lead") {
                    if (selecting) PickedLine(picked.size, r)
                    else ShelfRow(
                        "generated", r, focused = focus == 0, onClick = { focus = 0; onGenerated() },
                        glyph = Glyphs.Generated, value = generated.takeIf { it > 0 }?.toString(), chevron = true,
                    )
                }
                for (line in lines) when (line) {
                    is SheetLine.Day -> item(key = "d" + line.day.label) { DayLabel(line.day.label, r, line.day.photos.size, PRINTS_LABEL_ABOVE) }
                    is SheetLine.Tiles -> printRow(line.photos, line.photos.first().name, line.last) { k, f, m ->
                        val at = line.first + k
                        val bmp by rememberThumb(f)
                        val image = remember(bmp) { bmp?.asImageBitmap() }
                        Print(
                            r, m, focused = at + lead == focus,
                            onClick = {
                                focus = at + lead
                                if (!selecting) onOpen(f) else {
                                    val on = f.path !in picked
                                    picked = if (on) picked + f.path else picked - f.path
                                    Cues.play(if (on) Sound.SwitchOn else Sound.SwitchOff)
                                }
                            },
                            onLongClick = { focus = at; picked = setOf(f.path); Cues.play(Sound.SwitchOn) },
                        ) {
                            Box(Modifier.fillMaxSize().background(r.deep))
                            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (f.path in picked) Picked(r)
                        }
                    }
                }
                item { Spacer(Modifier.height(if (selecting) UNDER_KEYS.px else 22.px)) }
            }
            if (selecting) {
                KeyShade(r)
                DangerKey(Glyphs.Trash, r, onClick = { asking = chosen; Cues.play(Sound.SheetIn) })
                KnobKey(Glyphs.Send, r, slot = 1, onClick = { onSend(chosen.map { it.path }) })
                QuietKeyAt(Glyphs.Close, r, cx = 82f, cy = 574f, onClick = { cancel() })
            }
        }
        asking?.let { going ->
            Sheet(
                r, "delete ${going.size}", colour = r.danger,
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = null; picked = emptySet(); onDelete(going); Cues.play(Sound.Destroy) },
            )
        }
    }
}

// ---- one photo ---------------------------------------------------------------------------------

/**
 * The tallest a whole picture stands on its page, so its details (46 under it) stay clear of
 * the keys' row (532) on the first screen: 350, from y 104 to 454 at most. A 4:3 shot, 448 x
 * 336, is under it; a tall one stands 350 and narrows.
 */
private const val PICTURE_TALLEST = 350

/** From a picture's foot to its details' baseline. */
private const val PICTURE_DETAILS = 46f

/**
 * A picture whole, at its own shape: 448 across from x 16, or [tallest] px tall and narrower if it
 * is taller than that, centred (2026-09-28); radius 14.
 */
@Composable
private fun WholePicture(bitmap: Bitmap, tallest: Int, onClick: (() -> Unit)? = null) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(Modifier.fillMaxWidth().padding(start = 16.px, end = 16.px, top = 4.px).heightIn(max = tallest.px), contentAlignment = Alignment.TopCenter) {
        Image(
            image, null, contentScale = ContentScale.Fit,
            modifier = Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height).shotFrame().tappable(onClick),
        )
    }
}

/** A picture as a strip, x 16 to 464 from y 104, [height] px, centre-cropped, radius 14. */
@Composable
internal fun ShotStrip(bitmap: Bitmap, height: Int) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(image, null, contentScale = ContentScale.Crop,
        modifier = Modifier.padding(start = 16.px, end = 16.px, top = 4.px).fillMaxWidth().height(height.px).shotFrame())
}

/**
 * A line of details under a picture ("yesterday · 18:02"): `Value` 28 in sub, or [colour], at x 24,
 * the baseline [PICTURE_DETAILS] under the picture, ending on it.
 */
@Composable
private fun Details(text: String, colour: Color) {
    Text(text, style = BeamType.Value, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 24.px, end = 24.px).wordsAt(PICTURE_DETAILS))
}

/** When something was taken or made, as a photo's details read: "yesterday · 18:02", "wed 23 sept · 6:02 pm". */
private fun whenTaken(at: LocalDateTime, twentyFour: Boolean): String {
    val today = LocalDate.now()
    val day = at.toLocalDate()
    val word = if (day == today.minusDays(1)) "yesterday" else dayLabel(day, today)
    val time = at.format(DateTimeFormatter.ofPattern(if (twentyFour) "H:mm" else "h:mm a", Locale.UK))
    return "$word · $time".lowercase()
}

/**
 * A picture on the whole canvas: black round it, fading in and out over 120 ms.
 * A tap, a drag any way or a tick of the wheel puts it away; it eats them all, so nothing
 * under it moves. [bitmap] may come late (an agent's are fetched, Files.kt); [over] is laid over it and takes
 * its own taps (a send).
 */
@Composable
internal fun FullPicture(bitmap: Bitmap?, shown: Boolean, r: Ramp, onDismiss: () -> Unit, over: @Composable BoxScope.() -> Unit = {}) {
    AnimatedVisibility(shown, enter = fadeIn(tween(120, easing = LinearEasing)), exit = fadeOut(tween(120, easing = LinearEasing))) {
        WheelTarget { onDismiss(); false }
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        val dismiss by rememberUpdatedState(onDismiss)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(r.ground).pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        // Up or moved, either way it goes: a drag is dismissed as it starts.
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: break
                            change.consume()
                            if (!change.pressed || (change.position - change.previousPosition).getDistance() > 0f) { dismiss(); break }
                        }
                    }
                },
            ) { if (image != null) Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            // A sibling, not a child: the picture's Initial pass would take its taps first.
            over()
        }
    }
}

/**
 * A picture's page, a photo's or a generated one's, so both behave alike: under the header
 * [word], the [picture] whole (when there is one) and a tap on it shows it on the whole canvas;
 * [words] under it in one scroll with the picture, clear of the keys; the shade and [keys]
 * over the foot; [over] (the sheet) over the page.
 */
@Composable
private fun PicturePage(
    word: String,
    picture: Bitmap?,
    pull: PullState,
    scroll: ScrollState,
    words: @Composable ColumnScope.() -> Unit,
    keys: @Composable BoxScope.() -> Unit,
    over: @Composable BoxScope.() -> Unit,
) {
    val r = PHOTOS
    var full by remember(picture) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PageTop(word, r) {
            // The whole picture, never cropped, and what is said about it under it: one scroll.
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll).padding(bottom = UNDER_KEYS.px)) {
                if (picture != null) WholePicture(picture, PICTURE_TALLEST) { full = true }
                words()
            }
            KeyShade(r)
            keys()
        }
        over()
        if (picture != null) FullPicture(picture, full, r, onDismiss = { full = false })
    }
}

// ---- the exchange about a picture ---------------------------------------------------------------

/** The question's last baseline to the answer's first (188 to 262), as on Ask. */
private const val AFTER_QUESTION = 74f

/** One part of the answer's last baseline to the next one's first, as on Ask. */
private const val AFTER_PART = 54f

/** The dots' top above where the next baseline would be, so their middle is 60 under the question's last, as on Ask. */
private const val DOT_ABOVE = 22f

/** From the words' last baseline to the top of the bars while it listens. */
private const val BARS_UNDER = 24f

/**
 * The mic while it listens (Ask's, a size down to sit under a picture): 18 rounded bars 14 wide, 24
 * apart from x 24, in the hue, each a past sample of the level, the newest on the right, 14 (a dot)
 * to 110 tall, centred in a block 120 tall. Sampled every 70 ms while it shows, and read in the
 * draw.
 */
@Composable
private fun LevelBars(level: () -> Float, r: Ramp, modifier: Modifier = Modifier) {
    val bars = remember { FloatArray(18) }
    var tick by remember { mutableIntStateOf(0) }
    val read by rememberUpdatedState(level)
    LaunchedEffect(Unit) {
        while (true) {
            delay(70L)
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = read().coerceIn(0f, 1f)
            tick++
        }
    }
    Canvas(modifier.fillMaxWidth().height(120.px)) {
        if (tick < 0) return@Canvas // read in the draw, so each sample redraws and recomposes nothing
        pageScale {
            for ((i, v) in bars.withIndex()) {
                val h = 14f + 96f * v
                rect(24f + i * 24f, 60f - h / 2f, 14f, h, r.hue, 7f)
            }
        }
    }
}

/**
 * A tool call where it happened, as Ask sets it: what it did in sub (lowercase), the one value
 * that matters in white, at a value's 28, one line; a call that failed at 45%.
 */
@Composable
private fun ToolLine(call: ToolCall, r: Ramp, modifier: Modifier) {
    Text(
        buildAnnotatedString {
            append(call.label.lowercase())
            call.value?.let { v -> append("  "); withStyle(SpanStyle(color = r.ink)) { append(v) } }
        },
        style = BeamType.Value, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer { alpha = if (call.failed) 0.45f else 1f },
    )
}

/**
 * What was asked about a picture, in Ask's look, from x 24: while it listens, the words in white
 * with the hue caret over the level as bars; then the question in the hue, the answer in white
 * [AFTER_QUESTION] under it and its tool lines where they happened; three dots while the server
 * works and nothing has come; an error in danger with "hold to ask again". [phase] is null for an
 * exchange kept with the photo. The first baseline is [top] under where this starts, and it ends on
 * its last baseline ([wordsAt]).
 */
@Composable
internal fun ShotWords(transcript: String, parts: List<Part>, error: String?, phase: Phase?, level: () -> Float, r: Ramp, top: Float) {
    val words = Modifier.padding(horizontal = 24.px)
    if (phase == Phase.Listening) {
        CaretWords(transcript, r.ink, r.hue, words.wordsAt(top))
        LevelBars(level, r, Modifier.padding(top = BARS_UNDER.px))
        return
    }
    val answered = parts.any { it is Part.Text && it.text.isNotBlank() }
    val working = (phase == Phase.Transcribing || phase == Phase.Answering) && !answered
    var next = top
    if (transcript.isNotEmpty()) {
        // Still settling it is still yours, in white; once it is the question, the hue.
        Text(transcript, style = BeamType.Body, color = if (phase == Phase.Transcribing) r.ink else r.hue, modifier = words.wordsAt(top))
        next = AFTER_QUESTION
    }
    for (part in parts) {
        when (part) {
            is Part.Text -> {
                val text = part.text.trim()
                if (text.isEmpty()) continue
                Text(text, style = BeamType.Body, color = r.ink, modifier = words.wordsAt(next))
            }
            is Part.Tool -> ToolLine(part.call, r, words.wordsAt(next))
        }
        next = AFTER_PART
    }
    if (working) ThinkingDots(r, words.padding(top = (next - DOT_ABOVE).coerceAtLeast(0f).px))
    if (error != null) {
        Text(error, style = BeamType.Body, color = r.danger, modifier = words.wordsAt(next))
        Text("hold to ask again", style = BeamType.Value, color = r.sub, modifier = words.wordsAt(42f))
    }
}

/**
 * The exchange under a strip: live ([voice]) or [kept] as it was, scrolling in the room the
 * strip leaves and clear of the keys at its end, its first baseline 50 under the strip.
 */
@Composable
internal fun ColumnScope.ShotExchange(voice: VoiceState?, kept: Exchange?, level: () -> Float, r: Ramp, scroll: ScrollState) {
    if (voice != null) LaunchedEffect(voice.transcript, voice.parts.size, voice.answer) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(bottom = UNDER_KEYS.px)) {
        if (voice != null) ShotWords(voice.transcript, voice.parts, voice.error, voice.phase, level, r, top = 50f)
        else if (kept != null) ShotWords(kept.transcript, listOf(Part.Text(kept.answer)), null, null, level, r, top = 50f)
    }
}

/**
 * One photo: the whole picture under the header, radius 14; its details in sub under it ("yesterday
 * · 18:02"), or "making a picture" in the hue for a while after a transform; and what was asked
 * about it in Ask's look, live under a hold or as it was kept. Delete through the sheet, Transform
 * and Send as quiet keys. The wheel scrolls; a tick up at the top goes back. A tap on the picture
 * shows it on the whole canvas.
 */
@Composable
internal fun PhotoPage(file: File, voice: VoiceState, level: () -> Float, making: Boolean, onPhoto: (Photo?) -> Unit, onTransform: () -> Unit, onSend: () -> Unit, onMade: () -> Unit, onDelete: (File) -> Unit, onBack: () -> Unit) {
    val r = PHOTOS
    val ctx = LocalContext.current
    var photo by remember { mutableStateOf<Photo?>(null) }
    var kept by remember { mutableStateOf<Exchange?>(null) }
    LaunchedEffect(file.path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                Photo(bytes, BitmapFactory.decodeByteArray(bytes, 0, bytes.size), file) to PhotosStore.exchange(file)
            }.getOrNull()
        }
        if (loaded == null) onBack() else { photo = loaded.first; kept = loaded.second; onPhoto(loaded.first) }
    }
    DisposableEffect(Unit) { onDispose { onPhoto(null) } }
    val details = remember(file) { PhotosStore.takenAt(file)?.let { whenTaken(it, DateFormat.is24HourFormat(ctx)) } }
    val p = photo
    val live = p != null && voice.showsPhoto(p) && !voice.transform
    val scrolls = p != null
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.px.roundToPx() }
    val pull = rememberPull(onBack)
    WheelTarget { delta ->
        if (delta < 0 && (!scrolls || scroll.value == 0)) pull.stepBack()
        else if (scrolls) scope.launch { scroll.glideBy(delta * step) }
        false
    }
    if (p != null) KeepExchange(p, voice)
    var asking by remember { mutableStateOf(false) }
    if (making) LaunchedEffect(Unit) { delay(MAKING_MS); onMade() }
    if (live) LaunchedEffect(voice.transcript, voice.parts.size, voice.answer) { scroll.animateScrollTo(scroll.maxValue) }
    PicturePage(
        "photos", p?.bitmap, pull, scroll,
        words = {
            if (p != null) {
                when {
                    making -> Details("making a picture", r.hue)
                    details != null -> Details(details, r.sub)
                    else -> Spacer(Modifier.height(PICTURE_DETAILS.px))
                }
                // The question 60 under the details, as Ask's is under its header.
                if (live) ShotWords(voice.transcript, voice.parts, voice.error, voice.phase, level, r, top = 60f)
                else kept?.let { ShotWords(it.transcript, listOf(Part.Text(it.answer)), null, null, level, r, top = 60f) }
            }
        },
        keys = {
            DangerKey(Glyphs.Trash, r, onClick = { asking = true; Cues.play(Sound.SheetIn) })
            QuietKey(Glyphs.Generated, r, onClick = onTransform, slot = 1)
            QuietKey(Glyphs.Send, r, onClick = onSend, slot = 2)
        },
        over = {
            if (asking) Sheet(
                r, "delete", colour = r.danger,
                onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = false; onDelete(file) },
            )
        },
    )
}

private const val MAKING_MS = 4000L

/**
 * Transform a photo: the header "transform", the photo as a strip, and under it
 * what is said, in Ask's look: "hold to transform this photo" in sub until the hold, the words in white
 * over the level while it listens, then in the hue with the three dots while the server takes
 * it, an error in danger. It hands back once the picture is being made.
 */
@Composable
internal fun TransformPage(file: File, voice: VoiceState, level: () -> Float, onTransform: (Boolean) -> Unit, onStarted: () -> Unit, onBack: () -> Unit) {
    val r = PHOTOS
    DisposableEffect(Unit) { onTransform(true); onDispose { onTransform(false) } }
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
    }
    val started by rememberUpdatedState(onStarted)
    LaunchedEffect(voice.generation) { if (voice.generation != null) started() }
    val said = voice.transcript.isNotEmpty() || voice.phase != Phase.Idle || voice.error != null
    val scroll = rememberScrollState()
    LaunchedEffect(voice.transcript) { scroll.animateScrollTo(scroll.maxValue) }
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.px.roundToPx() }
    val pull = rememberPull(onBack)
    WheelTarget { delta ->
        if (delta < 0 && (!said || scroll.value == 0)) pull.stepBack()
        else if (said) scope.launch { scroll.glideBy(delta * step) }
        false
    }
    PageTop("transform", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground)) {
            bitmap?.let { ShotStrip(it, 210) }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(bottom = 22.px)) {
                if (!said) Text("hold to transform this photo", style = BeamType.Value, color = r.sub,
                    modifier = Modifier.padding(start = 24.px).wordsAt(PICTURE_DETAILS))
                else ShotWords(voice.transcript, emptyList(), voice.error, voice.phase, level, r, top = 50f)
            }
        }
    }
}

// ---- send --------------------------------------------------------------------------------------

/**
 * Send: the header word alone, and under it one centred
 * group: [what] (the tape's name or the photo count) as an item's title (48, stepping down to
 * two lines at 36 so a long name fits whole), the code on white (a camera reads it, not a
 * person), and the two addresses as the kit's selector, the same gap between each and the
 * group centred in the page. [r] is the feature it was sent from. The device serves the
 * photos, or a tape's transcript, itself while the page is open.
 */
@Composable
internal fun SendPage(share: Share, what: String, r: Ramp, tailscale: TailscaleRepo, tsUi: TailscaleUi, onBack: () -> Unit) {
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tailscale.refresh() } }
    val lan = remember { Addresses.lan() }
    val hosts = remember(lan, tsUi.ip) { buildList { tsUi.ip?.let { add("tailnet" to it) }; lan?.let { add("wi-fi" to it) } } }
    var which by remember { mutableIntStateOf(0) }
    LaunchedEffect(hosts.size) { which = which.coerceAtMost((hosts.size - 1).coerceAtLeast(0)) }
    var deaf by remember(share) { mutableStateOf(false) }
    DisposableEffect(share) {
        val server = ShareServer(share) {}
        if (!server.start()) deaf = true
        onDispose { server.stop() }
    }
    val pull = rememberPull(onBack)
    WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
    PageTop("send", r) {
        // Always a scroll, so a finger has something to pull; one that never moves, the page's height.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tall = maxHeight
            Column(
                Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())
                    .height(tall).padding(horizontal = 20.px),
                verticalArrangement = Arrangement.spacedBy(SEND_GAP.px, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    what, style = BeamType.Second.copy(color = r.ink, lineHeight = 1.05.em, textAlign = TextAlign.Center),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 36f.pxSp, maxFontSize = 48f.pxSp, stepSize = 4f.pxSp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 76.px),
                )
                val words = when {
                    deaf -> "port ${Share.PORT} is busy"
                    hosts.isEmpty() -> "no wi-fi and no tailnet"
                    else -> null
                }
                if (words != null) Box(Modifier.size(340.px), contentAlignment = Alignment.Center) {
                    Text(words, style = BeamType.Body, color = r.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.px))
                } else Code(share.url(hosts[which].second))
                if (hosts.size > 1) Selector(hosts.map { it.first }, which, r) { i ->
                    if (i != which) { which = i; Cues.play(Sound.Commit) }
                }
            }
        }
    }
}

/** The gap between the title, the code and the selector. */
private const val SEND_GAP = 36

/**
 * The code on white: [side] px square (340 by default), radius 14 as a print, the modules in
 * 111111 by whole pixels inside a [quiet] px quiet zone (30 by default).
 */
@Composable
internal fun Code(text: String, modifier: Modifier = Modifier, side: Float = 340f, quiet: Float = 30f) {
    val code = remember(text) { encode(text) }
    val image = remember(code) {
        code?.let { c ->
            val px = IntArray(c.side * c.side) { if (c[it % c.side, it / c.side]) CODE_INK else CODE_WHITE }
            Bitmap.createBitmap(px, c.side, c.side, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Box(modifier.size(side.px).clip(RoundedCornerShape(14.px)).background(Color.White)) {
        if (image != null) Canvas(Modifier.fillMaxSize()) {
            val room = size.minDimension - 2 * quiet.px.toPx()
            val cell = (room / image.width).toInt().coerceAtLeast(1)
            val side = cell * image.width
            drawImage(image, dstOffset = IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt()), dstSize = IntSize(side, side), filterQuality = FilterQuality.None)
        }
    }
}

private const val CODE_INK = 0xFF111111.toInt()
private const val CODE_WHITE = 0xFFFFFFFF.toInt()

// ---- generated ---------------------------------------------------------------------------------

/**
 * Generated (laid out as the prints): the pictures the model was asked to make, by day,
 * three across, the first day's label where the kit's first label is (baseline 132). One not
 * here yet is its prompt in sub on a faint print; one that never came says why in danger.
 * A tap opens its page; press and hold selects, as on Photos.
 */
@Composable
internal fun GeneratedPage(items: List<Generated>, onOpen: (Generated) -> Unit, onSend: (List<String>) -> Unit, onDelete: (List<Generated>) -> Unit, onBack: () -> Unit) {
    val r = PHOTOS
    val days = remember(items) { generatedDays(items, LocalDate.now()) }
    val flat = remember(days) { days.flatMap { it.items } }
    val lines = remember(days) { shelfLines(days) }
    val rowOf = remember(lines) { rowOfShelfTile(lines, flat.size) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    val chosen = remember(flat, picked) { flat.filter { it.id in picked } }
    LaunchedEffect(flat) { picked = picked.intersect(flat.map { it.id }.toSet()) }
    var focus by remember { mutableIntStateOf(0) }
    LaunchedEffect(flat.size) { focus = focus.coerceIn(0, maxOf(0, flat.lastIndex)) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<List<Generated>?>(null) }
    fun cancel() { picked = emptySet(); Cues.play(Sound.ScreenOut) }
    val pull = rememberPull { if (picked.isNotEmpty()) cancel() else onBack() }
    // Lazy item 0 is the picked line while selecting, nothing otherwise: the lines follow it.
    WheelTarget { delta ->
        if (delta < 0 && focus == 0) { pull.stepBack(); return@WheelTarget false }
        if (flat.isEmpty()) return@WheelTarget false
        if (!moved(focus, delta, flat.lastIndex) { focus = it }) return@WheelTarget false
        val i = rowOf[focus]
        scope.launch { list.keepInView(i + 1, top = (if (i > 0 && lines[i - 1] is ShelfLine.Day) i - 1 else i) + 1) }
        true
    }
    Box(Modifier.fillMaxSize()) {
        PageTop("generated", r) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground)) {
                item(key = "lead") { if (selecting) PickedLine(picked.size, r) }
                for ((li, line) in lines.withIndex()) when (line) {
                    is ShelfLine.Day -> item(key = "d" + line.day.label) {
                        DayLabel(line.day.label, r, line.day.items.size, if (li == 0 && !selecting) PRINTS_LABEL_FIRST else PRINTS_LABEL_ABOVE)
                    }
                    is ShelfLine.Tiles -> printRow(line.items, line.items.first().id, line.last) { k, g, m ->
                        val at = line.first + k
                        val bmp = g.file?.let { rememberGeneratedThumb(it).value }
                        val image = remember(bmp) { bmp?.asImageBitmap() }
                        val lost = g.lost
                        Print(
                            r, m, focused = at == focus,
                            onClick = {
                                focus = at
                                if (!selecting) onOpen(g) else {
                                    val on = g.id !in picked
                                    picked = if (on) picked + g.id else picked - g.id
                                    Cues.play(if (on) Sound.SwitchOn else Sound.SwitchOff)
                                }
                            },
                            onLongClick = { focus = at; picked = setOf(g.id); Cues.play(Sound.SwitchOn) },
                        ) {
                            Box(Modifier.fillMaxSize().background(r.deep))
                            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Text(
                                lost ?: g.prompt, style = BeamType.Label, color = if (lost != null) r.danger else r.sub,
                                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.px),
                            )
                            if (g.id in picked) Picked(r)
                        }
                    }
                }
                item { Spacer(Modifier.height(if (selecting) UNDER_KEYS.px else 22.px)) }
            }
            if (selecting) {
                val sendable = chosen.mapNotNull { it.file?.path }
                KeyShade(r)
                DangerKey(Glyphs.Trash, r, onClick = { asking = chosen; Cues.play(Sound.SheetIn) })
                KnobKey(Glyphs.Send, r, slot = 1, enabled = sendable.isNotEmpty(), onClick = { onSend(sendable) })
                QuietKeyAt(Glyphs.Close, r, cx = 82f, cy = 574f, onClick = { cancel() })
            }
        }
        asking?.let { going ->
            Sheet(
                r, "delete ${going.size}", colour = r.danger,
                onCancel = { asking = null; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = null; picked = emptySet(); onDelete(going); Cues.play(Sound.Destroy) },
            )
        }
    }
}

/**
 * One generated picture (laid out as a photo): the whole picture, when it was asked for in sub
 * under it, and its prompt in the hue, as a question is; or, while it is not here, what is going on
 * (sub, danger when it failed) where the picture would be, and the prompt. Fetched every few
 * seconds while the page is open and it is still coming. Delete (or Stop while it is being made),
 * Send when there is a picture, Try again when it failed; the photo it came from as a row.
 */
@Composable
internal fun GeneratedItemPage(item: Generated, photos: List<File>, asking: Boolean, onFetch: suspend (Generated) -> Unit, onAgain: (Generated) -> Unit, onSource: (File) -> Unit, onSend: () -> Unit, onDelete: (Generated) -> Unit, onBack: () -> Unit) {
    val r = PHOTOS
    val ctx = LocalContext.current
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var unreadable by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.file?.path) {
        val f = item.file ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
        unreadable = bitmap == null
    }
    val latest by rememberUpdatedState(item)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(item.id) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (latest.pending) { onFetch(latest); delay(GenerationFetcher.POLL_S * 1000) }
        }
    }
    val details = remember(item.at) {
        whenTaken(LocalDateTime.ofInstant(Instant.ofEpochMilli(item.at), ZoneId.systemDefault()), DateFormat.is24HourFormat(ctx))
    }
    val here = item.file != null && !unreadable
    val status = remember(item, asking, unreadable) { generatedStatus(item, asking, unreadable) }
    val (scroll, pull) = rememberWordsScroll(onBack)
    var sure by remember { mutableStateOf(false) }
    val stopping = item.pending
    val source = remember(item.source, photos) { item.source?.let { name -> photos.firstOrNull { it.name == name } } }
    PicturePage(
        "generated", bitmap.takeIf { here }, pull, scroll,
        words = {
            if (here) Details(details, r.sub)
            else Text(
                status.word.replaceFirstChar { it.lowercase() }, style = BeamType.Value, color = if (status.failed) r.danger else r.sub,
                modifier = Modifier.padding(start = 24.px, end = 24.px).wordsAt(PRINTS_LABEL_FIRST),
            )
            Text(item.prompt, style = BeamType.Body, color = r.hue,
                modifier = Modifier.padding(start = 24.px, end = 24.px).wordsAt(60f))
            if (source != null) {
                // Its title 74 under the prompt's last baseline (the row puts it 49 down).
                Spacer(Modifier.height(25.px))
                ShelfRow("from a photo", r, glyph = Glyphs.Photos, chevron = true, onClick = { onSource(source) })
            }
        },
        keys = {
            val glyph = if (stopping) Glyphs.Stop else Glyphs.Trash
            val trash = { sure = true; Cues.play(Sound.SheetIn) }
            when {
                here -> {
                    DangerKey(glyph, r, onClick = trash)
                    QuietKey(Glyphs.Send, r, onClick = onSend, slot = 1)
                }
                item.pending -> DangerKey(glyph, r, onClick = trash)
                else -> {
                    DangerKey(glyph, r, slot = 1, onClick = trash)
                    KnobKey(Glyphs.Restart, r, enabled = !asking, onClick = { onAgain(item) })
                }
            }
        },
        over = {
            if (sure) Sheet(
                r, if (stopping) "stop" else "delete", colour = r.danger,
                onCancel = { sure = false; Cues.play(Sound.SheetDismiss) },
                onAct = { sure = false; onDelete(item) },
            )
        },
    )
}
