package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.bleural.croissant.Part
import com.bleural.croissant.Phase
import com.bleural.croissant.ToolCall
import com.bleural.croissant.VoiceState
import com.bleural.croissant.data.Turn
import com.bleural.croissant.data.TurnState
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.toolGlyph
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.empty
import com.bleural.croissant.setup.Step

/**
 * Ask in Beam (picked 2026-10-06; with the croissant and as a chat, 2026-10-28): the page is the
 * conversation and nothing else, no bar, no labels, the header the word alone. The croissant
 * (AskCroissant.kt) shows when you first hold in a new conversation, big in the middle, your words
 * arriving under it in white from the left with a hue caret after them (Body 32, x 24, first
 * baseline 392). From the let go on it is a chat: each question you asked ranged right in the hue,
 * its lines ending on the gutter (x 456, 400 wide at most), the answer from the left in white 74
 * under it, the tool lines where they happened, each with its feature's glyph, and the next
 * question 86 under the last line. The croissant thinks in place of dots until the answer starts,
 * then moves small into the top right corner and stays there for the conversation, the words rising
 * to a first baseline of 176 under it; a follow-up you are saying comes on the right, in white with
 * the caret on the gutter. A turn whose answer is still coming behind a newer hold has three dots
 * in the hue; an error is in danger with "hold to ask again" in sub under it. Empty, it says "hold
 * to talk" in sub where your words will be, and there is no croissant. A hold stopped at its press
 * says why with what to set under it: no server (the setup), no key for the model's provider
 * ([onKey] its key's page), no model ([onModel], the model page).
 *
 * [turns] are the open conversation's kept turns; [live] is the exchange in flight or just
 * finished when it is this conversation's, drawn in place of its kept copy. History is a quiet
 * key bottom left, "+" (a new conversation) bottom right, the words fading out under them;
 * "+" only while there is something to leave, and neither while you hold. The wheel scrolls it
 * 96 px a tick; up at the top, or a finger pulled down there, is the elastic pull back.
 */
@Composable
internal fun AskPage(
    live: VoiceState, level: () -> Float, conv: String?, turns: List<Turn>,
    onHistory: () -> Unit, onNew: () -> Unit, onBack: () -> Unit,
    onKey: (String) -> Unit, onModel: () -> Unit,
) {
    val r = rampOf(Feature.Ask)
    val kept = remember(turns, live.turn) { turns.filter { it.id != live.turn } }
    val showsLive = !live.empty
    val empty = kept.isEmpty() && !showsLive
    val listening = live.phase == Phase.Listening
    val started = live.parts.isNotEmpty() || live.error != null
    val spot = when {
        empty || live.refused -> AskSpot.None
        kept.isEmpty() && showsLive && !started -> AskSpot.Middle
        else -> AskSpot.Corner
    }
    val mood = when {
        !showsLive -> AskMood.Idle
        listening -> AskMood.Listening
        live.working -> AskMood.Thinking
        live.phase == Phase.Answering -> AskMood.Answering
        else -> AskMood.Idle
    }
    val croissant = rememberAskCroissant(spot, mood, level)
    val first = if (spot == AskSpot.None) FIRST else croissant.wordsTop()
    val (scroll, pull) = rememberWordsScroll(onBack)
    val arrived = remember(conv) { booleanArrayOf(false) }
    // Every arrival lands on the newest line, once there is something there;
    // a conversation just opened starts on its newest line.
    LaunchedEffect(conv, empty, kept.size, kept.lastOrNull()?.state, live.transcript, live.parts.size, live.answer, live.error) {
        if (empty) return@LaunchedEffect
        if (arrived[0]) scroll.animateScrollTo(scroll.maxValue)
        else { arrived[0] = true; scroll.scrollTo(scroll.maxValue) }
    }

    Box(Modifier.fillMaxSize()) {
        PageTop("ask", r) {
            // Always a scroll, empty too, so a finger has something to pull.
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                if (empty) Words { Text("hold to talk", style = WORDS, color = r.sub, modifier = Modifier.wordsAt(FIRST)) }
                else if (live.refused) Words {
                    Text(if (live.noServer) "no server yet" else live.error.orEmpty(), style = WORDS, color = r.danger, modifier = Modifier.wordsAt(FIRST))
                }
                else {
                    for ((i, t) in kept.withIndex()) Words { KeptTurn(t, r, if (i > 0) AFTER_TURN else first) }
                    if (showsLive) Words { Exchange(live, r, if (kept.isNotEmpty()) AFTER_TURN else first, chat = kept.isNotEmpty()) }
                }
                Spacer(Modifier.height(if (listening) 48.px else UNDER_KEYS.px))
            }
            // Stopped at the press: the words say why and the row opens what to set.
            if (live.refused) {
                val open = LocalOpenSetup.current
                val key = live.noKey
                Column(Modifier.padding(top = NO_SERVER_ROW.px)) {
                    when {
                        live.noServer -> ShelfRow("set it up", r, focused = true, chevron = true, onClick = { open(Step.Server) })
                        key != null -> ShelfRow("add it", r, focused = true, chevron = true, onClick = { onKey(key) })
                        else -> ShelfRow("pick one", r, focused = true, chevron = true, onClick = onModel)
                    }
                }
            }
            if (!listening) {
                KeyShade(r)
                QuietKeyAt(Glyphs.History, r, cx = KEY_LEFT, cy = KEY_Y, onClick = onHistory)
                if (!empty) QuietKeyAt(Glyphs.Plus, r, cx = KEY_RIGHT, cy = KEY_Y, onClick = onNew)
            }
        }
        AskCroissantLayer(croissant, r)
    }
}

