package com.bleural.croissant.ui.beam

import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.AgentChat
import com.bleural.croissant.AskOption
import com.bleural.croissant.ChatItem
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.net.DocBlock
import com.bleural.croissant.net.DocStream
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.rememberTextMeasurer
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.px
import com.bleural.croissant.ui.rememberPull

/**
 * A new chat in a folder (`Screen.AgentNew`): a card ringed in the hue, "hold to start a chat in
 * <folder>", until a hold fills it; once the bridge has made the session, the page is its
 * conversation, in the same place in the stack.
 */
@Composable
internal fun AgentNewPage(agent: String, cwd: String, core: Core, nav: NavState) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    val chat by core.agents.chat.collectAsStateWithLifecycle()
    val voice by core.voice.collectAsStateWithLifecycle()
    val c = chat?.takeIf { it.agent == agent && it.cwd == cwd }
    val became = c?.session?.takeIf { it.isNotEmpty() }
    LaunchedEffect(became) {
        if (became != null) nav.replace(Screen.AgentChat(agent, became, cwd), sound = null, inPlace = true)
    }
    if (became != null) return AgentChatPage(agent, became, core, nav)
    val name = ui.agents.firstOrNull { it.id == agent }?.name ?: agent
    val folder = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
    val r = agentLook(agent).ramp
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop(name, r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, INK, GROUND).verticalScroll(scroll)) {
            Spacer(Modifier.height(8.px))
            val held = Held.of(voice, c?.sending, c?.unsent)
            if (held == null) {
                Box(Modifier.padding(start = 16.px, end = 16.px).fillMaxWidth().height(152.px)
                    .border(3.px, r.hue, RoundedCornerShape(16.px))) {
                    Text("hold to start a chat in $folder", style = BeamType.Body, color = SUB,
                        modifier = Modifier.padding(start = 20.px, end = 28.px).wordsAt(50f))
                }
            } else HeldCard(held, r.hue, core.level::value, onRetry = core.agents::retry, away = "the bridge did not answer")
            Spacer(Modifier.height(40.px))
        }
        TopFade(r, scroll)
    }
}

/**
 * A session's conversation (HeldCard.kt): your words are cards in the hue, black words; the agent's
 * are plain under them, each tool call a line in sub with its glyph. While a turn runs, the dots
 * and stop at the foot; when the agent asks to do something, its question as a card ringed in the
 * hue with its own options. A hold here is the next prompt, its card at the foot as on a new chat.
 * The wheel scrolls; the page opens on its newest line. The folder key at the foot opens the
 * session's folder (AgentFiles.kt).
 */
