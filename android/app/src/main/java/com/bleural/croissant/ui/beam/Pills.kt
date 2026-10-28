package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.delay

/*
 * Beam's pills: a word on a fully round fill that takes a tap ([Pill]), and the one at a page's
 * foot that asks "sure?" before it acts ([SurePill]). Page pixels.
 */

/** A pill: 46 tall, fully round, the word 30 Medium centred, 20 either side, pushed in as it is tapped (Push.kt). Every send is one, in the hue with a black word. */
@Composable
internal fun Pill(word: String, fill: Color, ink: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier) {
        Box(
            Modifier.height(46.px).pushable(source).clip(RoundedCornerShape(23.px)).background(fill)
                .clickable(interactionSource = source, indication = null, onClick = onClick).padding(start = 20.px, end = 20.px),
        ) {
            Text(word, style = PILL, color = ink, maxLines = 1, softWrap = false, modifier = Modifier.paddingFromBaseline(top = 33.px))
        }
    }
}

internal val PILL = BeamType.Value.copy(fontSize = 30f.pxSp, fontWeight = FontWeight.Medium)

/** A pill at the foot ([SurePill]): its right edge, its top; 46 tall, as every pill ([Pill]). */
internal const val PILL_RIGHT = 456f
internal const val PILL_TOP = 570f

/** Where the foot's tap area starts (page y): the foot pill's row, to the bottom. */
private const val FOOT_TOP = 560f

/** How long "sure?" waits for the second tap before it is the word again (2026-10-09: about 3 s). */
private const val SURE_MS = 3_000L

/**
 * A pill tapped twice: at the foot, its right edge on [PILL_RIGHT] and its top on [PILL_TOP],
 * [word] black on white; a first tap turns it red, "sure?", for [SURE_MS]; a second, [onSure].
 * Forget, remove and the like on a settings page.
 */
@Composable
internal fun BoxScope.SurePill(word: String, onSure: () -> Unit) {
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(SURE_MS); sure = false } }
    val k = animateFloatAsState(if (sure) 1f else 0f, SURE_SPRING, label = "sure")
    val text = rememberTextMeasurer(cacheSize = 4)
    val wide = remember(word) { text.measure(word, PILL).size.width + 40f }
    val narrow = remember { text.measure(SURE, PILL).size.width + 40f }
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = (480f - PILL_RIGHT - 12f).px)
            .width((maxOf(wide, narrow) + 24f).px).height((640f - FOOT_TOP).px)
            .drawBehind {
                // In page pixels, this box from PILL_RIGHT + 12 less its width across and from FOOT_TOP down.
                val u = size.height / (640f - FOOT_TOP)
                val left = PILL_RIGHT + 12f - (maxOf(wide, narrow) + 24f)
                scale(u, u, pivot = Offset.Zero) {
                    translate(-left, 0f) {
                        val s = k.value.coerceIn(0f, 1f)
                        footPill(text, wide + (narrow - wide) * s, PILL_TOP - FOOT_TOP, lerp(INK, DANGER, s), word, 1f, s)
                    }
                }
            }
            .tappable { if (sure) onSure() else { sure = true; Cues.play(Sound.SheetIn) } },
    )
}

/** The foot's pill, in page x: [w] wide ending on [PILL_RIGHT], from [y], 46 tall, [fill]; [word] at [a] and "sure?" at [s] of it, black, centred. */
private fun DrawScope.footPill(text: TextMeasurer, w: Float, y: Float, fill: Color, word: String, a: Float, s: Float) {
    val x = PILL_RIGHT - w
    rect(x, y, w, 46f, fill, 23f)
    if (a <= 0f) return
    if (s < 1f) text(text, x + w / 2f, y + 33f, word, PILL, GROUND.copy(alpha = a * (1f - s)), Anchor.Middle)
    if (s > 0f) text(text, x + w / 2f, y + 33f, SURE, PILL, GROUND.copy(alpha = a * s), Anchor.Middle)
}

/** "sure?" coming in and going: no overshoot. */
private val SURE_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 600f)

private const val SURE = "sure?"