/** Phases with the answer still to come: the transcript settling, or the model's answer not started. */
private val VoiceState.working get() = (phase == Phase.Transcribing || phase == Phase.Answering) && answer.isEmpty()

/** A turn's words, 24 in from each side; the blocks in it place themselves by baseline ([wordsAt]). */
@Composable
private fun Words(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(start = 24.px, end = 24.px), content = content)

/**
 * A turn as kept: the question in the hue, the answer's text and tool lines under it as they
 * came; the dots while its answer is still coming (behind a newer hold); why it failed in
 * danger. A turn heard by nobody yet has only the dots.
 */
@Composable
private fun ColumnScope.KeptTurn(t: Turn, r: Ramp, top: Float) {
    var next = top
    if (t.question.isNotBlank()) {
        Question(t.question, r.hue, top)
        next = AFTER_QUESTION
    }
    next = Parts(t.parts, r, next)
    when (t.state) {
        TurnState.Asking -> ThinkingDots(r, Modifier.padding(top = (next - DOT_ABOVE).px))
        TurnState.Failed -> Text(t.error ?: "Lost on the way", style = WORDS, color = r.danger, modifier = Modifier.wordsAt(next))
        TurnState.Done -> Unit
    }
}

/** A question as asked: ranged right, each line ending on the gutter, narrower than an answer so its side reads. */
@Composable
private fun Question(text: String, color: Color, top: Float) =
    Text(text, style = WORDS, color = color, textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(start = QUESTION_INSET.px).wordsAt(top))

/** The answer's text in white and its tool lines, [top] to the first and [AFTER_PART] between; returns where a next block's baseline goes. */
@Composable
private fun ColumnScope.Parts(parts: List<Part>, r: Ramp, top: Float): Float {
    var next = top
    for (part in parts) {
        when (part) {
            is Part.Text -> {
                val text = part.text.trim()
                if (text.isEmpty()) continue
                Text(text, style = WORDS, color = r.ink, modifier = Modifier.wordsAt(next))
            }
            is Part.Tool -> ToolLine(part.call, r, Modifier.wordsAt(next))
        }
        next = AFTER_PART
    }
    return next
}

/**
 * The exchange in flight, or the last one, as the kept turns are laid out: the question (in
 * white with the caret while it is being said, then in the hue, ranged right), the answer's
 * text and tool lines, and an error with its hint; the croissant thinks while nothing has come
 * back. Being said, the first question of a conversation runs from the left under the big
 * croissant, a follow-up ([chat]) on the right. [top] is the first baseline under what is above.
 */
@Composable
private fun ColumnScope.Exchange(state: VoiceState, r: Ramp, top: Float, chat: Boolean) {
    var next = top
    if (state.phase == Phase.Listening) {
        if (chat) CaretWords(state.transcript, r.ink, r.hue, Modifier.padding(start = QUESTION_INSET.px).wordsAt(top), align = TextAlign.End)
        else CaretWords(state.transcript, r.ink, r.hue, Modifier.wordsAt(top))
        return
    }
    if (state.transcript.isNotEmpty()) {
        // Still settling it is still yours, in white; once it is the question, the hue.
        Question(state.transcript, if (state.phase == Phase.Transcribing) r.ink else r.hue, top)
        next = AFTER_QUESTION
    }
    next = Parts(state.parts, r, next)
    state.error?.let {
        Text(it, style = WORDS, color = r.danger, modifier = Modifier.wordsAt(next))
        Text("hold to ask again", style = BeamType.Value, color = r.sub, modifier = Modifier.wordsAt(HINT))
    }
}