@Composable
internal fun AgentChatPage(agent: String, session: String, core: Core, nav: NavState) {
    val ui by core.agents.state.collectAsStateWithLifecycle()
    val chat by core.agents.chat.collectAsStateWithLifecycle()
    val voice by core.voice.collectAsStateWithLifecycle()
    val c = chat?.takeIf { it.agent == agent && it.session == session }
    val name = ui.agents.firstOrNull { it.id == agent }?.name ?: agent
    // Lazy, so a long session draws only what is in view: the wheel scrolls 96 a tick, up at
    // the top is the pull back.
    val list = rememberLazyListState()
    val pull = rememberPull(nav::pop)
    LazyWheel(list, pull, 96f)
    val held = Held.of(voice, c?.sending, c?.unsent)
    val r = agentLook(agent).ramp
    val scope = rememberCoroutineScope()
    val far = with(LocalDensity.current) { FAR.px.toPx() }
    // Shown once the bridge has sent what it holds, already at its newest line: laid out unseen,
    // put at the foot, then faded in, so nothing is seen scrolling. Until then the opening words.
    var revealed by remember(session) { mutableStateOf(false) }
    val opened = c?.opened == true
    LaunchedEffect(session, opened) {
        if (!opened || revealed) return@LaunchedEffect
        withFrameNanos { }
        list.scrollToItem(foot(list))
        withFrameNanos { }
        revealed = true
    }
    val shown by animateFloatAsState(if (revealed) 1f else 0f, tween(REVEAL_MS), label = "reveal")
    // How far the newest line is under the view: none when the foot's bottom is in it.
    val below by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null || last.index < info.totalItemsCount - 1) Float.MAX_VALUE
            else (last.offset + last.size - info.viewportEndOffset).toFloat().coerceAtLeast(0f)
        }
    }
    // New lines follow the bottom only while the reader is there: scrolled back, they stay put.
    // Only a scroll up lets go of the bottom: following it only ever goes down, so a follow cut
    // short by the next chunk, the newest line already further on, never stops the following.
    var stick by remember(session) { mutableStateOf(true) }
    LaunchedEffect(list) {
        var from = 0 to 0
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            val at = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            if (moving) from = at
            else if (below < far) stick = true
            else if (at.first < from.first || (at.first == from.first && at.second < from.second)) stick = false
        }
    }
    LaunchedEffect(c?.items?.size, (c?.items?.lastOrNull() as? ChatItem.Said)?.words?.length, c?.running, c?.asking, held) {
        if (!revealed || !stick) return@LaunchedEffect
        withFrameNanos { }
        list.animateScrollToItem(foot(list))
    }
    Box(Modifier.fillMaxSize()) {
        PageTop(name, r) {
            LazyColumn(state = list, userScrollEnabled = revealed,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown }.elasticPull(pull, INK, GROUND)) {
                item(key = "top") { Spacer(Modifier.height(8.px)) }
                if (c != null) items(c.items.size, key = { it }) { i -> ChatItemView(c.items, i, r, streaming = c.running && i == c.items.lastIndex) }
                item(key = "foot") {
                    Column {
                        if (c != null) {
                            val asking = c.asking
                            if (asking != null) Question(asking.title, lastTool(c), asking.options, r, onPick = { o ->
                                Cues.play(if (o == null || o.kind.startsWith("reject")) Sound.Destroy else Sound.Commit)
                                core.agents.permit(o?.id)
                            })
                            else if (c.running && held == null) Working(r, onStop = { Cues.play(Sound.Destroy); core.agents.stop() })
                            if (c.error != null && c.opened && !c.running) {
                                Text(c.error, style = BeamType.Body, color = r.danger, modifier = Modifier.padding(start = 24.px, end = 24.px).wordsAt(50f))
                                Spacer(Modifier.height(34.px))
                            }
                        }
                        if (held != null) {
                            HeldCard(held, r.hue, core.level::value, onRetry = core.agents::retry, away = "the bridge did not answer")
                            Spacer(Modifier.height(16.px))
                        }
                        // Room for the folder key at the foot, so the last line is never under it.
                        Spacer(Modifier.height(if (c != null) 124.px else 40.px))
                    }
                }
            }
            TopFade(r.ground) { list.scrolled() }
            if (!revealed) Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(8.px))
                SettingsWords(c?.error ?: "opening the session", if (c?.error != null) r.danger else r.sub, top = 28f)
                if (c?.error == null) ThinkingDots(r, Modifier.padding(start = 26.px, top = 30.px))
            }
            // Down to the newest line, while it is far under the view.
            DownKey(r, shown = revealed && below > far, onClick = {
                scope.launch {
                    val to = foot(list)
                    if (to - list.firstVisibleItemIndex > LONG_WAY) list.scrollToItem(to - LONG_WAY)
                    list.animateScrollToItem(to)
                    stick = true
                }
            })
            // The session's folder, to look through (AgentFiles.kt), on a black disc so its ring
            // shows over a card in the hue scrolled under it.
            if (c != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.px, bottom = 24.px).size(84.px).drawBehind { drawCircle(GROUND) })
                QuietKey(Glyphs.Folder, r, onClick = { nav.push(Screen.AgentFiles(agent, c.cwd)) })
            }
        }
        // The header word, tapped: back up to the first line, as a phone's clock does.
        Box(Modifier.fillMaxWidth().height(CONTENT_TOP.px).tappable { if (revealed) scope.launch { list.animateScrollToItem(0) } })
    }
}

/** The foot's index: the last item, the list's end. */
private fun foot(list: LazyListState): Int = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)

/** How far under the view the newest line is before the down key shows; how long a way is jumped before the last glide. */
private const val FAR = 240f
private const val LONG_WAY = 12
private const val REVEAL_MS = 180

/**
 * The key down to the newest line: a ring 60 in the hue with the arrow 30, centred across, on
 * the keys' line (its middle at y 574), coming and going with a fade.
 */
@Composable
private fun BoxScope.DownKey(r: Ramp, shown: Boolean, onClick: () -> Unit) {
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(160), label = "down")
    if (a <= 0.01f) return
    Box(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 36.px).size(60.px).graphicsLayer { alpha = a }
            .drawBehind { drawCircle(GROUND); drawCircle(r.hue, size.width / 2f - 1.5f * size.width / 60f, style = Stroke(3f * size.width / 60f)) }
            .clip(CircleShape).tappable(if (shown) onClick else null),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyphs.ArrowDown, null, Modifier.size(30.px), r.hue) }
}

/** Item [i] of a conversation: your words a card, the agent's as markdown ([streaming] while they arrive), a tool call its line. */
@Composable
private fun ChatItemView(items: List<ChatItem>, i: Int, r: Ramp, streaming: Boolean) {
    when (val item = items[i]) {
        is ChatItem.You -> Column {
            Message(item.words.trim(), r.hue)
            if (items.getOrNull(i + 1) is ChatItem.You) Spacer(Modifier.height(16.px))
        }
        is ChatItem.Said -> if (item.words.isNotBlank()) Column {
            SaidWords(item.words, streaming, r)
            Spacer(Modifier.height(34.px))
        }
        is ChatItem.Tool -> ToolLine(item, r, last = items.getOrNull(i + 1) !is ChatItem.Tool)
    }
}

