package com.bleural.croissant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import com.bleural.croissant.data.Timer
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

/** Milliseconds until the timer ends at wall-clock [nowMillis], never negative. */
fun Timer.millisLeft(nowMillis: Long): Long =
    (end().toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)

/** The fraction of the timer still to run, 1 when its start is not known (a timer from an older file). */
fun Timer.fraction(nowMillis: Long): Float {
    val start = startedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: return 1f
    val total = end().toInstant().toEpochMilli() - start
    if (total <= 0) return 0f
    return (millisLeft(nowMillis).toFloat() / total).coerceIn(0f, 1f)
}

/**
 * "2:00" for two minutes, "1:02:05" past an hour, counting whole seconds that have not
 * yet fully elapsed: a fresh two-minute timer reads 2:00 for its first second and 0:01
 * for its last, then 0:00.
 */
fun countdownText(millisLeft: Long): String {
    val s = (millisLeft + 999) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * The wall clock in milliseconds, updated on every second boundary while the caller is
 * on screen. Read from the clock each time rather than counted, so a pause (the screen
 * off) cannot leave it behind.
 */
@Composable
fun rememberSecondTick(): State<Long> {
    // A screen under another (LocalLayerVisible, from Slides) is not drawn, so its
    // clocks stop: Home's live rows ticking under the recorder cost a 90 ms frame a
    // second on the panel (2026-09-16). Back in view, the first tick is immediate.
    val visible = LocalLayerVisible.current
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val t = System.currentTimeMillis()
            state.longValue = t
            delay(1000 - t % 1000)
        }
    }
    return state
}

/** [v] brought back into 0 until [n], either way round. */
fun wrap(v: Int, n: Int): Int = ((v % n) + n) % n
