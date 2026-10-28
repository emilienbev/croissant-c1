package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Event
import com.bleural.croissant.data.Reminder
import com.bleural.croissant.data.Timer
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.clock
import com.bleural.croissant.ui.countdownText
import com.bleural.croissant.ui.fraction
import com.bleural.croissant.ui.glance
import com.bleural.croissant.ui.millisLeft
import com.bleural.croissant.ui.todayItems
import java.time.OffsetDateTime

/*
 * Home's day (2026-10-27), in place of the Today page: a tap on Home opens it where Home is. The
 * croissant shrinks into the top right corner and looks down at it, the date and the clock go to
 * the top left, and the rest of the day comes up in the features' hues: the timers as rings,
 * today's events still ahead, the to-dos, each group headed by its feature's dot, which rises out
 * of the row at the foot. A tap on the croissant, the elastic pull (a finger down or the wheel up)
 * or the left edge puts it away. Every number is a page pixel.
 */

/**
 * Whether Home's day is open, and the move between the two. Beam's, because a wake (the side
 * button, outside the composition) puts the day away.
 */
@Stable
internal class HomeState {
    /** Whether the day is asked for; [progress] follows it. */
    var open by mutableStateOf(false); private set

    /** 0 Home, 1 the day: the move itself, read in draw blocks only. */
    var progress by mutableFloatStateOf(0f)

    /** The croissant, kept here so it picks up where it was when Home comes back. */
    val croissant = IdleV3.rig()

    /** Changes to send [progress] back to where [open] says after a scrub that kept it. */
    var settle by mutableIntStateOf(0); private set

    /** A finger on the left edge has [progress]; nothing animates it meanwhile. */
    var scrubbing = false

    fun openDay() { if (!open) { open = true; Cues.play(Sound.ScreenIn) } }

    fun shutDay(sound: Sound) { if (open) { open = false; Cues.play(sound) } }

    /** Home again at once, with no move and no sound: Home left, or the screen woken. */
    fun snapShut() { open = false; progress = 0f }

    fun resettle() { settle++ }
}

// The move: in and out on ease-in-out-quart, the way out the quicker.
internal const val DAY_IN_MS = 420
internal const val DAY_OUT_MS = 300

/** A timer's ring: its name, what it has left, and the fraction of it still to run. */
internal class DayTimer(val name: String, val left: String, val fraction: Float)

/** An event still ahead today: its start as the clock writes it, and its title. */
internal class DayEvent(val time: String, val title: String)

/** What the day shows: at most [DAY_RINGS] timers, [DAY_ROWS] events and [DAY_ROWS] to-dos, each group with its whole count. */
internal class Day(
    val timers: List<DayTimer>, val timerCount: Int,
    val events: List<DayEvent>, val eventCount: Int,
    val todo: List<String>, val todoCount: Int,
)

internal const val DAY_RINGS = 3
internal const val DAY_ROWS = 2

/** The rest of the day: the running timers soonest first, the events still to start, the newest to-dos. */
internal fun dayOf(events: List<Event>, reminders: List<Reminder>, timers: List<Timer>, now: OffsetDateTime, twentyFour: Boolean): Day {
    val nowMs = now.toInstant().toEpochMilli()
    val running = timers.filter { it.millisLeft(nowMs) > 0 }.sortedBy { it.millisLeft(nowMs) }
    val ahead = todayItems(events, emptyList(), now).filter { it.start != null }
    val todo = glance(emptyList(), reminders, emptyList(), now).todo
    return Day(
        running.take(DAY_RINGS).map { DayTimer(it.label ?: "timer", countdownText(it.millisLeft(nowMs)), it.fraction(nowMs)) }, running.size,
        ahead.take(DAY_ROWS).map { DayEvent(clock(it.start!!, twentyFour), it.title) }, ahead.size,
        todo.take(DAY_ROWS), todo.size,
    )
}

/** One group on the day: its feature, its head's baseline, and the band of the page it owns for a tap. */
internal class DayGroup(val feature: Feature, val head: Float, val top: Float, val bottom: Float)

// The groups from 176 down: a head's baseline, then the timers' rings (centres 76 under it,
// names 172 under it, the next head 216 under it), the events' rows (44 under the head and 42
// apart, the next head 42 a row and 50 more under it), the to-dos' rows (42 under the head, 42 apart).
private const val FIRST_HEAD = 176f
private const val RING_DROP = 76f
private const val RINGS_TO_HEAD = 216f
private const val EVENT_DROP = 44f
private const val ROW_STEP = 42f
private const val ROWS_TO_HEAD = 50f
private const val TODO_DROP = 42f
/** A group's band starts this far over its head's baseline, so a tap on the head counts. */
private const val BAND_OVER = 32f

