package com.bleural.croissant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.Phase
import com.bleural.croissant.ToolCall
import com.bleural.croissant.VoiceState
import com.bleural.croissant.data.Conversation
import com.bleural.croissant.data.Turn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * What every design's Ask and History draw from: the open
 * conversation with the exchange in flight, the conversations by day, a tool line's glyph.
 * The look is each design's own; this is only the reading of Core.
 */

/**
 * Ask's conversation: [turns] are the open one's kept turns ([conv], null for a blank page),
 * [live] the exchange in flight or just finished when it is this conversation's (otherwise an
 * empty state), drawn in place of its kept copy, which [kept] leaves out.
 */
class AskConversation(val live: VoiceState, val conv: String?, val turns: List<Turn>) {
    val kept: List<Turn> get() = turns.filter { it.id != live.turn }
    /** Nothing kept and nothing live: the page says "hold to talk" and has no "+". */
    val blank: Boolean get() = kept.isEmpty() && live.empty
}

/**
 * Reads Ask's conversation from [core]. A hold about the camera's photo, a transform or a
 * translation is someone else's and never lands on Ask.
 */
@Composable
fun rememberAskConversation(core: Core, voice: VoiceState): AskConversation {
    val conversations by core.conversations.items.collectAsStateWithLifecycle()
    val open by core.askConversation.collectAsStateWithLifecycle()
    val live = if (voice.conv == open && voice.translate == null && !voice.transform && !voice.withPhoto) voice else VoiceState()
    val turns = remember(conversations, open) { conversations.firstOrNull { it.id == open }?.turns.orEmpty() }
    return AskConversation(live, open, turns)
}

/** One day of History: its word ("Today", "Yesterday", "Sun 4 Oct") and its conversations, newest first. */
class HistoryDay(val label: String, val items: List<Conversation>)

/** The conversations by the day they were last asked in, newest first, with the photo sheet's day words. */
fun historyDays(items: List<Conversation>, today: LocalDate): List<HistoryDay> {
    val zone = ZoneId.systemDefault()
    return items.sortedByDescending { it.last }
        .groupBy { Instant.ofEpochMilli(it.last).atZone(zone).toLocalDate() }
        .map { (day, list) -> HistoryDay(if (day == today.minusDays(1)) "Yesterday" else dayLabel(day, today), list) }
}

/** The feature a tool line belongs to, by what it did: a picture, the agenda, a timer, a tape. */
fun toolGlyph(call: ToolCall): ImageVector {
    val l = call.label.lowercase()
    return when {
        call.generation != null || "image" in l || "photo" in l -> Glyphs.Generated
        "reminder" in l -> Glyphs.Reminders
        "timer" in l -> Glyphs.Timers
        "tape" in l -> Glyphs.Tape
        else -> Glyphs.Calendar
    }
}

/**
 * Nothing to read: a page waiting for a hold rather than one showing an exchange. One
 * question asked once, so every page that draws differently when empty agrees on it.
 */
val VoiceState.empty: Boolean
    get() = phase == Phase.Idle && transcript.isEmpty() && answer.isEmpty() && error == null