/**
 * An agent's words as markdown, set as on a file's page (Files.kt), code and dots in the agent's
 * hue. Finished, they are read whole, once. While they stream, what is finished is read once
 * more only when one more block is ([DocStream.settled]), and the block being written with each
 * chunk, closed for the time being ([DocStream.closed]): an open bold already bold, a link not
 * yet whole its words.
 */
@Composable
private fun SaidWords(words: String, streaming: Boolean, r: Ramp) {
    val work = rememberWorkMeasurer()
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    val draw = rememberTextMeasurer(cacheSize = 32)
    val done = if (streaming) words.substring(0, DocStream.settled(words)) else words
    val head = remember(done) { DocBlock.markdown(done) }
    val headSet = remember(head, r.hue) { mdBlocks(head, work, unit, r.hue, lead = SAID_LEAD) }
    val tail = if (streaming) words.substring(done.length) else ""
    val tailSet = remember(tail, head, r.hue) {
        if (tail.isBlank()) emptyList()
        else mdBlocks(DocBlock.markdown(DocStream.closed(tail)), work, unit, r.hue, after = head.lastOrNull { it !is DocBlock.Rule }, lead = SAID_LEAD)
    }
    CompositionLocalProvider(LocalFileHue provides r.hue) {
        Column { (headSet + tailSet).forEach { MdBlockView(it, draw) } }
    }
}

/** An agent's first block sits this much nearer the item above than on a file's page: a paragraph's first baseline 50 under it. */
private const val SAID_LEAD = 8f

/** The tool call the agent is on: the last one not yet done. */
private fun lastTool(c: AgentChat): String? =
    c.items.lastOrNull { it is ChatItem.Tool && it.status != "completed" && it.status != "failed" }?.let { (it as ChatItem.Tool).title }

/** A tool's glyph by ACP's kind: a terminal to run, a text to edit, a file to read; none for the rest. */
private fun toolGlyph(kind: String): ImageVector? = when (kind) {
    "execute" -> Glyphs.Terminal
    "edit", "delete", "move" -> Glyphs.Text
    "read" -> Glyphs.File
    else -> null
}

/** One tool call, once done: its glyph 28 and its title in sub, one line; failed in danger. */
@Composable
private fun ToolLine(t: ChatItem.Tool, r: Ramp, last: Boolean) {
    val glyph = toolGlyph(t.kind)
    val colour = if (t.status == "failed") r.danger else SUB
    Box(Modifier.fillMaxWidth().height(if (last) 76.px else 52.px)) {
        if (glyph != null) Icon(glyph, null, Modifier.padding(start = 24.px, top = 14.px).size(28.px), colour)
        Text(t.title.ifBlank { t.kind }, style = BeamType.Value, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (if (glyph != null) 64f else 24f).px, end = 24.px).wordsAt(38f))
    }
}

/** A turn running, at the conversation's foot: the dots, stop in danger at the right. */
@Composable
private fun Working(r: Ramp, onStop: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(100.px)) {
        ThinkingDots(r, Modifier.padding(start = 26.px, top = 40.px))
        Pill("stop", DANGER, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 25.px, end = 24.px), onStop)
    }
}

/**
 * The agent asks before it acts: a card ringed in the hue, what it asks ([title]) and what it
 * is about to do ([doing]) under it, then its options as pills, allow in the hue, the others
 * black ringed, reject in danger.
 */
@Composable
private fun Question(title: String, doing: String?, options: List<AskOption>, r: Ramp, onPick: (AskOption?) -> Unit) {
    Box(Modifier.padding(start = 16.px, end = 16.px, top = 8.px).fillMaxWidth().border(3.px, r.hue, RoundedCornerShape(16.px))) {
        Column(Modifier.fillMaxWidth()) {
            Text(title.ifBlank { "may it go on?" }, style = BeamType.Body, color = INK,
                modifier = Modifier.padding(start = 20.px, end = 28.px).wordsAt(48f))
            if (!doing.isNullOrBlank() && doing != title) Text(doing, style = BeamType.Value, color = SUB, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.px, end = 28.px).wordsAt(40f))
            Row(Modifier.padding(start = 20.px, end = 20.px, top = 24.px, bottom = 22.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                options.forEach { o ->
                    val (fill, ink) = when {
                        o.kind == "allow_once" -> r.hue to GROUND
                        o.kind.startsWith("reject") -> DANGER to GROUND
                        else -> SUB to GROUND
                    }
                    Pill(optionWord(o), fill, ink) { onPick(o) }
                }
            }
        }
    }
    Spacer(Modifier.height(24.px))
}

/** An option's word: short by its kind, the agent's own name for a kind ACP does not know. */
private fun optionWord(o: AskOption): String = when (o.kind) {
    "allow_once" -> "allow"
    "allow_always" -> "always"
    "reject_once" -> "reject"
    "reject_always" -> "never"
    else -> o.name.lowercase()
}
