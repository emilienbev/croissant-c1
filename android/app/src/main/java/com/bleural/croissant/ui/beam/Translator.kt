package com.bleural.croissant.ui.beam

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.Translation
import com.bleural.croissant.offline.LocalState
import com.bleural.croissant.offline.OfflineVoice
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.languagesFor
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.tappable
import com.bleural.croissant.ui.translationDays
import com.bleural.croissant.ui.translatorShown
import com.bleural.croissant.ui.withSide
import java.time.OffsetDateTime
import kotlin.math.cos
import kotlin.math.sin

/*
 * The Translator in Beam: the pair as words on one line, the target in the hue; what was said in
 * white under its language's name in sub, the translation in the hue under its name in the hue.
 * Read-out, a speaker key, is not built, so the key is local mode's R1, quiet while off, and
 * "saved" sits bottom left. Languages, Saved, the local mode ask, loading and ready are built from
 * the kit in the same language.
 */

/**
 * The Translator: "english to french" at a row's 38 on baseline 150, the target in the hue; a tap
 * on a language opens that side's list and a tap on "to" turns the pair round ("to" is dim when
 * that cannot be done). Under it the from language's name in sub over what was said in white, and
 * the to language's name in the hue over what came back in the hue. A hold puts the words said in
 * white as they arrive with the hue caret after them, the mic's level as a segment meter under
 * them, and two faint bars where the translation will be; while it is being translated three dots
 * stand there instead. Only a translation made this way round is shown, so the names always label
 * their words. The wheel scrolls a long one; up at the top, or a finger pulled down there, is the
 * elastic pull back.
 *
 * Local mode: the R1 key bottom right, a quiet key (ring and glyph in the hue) while off,
 * filled when ready, which is how the page says it is local. Off, a
 * tap asks first on a sheet (online is the better translator); on, it switches off at once.
 * While it loads the page gives way to the loader.
 */
@Composable
internal fun TranslatorPage(
    voice: VoiceState,
    level: () -> Float,
    pair: LanguagePair,
    kept: List<Translation>,
    onPick: (to: Boolean) -> Unit,
    onSwap: () -> Unit,
    onSaved: () -> Unit,
    local: LocalState,
    hears: (Language) -> Boolean,
    onLocal: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val r = rampOf(Feature.Translator)
    val localOn = local is LocalState.Loading || local == LocalState.Ready
    val canSwap = !localOn || hears(pair.to)
    val shown = translatorShown(voice, kept.filter { it.pair == pair })
    val phase = if (voice.translate != null) voice.phase else Phase.Idle
    val listening = phase == Phase.Listening
    val working = phase == Phase.Transcribing || (phase == Phase.Answering && voice.answer.isEmpty())
    var asking by remember { mutableStateOf(false) }
    val (scroll, pull) = rememberWordsScroll(onBack)
    // A new hold starts at the top, where its words arrive.
    LaunchedEffect(listening) { if (listening) scroll.scrollTo(0) }
    val said = shown?.said.orEmpty()
    val heard = shown?.heard.orEmpty()
    val error = if (phase == Phase.Error) voice.error else null

    Box(Modifier.fillMaxSize()) {
        PageTop("translator", r) {
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll).padding(bottom = 132.px)) {
                PairLine(
                    pair.from.word, "to", pair.to.word,
                    firstColour = r.ink, jointColour = if (canSwap) r.sub else r.dim, secondColour = r.hue,
                    onFirst = { onPick(false) }, onJoint = if (canSwap) onSwap else null, onSecond = { onPick(true) },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.px)) {
                    Exchange(pair, said, heard, listening, working, error, nothing = shown == null, level, r)
                }
            }
            SavedWord(r, onSaved)
            // The R1: quiet while off, filled when ready. Off, a tap asks first; on, it switches off at once.
            if (local == LocalState.Off) QuietKey(Glyphs.Device, r, onClick = { asking = true; Cues.play(Sound.SheetIn) })
            else if (local == LocalState.Ready) KnobKey(Glyphs.Device, r, onClick = { onLocal(false) })
            LocalLoader(local, r, onClose = { onLocal(false) })
        }
        // The kit's sheet with the question over its words (the question is the app's, so lowercase).
        if (asking) Sheet(
            r, listOf(SheetBand("switch to local") { asking = false; onLocal(true) }),
            onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
            question = "translate on the device?",
            line = "online is better. switch only if the network is unstable.",
        )
    }
}