/** The groups that have something, in order, placed. */
internal fun dayGroups(day: Day): List<DayGroup> {
    val out = ArrayList<DayGroup>(3)
    var y = FIRST_HEAD
    fun add(f: Feature, height: Float) { out += DayGroup(f, y, y - BAND_OVER, y + height - BAND_OVER); y += height }
    if (day.timers.isNotEmpty()) add(Feature.Timers, RINGS_TO_HEAD)
    if (day.events.isNotEmpty()) add(Feature.Calendar, ROW_STEP * day.events.size + ROWS_TO_HEAD)
    if (day.todo.isNotEmpty()) add(Feature.Reminders, TODO_DROP + ROW_STEP * day.todo.size)
    return out
}

// A group's head: its feature's dot (r 7 at x 31, 9 over the baseline, drawn by Home as it
// rises), the word in the hue from 48, the count in sub ending at 456.
internal const val HEAD_DOT_X = 31f
internal const val HEAD_DOT_RISE = 9f
private const val HEAD_WORD_X = 48f
// A timer's ring: r 60, 8 wide, the track faint and what is left in the hue from twelve o'clock,
// 152 apart and centred as a set; the time inside, its name 96 under the centre.
private const val RING_R = 60f
private const val RING_W = 8f
private const val RING_PITCH = 152f
private const val RING_TIME_DROP = 10f
private const val RING_NAME_DROP = 96f
// An event: its time in the calendar's hue at 24, the title from 104 (or 20 past a long time).
private const val EVENT_TITLE_X = 104f
// A to-do: its ring (r 11, 3 wide) at x 35, 11 over the baseline, the title at 60.
private const val TODO_RING_X = 35f
private const val TODO_RING_R = 11f
private const val TODO_TITLE_X = 60f

/** The word a group's head says. */
internal fun headWord(f: Feature): String = when (f) {
    Feature.Timers -> "timers"
    Feature.Calendar -> "today"
    else -> "to do"
}

/**
 * The day's groups, each at the alpha [shown] gives it (they come up after the croissant has moved), heads
 * without their dots. Nothing left today says so, in sub, where the first head would be.
 */
internal fun DrawScope.drawDay(m: TextMeasurer, day: Day, groups: List<DayGroup>, shown: (Int) -> Float, r: Ramp) {
    fun Color.a(a: Float) = copy(alpha = this.alpha * a)
    if (groups.isEmpty()) { text(m, 24f, FIRST_HEAD, "nothing left today", BeamType.Value, r.sub.a(shown(0))); return }
    groups.forEachIndexed { g, grp ->
        val a = shown(g)
        if (a <= 0f) return@forEachIndexed
        val hue = rampOf(grp.feature).hue
        val count = when (grp.feature) { Feature.Timers -> day.timerCount; Feature.Calendar -> day.eventCount; else -> day.todoCount }
        text(m, HEAD_WORD_X, grp.head, headWord(grp.feature), BeamType.Label, hue.a(a))
        text(m, 456f, grp.head, count.toString(), BeamType.Label, r.sub.a(a), Anchor.End)
        when (grp.feature) {
            Feature.Timers -> day.timers.forEachIndexed { i, t ->
                val cx = 240f + (i - (day.timers.size - 1) / 2f) * RING_PITCH
                val cy = grp.head + RING_DROP
                val box = Offset(cx - RING_R, cy - RING_R)
                drawArc(FAINT.a(a), 0f, 360f, false, box, Size(2 * RING_R, 2 * RING_R), style = Stroke(RING_W))
                drawArc(hue.a(a), -90f, 360f * t.fraction, false, box, Size(2 * RING_R, 2 * RING_R), style = Stroke(RING_W, cap = StrokeCap.Round))
                text(m, cx, cy + RING_TIME_DROP, t.left, BeamType.Value, r.ink.a(a), Anchor.Middle)
                text(m, cx, cy + RING_NAME_DROP, fit(m, t.name.lowercase(), BeamType.Label, RING_PITCH - 12f), BeamType.Label, r.sub.a(a), Anchor.Middle)
            }
            Feature.Calendar -> day.events.forEachIndexed { i, e ->
                val base = grp.head + EVENT_DROP + i * ROW_STEP
                val tw = text(m, 24f, base, e.time, BeamType.Value, hue.a(a))
                val x = maxOf(EVENT_TITLE_X, 24f + tw + 20f)
                text(m, x, base, fit(m, e.title.lowercase(), BeamType.Select, 456f - x), BeamType.Select, r.ink.a(a))
            }
            else -> day.todo.forEachIndexed { i, title ->
                val base = grp.head + TODO_DROP + i * ROW_STEP
                drawCircle(hue.a(a), TODO_RING_R, Offset(TODO_RING_X, base - TODO_RING_R), style = Stroke(3f))
                text(m, TODO_TITLE_X, base, fit(m, title.lowercase(), BeamType.Select, 456f - TODO_TITLE_X), BeamType.Select, r.ink.a(a))
            }
        }
    }
}
