package com.bleural.croissant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import com.bleural.croissant.weather.Day
import com.bleural.croissant.weather.Forecasts
import com.bleural.croissant.weather.Hour
import com.bleural.croissant.weather.Place
import com.bleural.croissant.weather.Sky
import com.bleural.croissant.weather.Weather
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The weather in words and numbers, for any design: Home's line under
 * the clock, the Weather page's now, today and week, the places. Which words and which hours,
 * never how they look.
 */

/**
 * Which place the Weather page shows, by id: one page's state shared with the Places and Add
 * pages over it, which pick a place and go back to it. Not kept: the page opens on here after
 * a restart, and on here again when the place it was on is removed.
 */
object WeatherView {
    var shown by mutableStateOf(Place.HERE)
}

/** A place's name as a design writes it: the search's, the locality for here, or "here". */
fun placeName(p: Place): String = p.name.ifBlank { "here" }

/** The glyph for the sky (ui/Icons.kt): rain and storms the rain cloud, snow the snow cloud, cloud and fog the cloud, else the sun by day and the moon by night. */
fun skyIcon(sky: Sky?, day: Boolean): ImageVector = when (sky) {
    Sky.Rain, Sky.Storm -> Glyphs.Rain
    Sky.Snow -> Glyphs.Snow
    Sky.Cloud, Sky.Fog -> Glyphs.Cloud
    else -> if (day) Glyphs.Sun else Glyphs.Night
}

/** A forecast fetched this recently still says what it is like now; older, now is the forecast for this hour. */
private const val NOW_MS = 90 * 60_000L

/**
 * The next twelve hours from the hour [now] is in, thirteen with it: the first is now, at the
 * reading's temperature while it is recent. Fewer when the forecast runs out, none when it
 * has nothing from this hour on.
 */
fun todayHours(w: Weather, now: LocalDateTime, nowMs: Long): List<Hour> {
    val from = now.withMinute(0).withSecond(0).withNano(0)
    val hours = w.hours.filter { !it.at.isBefore(from) }.take(13)
    if (hours.isEmpty() || nowMs - w.fetchedAt >= NOW_MS) return hours
    return listOf(hours[0].copy(temp = w.temp, code = w.code)) + hours.drop(1)
}

/** The temperature now: the reading while it is recent, else this hour's forecast, else the reading. */
fun nowTemp(w: Weather, now: LocalDateTime, nowMs: Long): Int = todayHours(w, now, nowMs).firstOrNull()?.temp ?: w.temp

/** The week from [today] on, seven days at most (the place's own dates). */
fun weekDays(w: Weather, today: LocalDate): List<Day> = w.days.filter { !it.date.isBefore(today) }.take(7)

/** A day of the week as a row says it: "today", else its short name ("wed"). */
fun dayWord(d: LocalDate, today: LocalDate): String =
    if (d == today) "today" else d.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)).lowercase()

/** A line through [pts], smoothed (Catmull-Rom as cubic Béziers), for any design's chart of the hours. */
fun smoothLine(pts: List<Offset>): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    for (j in 1 until pts.size) {
        val a = pts[maxOf(0, j - 2)]; val b = pts[j - 1]; val c = pts[j]; val e = pts[minOf(pts.size - 1, j + 1)]
        cubicTo(b.x + (c.x - a.x) / 6f, b.y + (c.y - a.y) / 6f, c.x - (e.x - b.x) / 6f, c.y - (e.y - b.y) / 6f, c.x, c.y)
    }
}

/** An hour under a chart: "14" on a 24-hour clock, "2 pm" on a 12-hour one. */
fun hourLabel(t: LocalDateTime, twentyFour: Boolean): String =
    if (twentyFour) t.hour.toString().padStart(2, '0')
    else t.format(DateTimeFormatter.ofPattern("h a", Locale.UK)).lowercase()

/** A forecast older than this says nothing: yesterday's temperature is not the weather. */
private const val KEEP_MS = 6 * 3600_000L

/** [w] while it is still worth showing at [nowMs], else null. */
fun fresh(w: Weather?, nowMs: Long): Weather? = w?.takeIf { nowMs - it.fetchedAt < KEEP_MS }

/** A LocalTime as hours of the day, fractional: 19:30 is 19.5. */
fun LocalTime.hours(): Float = hour + minute / 60f + second / 3600f

/** Whether it is daylight at [now]: by the sun when there is a forecast, else 6 to 18. */
fun daylight(w: Weather?, now: LocalDateTime): Boolean {
    val sun = w?.sunOn(now.toLocalDate()) ?: return now.hour in 6 until 18
    val t = now.toLocalTime()
    return !t.isBefore(sun.rise) && t.isBefore(sun.set)
}

/** The sky now in a word or two: "sunny", "clear", "partly cloudy", "cloudy", "rain". */
fun skyWord(w: Weather, day: Boolean): String = when (w.sky) {
    Sky.Clear -> if (day) "sunny" else "clear"
    Sky.Cloud -> if (w.code == 2) "partly cloudy" else "cloudy"
    Sky.Fog -> "foggy"
    Sky.Rain -> if (w.code in 51..57) "drizzle" else "rain"
    Sky.Snow -> "snow"
    Sky.Storm -> "storms"
}

private fun wet(h: Hour) = h.sky == Sky.Rain || h.sky == Sky.Storm || h.rainChance >= 50

/** An hour as a time: "16:00", or "4 pm" on a 12-hour clock. */
private fun hourText(t: LocalDateTime, twentyFour: Boolean): String =
    if (twentyFour) t.format(DateTimeFormatter.ofPattern("H:mm", Locale.UK))
    else t.format(DateTimeFormatter.ofPattern("h a", Locale.UK)).lowercase()

/**
 * What the weather does next, in a few words, or null when there is nothing to say. The
 * first hour of rain in the next twelve ("rain from 16:00"; while it rains, the first dry
 * one: "dry from 16:00"); else after sunset the temperature at midnight ("10° by midnight");
 * else the day's high still to come ("19° later"); else the sky now ("sunny").
 */
fun weatherNext(w: Weather, now: LocalDateTime, twentyFour: Boolean): String? {
    val ahead = w.hours.filter { it.at.isAfter(now) && !it.at.isAfter(now.plusHours(12)) }
    if (w.sky == Sky.Rain || w.sky == Sky.Storm) return ahead.firstOrNull { !wet(it) }?.let { "dry from ${hourText(it.at, twentyFour)}" }
    ahead.firstOrNull(::wet)?.let { return "rain from ${hourText(it.at, twentyFour)}" }
    val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
    val sun = w.sunOn(now.toLocalDate())
    if (sun != null && !now.toLocalTime().isBefore(sun.set)) {
        return w.hours.firstOrNull { it.at == midnight }?.let { "${it.temp}° by midnight" }
    }
    val high = w.hours.filter { it.at.isAfter(now) && it.at.isBefore(midnight) }.maxOfOrNull { it.temp }
    if (high != null && high > w.temp) return "$high° later"
    return skyWord(w, daylight(w, now))
}

/** The place [f] shows for [WeatherView.shown], or here when that place has gone. */
fun shownPlace(f: Forecasts): Place = f.places.firstOrNull { it.id == WeatherView.shown } ?: f.places.first()
