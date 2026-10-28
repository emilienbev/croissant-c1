package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp

/*
 * The croissant on Ask (2026-10-28). It shows when you first hold in a new conversation, big in the
 * middle in place of the mic's level bars, listening; thinks there in place of the dots when you
 * let go; moves small into the top right corner, where Home's opened day puts it, when the answer
 * starts, and stays there for the rest of the conversation, listening, thinking and answering
 * there. A new conversation has none until you hold.
 */

/** Where the croissant is on Ask: not there, big in the middle, or small in the corner. */
internal enum class AskSpot { None, Middle, Corner }

/** What it acts: nothing over its idle, or one of [AskActs]. */
internal enum class AskMood(val act: CroissantAct?) {
    Idle(null), Listening(AskActs.listening), Thinking(AskActs.thinking), Answering(AskActs.answering)
}

/** The croissant on Ask: its rig, how far it is on its way to the corner (0 the middle, 1 the corner), and how much it shows. */
@Stable
internal class AskCroissant {
    val rig = IdleV3.rig()
    val corner = Animatable(1f)
    val shown = Animatable(0f)
    var acting: CroissantAct? = null
}

/**
 * The croissant for Ask at [spot], acting [mood], its listening following [heard]. It appears
 * where it is wanted, then moves between the spots as Home's day moves; each act is let go as
 * the next starts, so answering ends in its nod.
 */
@Composable
internal fun rememberAskCroissant(spot: AskSpot, mood: AskMood, heard: () -> Float): AskCroissant {
    val c = remember { AskCroissant() }
    SideEffect { c.rig.heard = heard }
    PlayCroissant(c.rig)
    LaunchedEffect(spot) {
        if (spot == AskSpot.None) { c.shown.snapTo(0f); return@LaunchedEffect }
        val to = if (spot == AskSpot.Middle) 0f else 1f
        if (c.shown.value == 0f) {
            c.corner.snapTo(to)
            c.shown.animateTo(1f, tween(APPEAR_MS))
        } else {
            c.corner.animateTo(to, tween(DAY_IN_MS, easing = DAY_MOVE))
        }
    }
    LaunchedEffect(mood) {
        val next = mood.act
        c.acting?.let { if (it !== next) c.rig.letGo(it) }
        if (next != null && !c.rig.holds(next)) c.rig.perform(next)
        c.acting = next
    }
    return c
}

/** The first baseline of Ask's words under the header, under the croissant wherever it is on its way. */
internal fun AskCroissant.wordsTop(): Float = lerp(MIDDLE_WORDS, CORNER_WORDS, corner.value)

/** The croissant over the page, on its own layer, so a frame of it records nothing else. */
@Composable
internal fun AskCroissantLayer(c: AskCroissant, r: Ramp) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = c.shown.value }) {
        if (c.shown.value == 0f) return@Canvas
        val p = c.corner.value
        pageScale {
            croissant(
                c.rig.pose(), lerp(MIDDLE_X, CORNER_X, p), lerp(MIDDLE_TOP, CORNER_TOP, p),
                lerp(MIDDLE_W, CORNER_W, p), lerp(MIDDLE_INK, CORNER_INK, p), r.ink, r.ground,
            )
        }
    }
}

// Big in the middle, as Home has it: centred, its top 112, 260 wide, a 7 px line.
private const val MIDDLE_X = 240f
private const val MIDDLE_TOP = 112f
private const val MIDDLE_W = 260f
private const val MIDDLE_INK = 7f
// In the corner, as Home's opened day has it: centred on x 392, its top 26, 124 wide, a 5 px line.
private const val CORNER_X = 392f
private const val CORNER_TOP = 26f
private const val CORNER_W = 124f
private const val CORNER_INK = 5f

/** The words' first baseline under the header: page y 392 under the big croissant, 176 under the corner's. */
private const val MIDDLE_WORDS = 392f - CONTENT_TOP
private const val CORNER_WORDS = 176f - CONTENT_TOP

/** How long it takes to come up when you first hold. */
private const val APPEAR_MS = 160

