package com.bleural.croissant.weather

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A place the weather is for: [HERE], the device's own from its last
 * location fix, or one the user added from a search. [name] is as the search gave it
 * ("Lisbon"); for here it is the locality when the device can name it, else blank, and a
 * design says "here". [lat] and [lon] of here are the last fix's, NaN before there is one.
 */
data class Place(val id: String, val name: String, val country: String, val lat: Double, val lon: Double) {
    val here: Boolean get() = id == HERE

    companion object {
        const val HERE = "here"

        /** An added place's id: its coordinates to about 100 m, so the same place is not added twice. */
        fun idOf(lat: Double, lon: Double): String = "%.3f,%.3f".format(Locale.ROOT, lat, lon)

        fun toJson(ps: List<Place>): String = JSONArray().apply {
            ps.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("country", p.country).put("lat", p.lat).put("lon", p.lon)) }
        }.toString()

        /** The added places kept in prefs; anything unreadable is left out. */
        fun fromJson(s: String?): List<Place> = runCatching {
            val a = JSONArray(s ?: return emptyList())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Place(o.getString("id"), o.optString("name"), o.optString("country"), o.getDouble("lat"), o.getDouble("lon"))
                    .takeIf { !it.here }
            }
        }.getOrDefault(emptyList())
    }
}

/** A place a search found, not added yet: its name, country and region (admin1) as Open-Meteo's geocoder gives them. */
data class Found(val name: String, val country: String, val region: String, val lat: Double, val lon: Double) {
    val id: String get() = Place.idOf(lat, lon)

    fun toPlace() = Place(id, name, country, lat, lon)

    companion object {
        /** The geocoder's answer (`results`, absent when nothing matches). */
        fun parse(json: String): List<Found> = runCatching {
            val a = JSONObject(json).optJSONArray("results") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("latitude") || !o.has("longitude")) return@mapNotNull null
                Found(o.optString("name"), o.optString("country"), o.optString("admin1"), o.getDouble("latitude"), o.getDouble("longitude"))
            }
        }.getOrDefault(emptyList())
    }
}

/** What Home shows: which place, its forecast, and whether it is there because it is pinned (no recent fix). */
data class HomeWeather(val place: Place, val weather: Weather, val pinned: Boolean)

/**
 * Every place and its forecast: here first, always, then the added ones in the order they
 * were added; at most one of those [pinned]. [fixAt] is when the last location fix was
 * taken (wall-clock ms), 0 before the first.
 */
data class Forecasts(
    val places: List<Place>,
    val weather: Map<String, Weather>,
    val pinned: String?,
    val fixAt: Long,
) {
    fun of(p: Place): Weather? = weather[p.id]

    /**
     * Home's weather at [nowMs]: here's while the fix it is for is under [FIX_KEEP_MS] old;
     * otherwise the pinned place's, the fallback for a device with no GPS
     * (2026-09-29); with nothing pinned (or its forecast not in yet), here's all the same, the
     * last place the device knew being a better guess than no weather; otherwise none. How old
     * the forecast itself is, is the design's to judge.
     */
    fun home(nowMs: Long): HomeWeather? {
        val here = places.first()
        val w = weather[here.id]
        if (w != null && fixAt > 0 && nowMs - fixAt < FIX_KEEP_MS) return HomeWeather(here, w, pinned = false)
        val p = places.firstOrNull { it.id == pinned }
        p?.let { weather[it.id] }?.let { return HomeWeather(p, it, pinned = true) }
        return w?.let { HomeWeather(here, it, pinned = false) }
    }

    companion object {
        /** A fix older than this no longer says where the device is, for Home. */
        const val FIX_KEEP_MS = 6 * 3600_000L

        /** Here, before its first fix: nowhere yet. */
        fun here(name: String = "", lat: Double = Double.NaN, lon: Double = Double.NaN) = Place(Place.HERE, name, "", lat, lon)
    }
}
