package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest

/**
 * A button's push (2026-10-17): pressed, it sinks to [PUSHED] on a stiff spring; let go, it
 * springs back a little past its size and settles. A tap too quick to have sunk still dips
 * first, so every tap shows; a press that turns into a scroll just comes back. The pills'
 * ([Pill], every Atelier app's).
 */
@Composable
internal fun Modifier.pushable(source: InteractionSource): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(source) {
        source.interactions.collectLatest { i ->
            when (i) {
                is PressInteraction.Press -> scale.animateTo(PUSHED, SINK)
                is PressInteraction.Release -> {
                    if (scale.value > PUSHED + 0.02f) scale.animateTo(PUSHED, DIP)
                    scale.animateTo(1f, BACK)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, BACK)
            }
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

private const val PUSHED = 0.9f
/** Down in about 40 ms, no bounce. */
private val SINK = spring<Float>(dampingRatio = 1f, stiffness = 4500f)
/** A tap's dip when it came up before it sank: a fixed 50 ms, so the way back starts at once. */
private val DIP = tween<Float>(50, easing = FastOutLinearInEasing)
/** Back in about 180 ms, over by a few percent once. */
private val BACK = spring<Float>(dampingRatio = 0.5f, stiffness = 1500f)
