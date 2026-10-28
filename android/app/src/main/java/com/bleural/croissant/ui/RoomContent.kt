package com.bleural.croissant.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bleural.croissant.audio.RecPhase
import com.bleural.croissant.audio.RecorderState
import com.bleural.croissant.data.Event
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.Timer
import com.bleural.croissant.weather.Weather
import java.io.File
import java.time.OffsetDateTime

/** What a feature says under its word on its panel: the big value and the muted word beside it. Either may be absent. */
data class RoomLine(val value: String? = null, val muted: String? = null)

/**
 * What each panel says under its word: one big value with a muted word beside it, or
 * nothing.
 * The copy rule holds here as everywhere, if there is nothing to say say nothing, so a
 * feature with no news shows its object and its word alone.
 *
 * The two that run (the recorder's counter, the soonest timer) read the second here,
 * once a second, which re-measures three lines of text and nothing else.
 */
@Composable
fun rememberRoomLines(
    events: List<Event>,
    reminders: List<Reminder>,
    timers: List<Timer>,
    photos: List<File>,
    recordings: Int,
    recorder: RecorderState,
    elapsed: () -> Long,
    practiceMs: () -> Long,
    translator: LanguagePair,
    weather: Weather? = null,
): (Feature) -> RoomLine {
    val twentyFour = DateFormat.is24HourFormat(LocalContext.current)
    val minute by rememberMinuteTick()
    val second by rememberSecondTick()
    val now = remember(minute) { OffsetDateTime.now() }

    val taping = recorder.phase != RecPhase.Idle
    val running = timers.isNotEmpty()
    val lines = remember(events, reminders, timers, photos, recordings, now, recorder.phase, second, translator, weather) {
        val open = reminders.count { !it.done }
        val next = events.asSequence()
            .filter { !it.allDay }
            .mapNotNull { runCatching { OffsetDateTime.parse(it.start).withOffsetSameInstant(now.offset) }.getOrNull() }
            .filter { it.isAfter(now) && it.toLocalDate() == now.toLocalDate() }
            .minOrNull()
        val soonest = if (running) timers.map { it.millisLeft(System.currentTimeMillis()) }.filter { it > 0 }.minOrNull() else null
        mapOf(
            Feature.Ask to RoomLine(muted = "Hold to talk"),
            Feature.Recorder to when {
                taping -> RoomLine(counterText(elapsed()), if (recorder.phase == RecPhase.Paused) "Paused" else "Recording")
                else -> RoomLine(recordings.toString(), if (recordings == 1) "tape" else "tapes")
            },
            // Every panel with something to count says it, none included (2026-09-22).
            Feature.Reminders to RoomLine(open.toString(), "to do"),
            Feature.Timers to (soonest?.let { RoomLine(countdownText(it), "left") } ?: RoomLine("0", "timers")),
            Feature.Camera to RoomLine(),
            Feature.Calendar to (next?.let { RoomLine(clock(it, twentyFour), "next") } ?: RoomLine("0", "left today")),
            // Home's weather (here, or the pinned place without a recent fix): the degrees and the sky.
            Feature.Weather to (fresh(weather, now.toInstant().toEpochMilli())?.let { w -> RoomLine("${w.temp}°", skyWord(w, daylight(w, now.toLocalDateTime()))) } ?: RoomLine()),
            Feature.Photos to RoomLine(photos.size.toString(), if (photos.size == 1) "photo" else "photos"),
            // Today's practice rather than the tempo, which the page shows anyway.
            Feature.Metronome to (practiceMs() / 60_000).let { min ->
                if (min < 60) RoomLine(min.toString(), "min today") else RoomLine("${min / 60}h ${(min % 60).toString().padStart(2, '0')}", "today")
            },
            Feature.Translator to RoomLine(muted = "${translator.from.word} to ${translator.to.word}"),
            Feature.Settings to RoomLine(),
        )
    }
    return { f -> lines[f] ?: RoomLine() }
}