/**
 * Under the pair, each block measured from the last baseline above it ([wordsAt]): the from name at
 * page 222 in sub, what was said 42 under it in white (with the caret while it is being said), then
 * the segment meter (24 tall) 36 under the words while you hold, the to name in the hue (64 under
 * the meter, 60 under the words) and the translation 42 under it in the hue, or the two faint bars,
 * or the dots. An error is in danger 74 under the words, "hold to try again" in sub under it.
 * Nothing yet: "hold to talk" in sub where the from name would be. A name only stands over words:
 * with nothing said there is no from name.
 */
@Composable
private fun Exchange(
    pair: LanguagePair, said: String, heard: String, listening: Boolean, working: Boolean, error: String?,
    nothing: Boolean, level: () -> Float, r: Ramp,
) {
    if (nothing && !listening) {
        Text("hold to talk", style = BeamType.Body, color = r.sub, modifier = Modifier.wordsAt(UNDER_PAIR))
        return
    }
    var next = UNDER_PAIR
    if (listening || said.isNotEmpty()) {
        Text(pair.from.word.lowercase(), style = BeamType.Label, color = r.sub, maxLines = 1, modifier = Modifier.wordsAt(UNDER_PAIR))
        if (listening) CaretWords(said, r.ink, r.hue, Modifier.wordsAt(UNDER_NAME))
        else Text(said, style = BeamType.Body, color = r.ink, modifier = Modifier.wordsAt(UNDER_NAME))
        next = NAME_AFTER_WORDS
    }
    if (listening) {
        // The level read in the draw, so a sample recomposes nothing.
        Meter(level, r, Modifier.padding(top = METER_ABOVE.px), height = 24f)
        next = NAME_AFTER_METER
    }
    if (heard.isNotEmpty() || listening || working) {
        Text(pair.to.word.lowercase(), style = BeamType.Label, color = r.hue, maxLines = 1, modifier = Modifier.wordsAt(next))
        when {
            heard.isNotEmpty() -> Text(heard, style = BeamType.Body, color = r.hue, modifier = Modifier.wordsAt(UNDER_NAME))
            listening -> Pending(r)
            else -> ThinkingDots(r, Modifier.padding(top = DOTS_UNDER_NAME.px))
        }
        next = NAME_AFTER_WORDS
    }
    if (error != null) {
        Text(error, style = BeamType.Body, color = r.danger, modifier = Modifier.wordsAt(if (next == UNDER_PAIR) UNDER_PAIR else ERROR_AFTER))
        Text("hold to try again", style = BeamType.Value, color = r.sub, modifier = Modifier.wordsAt(HINT))
    }
}

/**
 * Words on one line, 78 tall with the baseline 50 down (page 150 under the header): [first],
 * [joint] and [second] at a row's 38, 14 apart from x 24, each its own tap. [underline] puts
 * the selector's mark (4 px in the hue, 12 under the baseline) under the first (0) or the
 * second (2). A language too long for its half is cut with an ellipsis.
 */
@Composable
private fun PairLine(
    first: String, joint: String, second: String,
    firstColour: Color, jointColour: Color, secondColour: Color,
    underline: Int = -1, mark: Color = Color.Unspecified,
    onFirst: (() -> Unit)? = null, onJoint: (() -> Unit)? = null, onSecond: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(PAIR.px).padding(horizontal = 24.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
        PairWord(first.lowercase(), firstColour, if (underline == 0) mark else null, Modifier.weight(1f, fill = false), onFirst)
        PairWord(joint, jointColour, null, Modifier, onJoint)
        PairWord(second.lowercase(), secondColour, if (underline == 2) mark else null, Modifier.weight(1f, fill = false), onSecond)
    }
}