/**
 * A tool call where it happened: its feature's glyph 28 in sub, then what it did in sub ("adding
 * event", the app's words, so lowercase) 12 after it, and the one value that matters in white (the
 * user's, as it is), at a value's 28, one line. A call that failed is at 45%.
 */
@Composable
private fun ToolLine(call: ToolCall, r: Ramp, modifier: Modifier) {
    val glyph = toolGlyph(call)
    val inline = remember(glyph, r.sub) {
        mapOf("glyph" to InlineTextContent(Placeholder(40f.pxSp, 28f.pxSp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize()) { Icon(glyph, null, Modifier.size(28.px), r.sub) }
        })
    }
    Text(
        buildAnnotatedString {
            appendInlineContent("glyph", " ")
            append(call.label.lowercase())
            call.value?.let { v -> append("  "); withStyle(SpanStyle(color = r.ink)) { append(v) } }
        },
        style = BeamType.Value, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = inline,
        modifier = modifier.graphicsLayer { alpha = if (call.failed) 0.45f else 1f },
    )
}

/**
 * Words as they are being said: [text] in [ink] with a caret 3 x 34 in [hue] 6 px after the last
 * word, centred on the line (the Translator's listening too), the lines ranged by [align]. Empty,
 * the caret alone shows where the words will come.
 */
@Composable
internal fun CaretWords(text: String, ink: Color, hue: Color, modifier: Modifier = Modifier, style: TextStyle = WORDS, align: TextAlign = TextAlign.Start) {
    val caret = remember(hue) {
        mapOf("caret" to InlineTextContent(Placeholder(9f.pxSp, 34f.pxSp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize().padding(start = 6.px).background(hue))
        })
    }
    Text(
        buildAnnotatedString { append(text); appendInlineContent("caret", "|") },
        style = style, color = ink, inlineContent = caret, textAlign = align, modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The server at work: three dots r 8 in the hue, 28 apart from x 34 (their row starts on the text's
 * edge, x 24), fading in turn left to right, a round every 1.2 s. 16 tall; the caller sets how far
 * under the words they sit. The fade is read in the draw, so it recomposes nothing.
 */
@Composable
internal fun ThinkingDots(r: Ramp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots").animateFloat(
        0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dotsT",
    )
    Canvas(modifier.size(76.px, 16.px)) {
        // Page pixels by hand: pageScale is for a canvas 480 across.
        val u = 1.px.toPx()
        for (i in 0 until 3) {
            // How long since the pulse passed this dot, in dots: bright on it, down to a quarter two later.
            val since = ((t.value - i) % 3f + 3f) % 3f
            val a = 0.25f + 0.75f * (1f - since / 2f).coerceIn(0f, 1f)
            dot((8f + i * 28f) * u, 8f * u, 8f * u, r.hue.copy(alpha = a))
        }
    }
}

/**
 * Sets a block of words so its first baseline is [top] page px under where it starts, and ends the
 * block on its last baseline, so the next block measures its gap from there (every line is placed
 * by its baseline). The descent draws past the end.
 */
internal fun Modifier.wordsAt(top: Float): Modifier = layout { m, c ->
    val p = m.measure(c)
    val first = p[FirstBaseline].takeUnless { it == AlignmentLine.Unspecified } ?: 0
    val last = p[LastBaseline].takeUnless { it == AlignmentLine.Unspecified } ?: p.height
    val above = top.px.roundToPx() - first
    layout(p.width, maxOf(0, above + last)) { p.place(0, above) }
}

/** Words that are not a row, 42 a line. */
private val WORDS: TextStyle get() = BeamType.Body

/** The first line's baseline under the header: page y 112. */
private const val FIRST = 46f

/** A turn's last baseline to the next question's first. */
private const val AFTER_TURN = 86f
/** How far a question's lines start in from the words' left edge: they run 400 at most, to the gutter at x 456. */
private const val QUESTION_INSET = 32f
/** The keys' centres: History bottom left, "+" bottom right, 24 in from each side. */
private const val KEY_LEFT = 66f
private const val KEY_RIGHT = 414f
private const val KEY_Y = 574f

/** The "set it up" row's top under "no server yet": 40 under its baseline. */
private const val NO_SERVER_ROW = 86f
/** The question's last baseline to the answer's (or the error's) first. */
private const val AFTER_QUESTION = 74f
/** One part of the answer's last baseline to the next one's first. */
private const val AFTER_PART = 54f
/** An error to its hint. */
private const val HINT = 42f
/** The dots' top above where the next baseline would be, so their middle is 60 under the question's last. */
private const val DOT_ABOVE = 22f
