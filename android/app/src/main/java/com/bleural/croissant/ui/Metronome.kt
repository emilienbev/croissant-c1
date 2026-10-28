package com.bleural.croissant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.bleural.croissant.metronome.Metronome
import java.time.LocalDate

/** The beat being heard, read off the metronome every frame while it plays. */
@Composable
fun rememberBeat(m: Metronome, playing: Boolean): Int {
    var beat by remember { mutableIntStateOf(-1) }
    LaunchedEffect(playing) {
        if (!playing) { beat = -1; return@LaunchedEffect }
        while (true) withFrameNanos { beat = m.beatNow() }
    }
    return beat
}

/** A practice time: minutes under the hour ("18 min"), hours and minutes past it ("2h 05"). */
fun practiceTime(ms: Long): String {
    val min = ms / 60_000
    return if (min < 60) "$min min" else "${min / 60}h ${(min % 60).toString().padStart(2, '0')}"
}

/** Days played in a row, a minute or more each, up to today, or to yesterday while today has none yet. */
fun practiceStreak(log: Map<LocalDate, Long>, today: LocalDate): Int {
    var d = if ((log[today] ?: 0L) >= 60_000) today else today.minusDays(1)
    var n = 0
    while ((log[d] ?: 0L) >= 60_000) { n++; d = d.minusDays(1) }
    return n
}
