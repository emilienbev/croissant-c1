package com.bleural.croissant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The control centre, pulled down from the top edge on any screen (it was the top bar's drag
 * until the bar went, 2026-10-01) and put away with a push up on the panel, or a tap on the
 * scrim. It is a card in the surface grey hanging from the top of the canvas, its bottom
 * corners rounded, over a scrim that dims the whole panel behind it; it follows the finger
 * like the edge drag and settles once a third of the way. Two level bars on the left, Sound
 * and Display, a tap or a drag setting them as on their pages; six tiles on the right,
 * Wi-Fi and Bluetooth, the tailnet and Sound, the battery and Power. Wi-Fi, Bluetooth and
 * the tailnet: a tap flips it, a long press opens its page. Sound: a tap mutes everything
 * but a ringing timer, and the sound bar dims. The battery: its level filling the tile from
 * the bottom under the percent, a tap opens Battery. Power: a tap offers Sleep, Restart and
 * Power off through the sheet. A tile is lit in the settings grey with a black glyph when
 * its thing is on, dark with a muted glyph when off; Power is red with a white glyph
 * (2026-09-16). The bars and the tiles share the radius of 20. The wheel does
 * nothing while it is open. The panel is 442 px tall: 22 in, the bars 60 x 362 with their
 * glyphs 12 under, the tiles 110 with 16 between, 22 out.
 */
class ControlState(private val scope: CoroutineScope) {
    /** 0 parked above the canvas, 1 fully down. */
    val shown = Animatable(0f)
    var open by mutableStateOf(false)
        private set
    /** The panel's height in px, from its layout; the drag is measured against it. */
    var height = 1

    fun drag(dy: Float) { scope.launch { shown.snapTo((shown.value + dy / height).coerceIn(0f, 1f)) } }

    /** The finger lifted: a third of the way from where the panel was, it goes on; else it returns. */
    fun release() {
        if (open) { if (shown.value < 2f / 3) close() else open() }
        else { if (shown.value > 1f / 3) open() else close(null) }
    }

    fun open() {
        if (!open) Cues.play(Sound.PanelIn)
        open = true
        scope.launch { shown.animateTo(1f, SPEC) }
    }

    /** [sound] is null when something else speaks: the page a long press opens, the sleep a tile chose. */
    fun close(sound: Sound? = Sound.PanelOut) {
        if (open) sound?.let(Cues::play)
        open = false
        scope.launch { shown.animateTo(0f, SPEC) }
    }

    private companion object {
        val SPEC = tween<Float>(120, easing = LinearEasing)
    }
}

/**
 * The pull from the top edge, on the whole canvas, now that there is no top bar to hold it
 * (2026-10-01): a finger that lands in the top [TOP_EDGE] px and goes down past the
 * slop brings the control centre down with it, as the bar's drag did. It watches in the
 * Initial pass so it has the drag before a scrolling page, a pull or the room under it, and
 * it takes nothing else: a tap there, or a drag that sets off sideways or up, is the page's.
 * A shell puts it on what the control centre hangs over, not on the control centre, which
 * has its own [controlDrag].
 */
fun Modifier.controlEdge(state: ControlState): Modifier = pointerInput(state) {
    val edge = TOP_EDGE.px.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.y > edge || state.shown.value > 0f) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var pulling = false
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            if (pulling) { state.drag(c.positionChange().y); c.consume(); continue }
            val d = c.position - down.position
            if (d.y > slop && d.y > abs(d.x)) { pulling = true; state.drag(d.y - slop); c.consume() }
            else if (d.getDistance() > slop) return@awaitEachGesture
        }
        if (pulling) state.release()
    }
}

/** How far down from the top a finger may land and still pull the control centre: the old bar's 46. */
const val TOP_EDGE = 46

/** The pull: on the top edge it brings the panel down or pushes it back; on the panel it does the same. */
fun Modifier.controlDrag(state: ControlState): Modifier = pointerInput(state) {
    detectVerticalDragGestures(
        onVerticalDrag = { change, dy -> change.consume(); state.drag(dy) },
        onDragEnd = { state.release() },
        onDragCancel = { state.release() },
    )
}
