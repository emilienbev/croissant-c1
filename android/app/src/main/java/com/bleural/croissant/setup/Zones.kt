package com.bleural.croissant.setup

import java.time.Instant
import java.time.ZoneId

/** A time zone as the setup lists it: its id, the city it is named for, the offset right now ("gmt+1"). */
data class Zone(val id: String, val city: String, val offset: String)

object Zones {
    /** The region ids a person picks from: Europe/Paris, America/Argentina/Buenos_Aires; not Etc/GMT+3 nor the old aliases. */
    private val REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    fun of(id: String): Zone = Zone(id, cityOf(id), offsetOf(id))

    /** Every zone, by city, each once. */
    val all: List<Zone> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.substringBefore('/') in REGIONS && '/' in it }
            .map(::of)
            .distinctBy { it.city }
            .sortedBy { it.city }
    }

    /** The zones whose city starts with [typed] first, then those that have it anywhere. */
    fun search(typed: String): List<Zone> {
        val t = typed.trim()
        if (t.isEmpty()) return all
        val starts = all.filter { it.city.startsWith(t, ignoreCase = true) }
        return starts + all.filter { it !in starts && it.city.contains(t, ignoreCase = true) }
    }

    private fun cityOf(id: String) = id.substringAfterLast('/').replace('_', ' ')

    private fun offsetOf(id: String): String {
        val s = ZoneId.of(id).rules.getOffset(Instant.now()).totalSeconds
        if (s == 0) return "gmt"
        val h = kotlin.math.abs(s) / 3600
        val m = kotlin.math.abs(s) % 3600 / 60
        return "gmt" + (if (s < 0) "-" else "+") + h + if (m != 0) ":%02d".format(m) else ""
    }
}
