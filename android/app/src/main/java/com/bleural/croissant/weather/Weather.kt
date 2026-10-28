package com.bleural.croissant.weather

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The sky as a coarse kind, from a WMO code (the words the server uses for them are in
 * server/croissant_server/sources.py). Enough to pick a glyph and a word; a design says how.
 */
enum class Sky { Clear, Cloud, Fog, Rain, Snow, Storm }

fun skyOf(code: Int): Sky = when (code) {
    0, 1 -> Sky.Clear
    2, 3 -> Sky.Cloud
    45, 48 -> Sky.Fog
    in 51..67, in 80..82 -> Sky.Rain
    in 71..77, 85, 86 -> Sky.Snow
    in 95..99 -> Sky.Storm
    else -> Sky.Cloud
}

/** One hour of the forecast, from [at] (the device's local time). */
data class Hour(val at: LocalDateTime, val temp: Int, val code: Int, val rainChance: Int) {
    val sky: Sky get() = skyOf(code)
}

/** A day's sunrise and sunset, local. */
data class SunDay(val date: LocalDate, val rise: LocalTime, val set: LocalTime)

/** One day of the week ahead, by the place's own date: its sky and its range in whole degrees C. */
data class Day(val date: LocalDate, val code: Int, val max: Int, val min: Int) {
    val sky: Sky get() = skyOf(code)
}

/**
 * The weather at one place: the reading now (temperature rounded to whole degrees C, the WMO
 * code, day or night), the sun and the sky day by day for the week, and the forecast by the
 * hour from the hour it was fetched in (the next 48). [fetchedAt] is wall-clock ms.
 */
data class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val sun: List<SunDay>,
    val hours: List<Hour>,
    val fetchedAt: Long,
    val days: List<Day> = emptyList(),
) {
    val sky: Sky get() = skyOf(code)

    fun sunOn(date: LocalDate): SunDay? = sun.firstOrNull { it.date == date }

    companion object {
        /**
         * An Open-Meteo answer (`current`, `hourly`, `daily`, `timezone=auto`), or null when it
         * is not one. Its times are the place's own; they are turned into the device's zone,
         * which is the same one unless the device has travelled since the fix.
         */
        fun parse(json: String, fetchedAt: Long): Weather? = runCatching { JSONObject(json) }.getOrNull()?.let { parse(it, fetchedAt) }

        /** One place's forecast out of an answer (a multi-place answer is an array of these). */
        fun parse(o: JSONObject, fetchedAt: Long): Weather? = runCatching {
            val offset = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
            val zone = ZoneId.systemDefault()
            fun local(s: String) = LocalDateTime.parse(s).atOffset(offset).atZoneSameInstant(zone).toLocalDateTime()
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val rises = daily.getJSONArray("sunrise")
            val sets = daily.getJSONArray("sunset")
            val sun = (0 until rises.length()).map { i ->
                val r = local(rises.getString(i))
                SunDay(r.toLocalDate(), r.toLocalTime(), local(sets.getString(i)).toLocalTime())
            }
            // The week by the place's own dates; a day the model has no value for is left out.
            val dates = daily.getJSONArray("time")
            val dayCodes = daily.optJSONArray("weather_code")
            val maxes = daily.optJSONArray("temperature_2m_max")
            val mins = daily.optJSONArray("temperature_2m_min")
            val days = if (dayCodes == null || maxes == null || mins == null) emptyList() else (0 until dates.length()).mapNotNull { i ->
                if (dayCodes.isNull(i) || maxes.isNull(i) || mins.isNull(i)) return@mapNotNull null
                Day(LocalDate.parse(dates.getString(i)), dayCodes.getInt(i), Math.round(maxes.getDouble(i)).toInt(), Math.round(mins.getDouble(i)).toInt())
            }
            val hourly = o.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val chances = hourly.getJSONArray("precipitation_probability")
            val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fetchedAt), zone).withMinute(0).withSecond(0).withNano(0)
            // An hour the model has no value for comes as null: it is left out.
            val hours = (0 until times.length()).mapNotNull { i ->
                if (temps.isNull(i) || codes.isNull(i)) return@mapNotNull null
                Hour(local(times.getString(i)), Math.round(temps.getDouble(i)).toInt(), codes.getInt(i), chances.optInt(i, 0))
            }.filter { !it.at.isBefore(from) }
            Weather(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                sun = sun,
                hours = hours,
                fetchedAt = fetchedAt,
                days = days,
            )
        }.getOrNull()
    }
}
