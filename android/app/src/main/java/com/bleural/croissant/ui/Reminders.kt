package com.bleural.croissant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bleural.croissant.data.Reminder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A day line of the list: its word, its colour, and the rows under it. */
data class ReminderSection(val label: String, val colour: androidx.compose.ui.graphics.Color, val rows: List<ReminderRow>)

/** One row of the list. A class of its own so the line list can tell rows from day lines. */
data class ReminderRow(val reminder: Reminder)

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/**
 * The list in order: the open reminders under the day they were added, newest day
 * first and newest first within it, then Done, most recently ticked first. Empty
 * sections are left out. A reminder whose `createdAt` does not parse files under the
 * oldest day.
 */
fun reminderSections(items: List<Reminder>, now: OffsetDateTime): List<ReminderSection> {
    val today = now.toLocalDate()
    val byDay = sortedMapOf<LocalDate, ArrayList<Reminder>>(compareByDescending { it })
    for (r in items) {
        if (r.done) continue
        byDay.getOrPut(addedDay(r, now)) { ArrayList() } += r
    }
    val out = ArrayList<ReminderSection>()
    for ((day, rows) in byDay) out += ReminderSection(addedLabel(day, today), Hue.Reminders, rows.sortedByDescending { it.createdAt }.map(::ReminderRow))
    val done = items.filter { it.done }.sortedByDescending { it.doneAt ?: "" }.map(::ReminderRow)
    if (done.isNotEmpty()) out += ReminderSection("Done", Ink.Muted, done)
    return out
}

/** The device-zone day a reminder was added. */
fun addedDay(r: Reminder, now: OffsetDateTime): LocalDate =
    runCatching { OffsetDateTime.parse(r.createdAt).atZoneSameInstant(now.offset).toLocalDate() }.getOrDefault(LocalDate.MIN)

/** "Today", "Yesterday", else the calendar's day line ("Mon 14 Sept"). */
fun addedLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    LocalDate.MIN -> "Earlier"
    else -> day.format(DAY)
}

/** Bumps on the minute, on a time or zone change and on screen-on, so relative dates stay right. */
@Composable
fun rememberMinuteTick(): State<Long> {
    val ctx = LocalContext.current
    val state = remember { mutableLongStateOf(0L) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { state.longValue++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return state
}
