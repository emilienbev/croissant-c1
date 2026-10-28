package com.bleural.croissant.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ForecastsTest {
    private val now = 1_790_000_000_000L
    private val hour = 3600_000L
    private fun w(temp: Int) = Weather(temp, 3, true, emptyList(), emptyList(), now)
    private val here = Forecasts.here(lat = 51.5, lon = -0.1)
    private val lisbon = Place("38.725,-9.150", "Lisbon", "Portugal", 38.725, -9.15)
    private val paris = Place("48.853,2.349", "Paris", "France", 48.853, 2.349)

    private fun f(fixAt: Long, pinned: String?, vararg ws: Pair<Place, Weather>) =
        Forecasts(listOf(here, lisbon, paris), ws.associate { it.first.id to it.second }, pinned, fixAt)

    @Test fun hereWhileTheFixIsFresh() {
        val h = f(now - 5 * hour, lisbon.id, here to w(17), lisbon to w(24)).home(now)!!
        assertTrue(h.place.here)
        assertFalse(h.pinned)
        assertEquals(17, h.weather.temp)
    }

    @Test fun thePinnedPlaceOnceTheFixIsOld() {
        val h = f(now - 7 * hour, lisbon.id, here to w(17), lisbon to w(24)).home(now)!!
        assertEquals(lisbon, h.place)
        assertTrue(h.pinned)
        assertEquals(24, h.weather.temp)
    }

    @Test fun thePinnedPlaceBeforeAnyFix() {
        assertEquals(lisbon, f(0L, lisbon.id, lisbon to w(24)).home(now)!!.place)
    }

    @Test fun thePinnedPlaceWhenHereHasNoForecast() {
        assertEquals(lisbon, f(now - hour, lisbon.id, lisbon to w(24)).home(now)!!.place)
    }

    @Test fun hereAllTheSameWhenNothingIsPinned() {
        val h = f(now - 7 * hour, null, here to w(17), paris to w(19)).home(now)!!
        assertTrue(h.place.here)
        assertFalse(h.pinned)
    }

    @Test fun nothingWithoutAnyForecastOfHereOrAPin() {
        assertNull(f(0L, null, paris to w(19)).home(now))
    }

    @Test fun hereWhenThePinnedPlaceHasNoForecastYet() {
        assertTrue(f(now - 7 * hour, paris.id, here to w(17), lisbon to w(24)).home(now)!!.place.here)
    }

    @Test fun placesRoundTrip() {
        assertEquals(listOf(lisbon, paris), Place.fromJson(Place.toJson(listOf(lisbon, paris))))
        assertEquals(emptyList<Place>(), Place.fromJson("not json"))
    }

    @Test fun foundReadsTheGeocoder() {
        val json = """{"results":[{"name":"Lisbon","latitude":38.72509,"longitude":-9.1498,"country":"Portugal","admin1":"Lisbon District"},
            {"name":"Nowhere"}]}"""
        val found = Found.parse(json)
        assertEquals(1, found.size)
        assertEquals("Lisbon", found[0].name)
        assertEquals("Portugal", found[0].country)
        assertEquals("38.725,-9.150", found[0].id)
        assertEquals(emptyList<Found>(), Found.parse("""{"generationtime_ms":0.05}"""))
    }

    @Test fun weatherReadsTheWeek() {
        val json = """{"utc_offset_seconds":3600,
            "current":{"temperature_2m":21.4,"weather_code":3,"is_day":1},
            "hourly":{"time":["2026-09-29T11:00"],"temperature_2m":[20.2],"weather_code":[3],"precipitation_probability":[10]},
            "daily":{"time":["2026-09-29","2026-09-30"],"weather_code":[3,61],"temperature_2m_max":[25.4,null],"temperature_2m_min":[13.6,12.0],
                "sunrise":["2026-09-29T07:02","2026-09-30T07:04"],"sunset":["2026-09-29T18:50","2026-09-30T18:48"]}}"""
        val w = Weather.parse(json, 0L)!!
        assertEquals(21, w.temp)
        assertEquals(listOf(Day(LocalDate.parse("2026-09-29"), 3, 25, 14)), w.days)
        assertEquals(2, w.sun.size)
    }
}