@Composable
private fun PairWord(word: String, colour: Color, mark: Color?, modifier: Modifier, onClick: (() -> Unit)?) {
    Box(
        modifier.fillMaxHeight().tappable(onClick).drawBehind {
            if (mark != null) drawRoundRect(mark, Offset(0f, 62.px.toPx()), Size(size.width, 4.px.toPx()), CornerRadius(2.px.toPx()))
        },
    ) {
        Text(word, style = BeamType.Row, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.paddingFromBaseline(top = 50.px))
    }
}

/** Where the translation will be: two faint bars 16 tall, 300 and 200 long, 26 and 58 under the name's baseline. */
@Composable
private fun Pending(r: Ramp) {
    Box(Modifier.padding(top = 26.px).size(300.px, 16.px).clip(RoundedCornerShape(8.px)).background(r.deep))
    Box(Modifier.padding(top = 16.px).size(200.px, 16.px).clip(RoundedCornerShape(8.px)).background(r.deep))
}

/**
 * The way to the saved ones: "saved" in white at x 24 with a chevron in sub 14 after it, on
 * the key's line (page baseline 586), a tap anywhere in the bottom left 200 x 100.
 */
@Composable
private fun BoxScope.SavedWord(r: Ramp, onClick: () -> Unit) {
    Row(Modifier.align(Alignment.BottomStart).size(200.px, 100.px).tappable(onClick).padding(start = 24.px)) {
        Text("saved", style = BeamType.Body, color = r.ink, modifier = Modifier.paddingFromBaseline(top = 46.px))
        Canvas(Modifier.padding(start = 14.px, top = 26.px).size(10.px, 18.px)) {
            val u = size.width / 10f
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(10f * u, 9f * u); lineTo(0f, 18f * u) }, r.sub,
                style = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Local mode loading: black over the page under the header, and the R1 88 in the hue at (240, 300)
 * as laid out under the top bar, inside a ring of 30 rounded segments (14 wide, from r 108 to 136),
 * lit in the hue clockwise from the top toward an optimistic 18 s, the rest faint. Under it the
 * seconds left at 64 Light (the unit in sub), "almost" past them; bottom left "cancel" as a sheet's
 * word, which switches it off. A load that failed leaves the ring unlit, the R1 and the reason in
 * danger, and "close". The wheel is swallowed while it shows; the edge and a hold still work.
 */
@Composable
private fun BoxScope.LocalLoader(local: LocalState, r: Ramp, onClose: () -> Unit) {
    if (local !is LocalState.Loading && local !is LocalState.Failed) return
    val at = (local as? LocalState.Loading)?.at
    // The clock only runs while loading, and only the ring and the seconds read it.
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (at != null) LaunchedEffect(at) { while (true) withFrameMillis { now.longValue = SystemClock.elapsedRealtime() } }
    Box(Modifier.fillMaxSize().background(r.ground).tappable {})
    Canvas(Modifier.fillMaxSize()) {
        pageScale {
            // This box starts under the header, so (240, 300) under the top bar is (240, 200) in it, [LIFT] higher on the page.
            val lit = if (at == null) 0 else {
                val frac = ((now.longValue - at).coerceAtLeast(0L).toFloat() / OfflineVoice.LOAD_TARGET_MS).coerceIn(0f, 1f)
                (frac * SEGMENTS).toInt()
            }
            for (i in 0 until SEGMENTS) {
                val a = Math.toRadians(-90.0 + i * 360.0 / SEGMENTS)
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                drawLine(if (i < lit) r.hue else r.deep, Offset(240f + 115f * c, 200f + 115f * s), Offset(240f + 129f * c, 200f + 129f * s),
                    strokeWidth = 14f, cap = StrokeCap.Round)
            }
        }
    }
    Icon(Glyphs.Device, null, Modifier.offset(x = 196.px, y = 156.px).size(88.px), if (at != null) r.hue else r.danger)
    Column(Modifier.fillMaxWidth().padding(top = 336.px, start = 24.px, end = 24.px), horizontalAlignment = Alignment.CenterHorizontally) {
        if (at != null) {
            val left by remember(at) { derivedStateOf { (OfflineVoice.LOAD_TARGET_MS - (now.longValue - at) + 999) / 1000 } }
            Text(
                buildAnnotatedString {
                    if (left > 0) { append("$left"); withStyle(SpanStyle(color = r.sub)) { append(" s") } } else append("almost")
                },
                style = BeamType.Mid, color = r.ink, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = 72.px),
            )
        } else if (local is LocalState.Failed) {
            Text(local.message, style = BeamType.Body, color = r.danger, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.paddingFromBaseline(top = 60.px))
        }
    }
    // Where a sheet's cancel is: page baseline 590, bottom left.
    Box(Modifier.align(Alignment.BottomStart).size(240.px, 110.px).tappable(onClose).padding(start = 24.px)) {
        Text(if (at != null) "cancel" else "close", style = BeamType.Second, color = r.ink, maxLines = 1,
            modifier = Modifier.paddingFromBaseline(top = 60.px))
    }
    WheelTarget { false }
}

/**
 * The pair's languages: the pair line as on the Translator, the side being picked in the hue
 * with the selector's mark under it, the other in sub (a tap picks that side instead); under
 * it every language but the other side's as the kit's rows, the one in use with a check. A
 * pick goes back. In local mode [hears] says which can be picked to speak in; the others are
 * in sub on the from side, and inert. No label: the marked word says which side it is.
 */
@Composable
internal fun TranslatorLanguagesPage(pair: LanguagePair, startTo: Boolean, onPick: (LanguagePair) -> Unit, onBack: () -> Unit, hears: (Language) -> Boolean = { true }) {
    val r = rampOf(Feature.Translator)
    var to by remember { mutableStateOf(startTo) }
    val list = languagesFor(pair, to)
    val current = if (to) pair.to else pair.from
    val rows = rememberRows(list.size, onBack)
    LaunchedEffect(to) { rows.focus = list.indexOf(current).coerceAtLeast(0) }
    PageTop("translator", r) {
        Column(Modifier.fillMaxSize()) {
            PairLine(
                pair.from.word, "to", pair.to.word,
                firstColour = if (to) r.sub else r.hue, jointColour = r.sub, secondColour = if (to) r.hue else r.sub,
                underline = if (to) 2 else 0, mark = r.hue,
                onFirst = { to = false }, onSecond = { to = true },
            )
            ShelfList(rows, r) {
                items(list.size) { i ->
                    val heard = to || hears(list[i])
                    ShelfRow(
                        list[i].word.lowercase(), r, focused = rows.focus == i,
                        onClick = if (heard) ({ rows.focus = i; onPick(pair.withSide(to, list[i])) }) else null,
                        mark = if (list[i] == current) Glyphs.Check else null,
                        dim = !heard, edge = shelfEdge(i, list.size),
                    )
                }
                item { Spacer(Modifier.height(24.px)) }
            }
        }
    }
}

/**
 * The saved translations: the pair's, both ways round, newest first. At the top the pair as
 * the Translator shows it ("english and french"), then a group per day
 * under its name and count in sub. Each is what was said in sub over what came back in
 * white; the focused one's translation turns the hue, Medium, and both show whole, the
 * others a line each. The wheel moves the focus, a tap puts it there.
 */
@Composable
internal fun TranslatorSavedPage(pair: LanguagePair, kept: List<Translation>, onBack: () -> Unit) {
    val r = rampOf(Feature.Translator)
    val minute by rememberMinuteTick()
    val days = remember(kept, minute) { translationDays(kept, OffsetDateTime.now()) }
    // Where each translation sits in the list (item 0 is the pair), and what stays above it:
    // the pair for the first day, the day's name for the others.
    val (itemAt, labelAt) = remember(days) {
        val items = ArrayList<Int>()
        val labels = ArrayList<Int>()
        var at = 1
        days.forEachIndexed { di, d ->
            val label = if (di == 0) 0 else at
            at++
            repeat(d.items.size) { items += at++; labels += label }
        }
        items to labels
    }
    val rows = rememberRows(kept.size, onBack, itemOf = { itemAt.getOrElse(it) { 0 } }, topOf = { labelAt.getOrElse(it) { 0 } })
    PageTop("saved", r) {
        ShelfList(rows, r) {
            item { PairLine(pair.from.word, "and", pair.to.word, firstColour = r.ink, jointColour = r.sub, secondColour = r.ink) }
            var n = 0
            days.forEachIndexed { di, d ->
                item { DotLabel(d.label.lowercase(), r, count = d.items.size, top = if (di == 0) UNDER_PAIR else LABEL_ABOVE) }
                d.items.forEach { t ->
                    val i = n++
                    item(key = t.id) { SavedRow(t, rows.focus == i, r) { rows.focus = i } }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/**
 * One saved translation: what was said at a value's 28 in sub, its baseline 38 down, and what
 * came back at a row's 38 in white 44 under it, 22 under the last. Focused, what came back
 * turns the hue and Medium, and both show whole (two lines said, three back, 46 a line);
 * otherwise a line each.
 */
@Composable
private fun SavedRow(t: Translation, focused: Boolean, r: Ramp, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tappable(onClick).padding(start = 24.px, end = 24.px, bottom = 22.px)) {
        Text(t.said, style = BeamType.Value.copy(lineHeight = 36f.pxSp), color = r.sub, maxLines = if (focused) 2 else 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.wordsAt(38f))
        Text(t.heard, style = if (focused) BeamType.RowFocused else BeamType.Row, color = if (focused) r.hue else r.ink,
            maxLines = if (focused) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.wordsAt(44f))
    }
}

/** The Translator's pushed pages: its languages and its saved translations. */
@Composable
internal fun TranslatorPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        is Screen.TranslatorLanguages -> {
            val local by core.local.collectAsStateWithLifecycle()
            val localOn = local is LocalState.Loading || local == LocalState.Ready
            TranslatorLanguagesPage(core.translatorPair, screen.to,
                onPick = { core.saveTranslatorPair(it); Cues.play(Sound.Commit); nav.pop() }, onBack = nav::pop,
                hears = { !localOn || core.localHears(it) })
        }
        Screen.TranslatorSaved -> {
            val all by core.translations.items.collectAsStateWithLifecycle()
            val pair = core.translatorPair
            TranslatorSavedPage(pair, remember(all, pair) { core.translations.forPair(pair) }, onBack = nav::pop)
        }
        else -> Unit
    }
}

/** The pair line's height: its baseline 50 down, page 150. */
private const val PAIR = 78f
/** The pair line's end (page 178) to the first baseline under it (page 222). */
private const val UNDER_PAIR = 44f
/** A language's name to the first baseline of its words. */
private const val UNDER_NAME = 42f
/** Words to the next language's name. */
private const val NAME_AFTER_WORDS = 60f
/** Words to the top of the listening meter. */
private const val METER_ABOVE = 36f
/** The meter's foot to the to language's name. */
private const val NAME_AFTER_METER = 64f
/** The to name's baseline to the dots' top: their middle where the translation's first line sits. */
private const val DOTS_UNDER_NAME = 20f
/** Words to an error. */
private const val ERROR_AFTER = 74f
/** An error to its hint. */
private const val HINT = 42f
/** The loading ring's segments. */
private const val SEGMENTS = 30
