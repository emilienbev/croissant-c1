package com.bleural.croissant.ui.beam

import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Place

/**
 * Beam's room, in order: every feature, with Agents and Atelier just before Settings. Only Beam has
 * Atelier and Agents; the other designs' rooms are `Feature.room`. A place is an index in the
 * room's list ([beamRoom]), which is what the room's `pos` counts, the side wheel's discs and
 * Home's dots; a feature's ordinal is not its place.
 */
internal val PLACES: List<Feature> = Feature.room.filter { it != Feature.Settings } + Feature.Agents + Feature.Atelier + Feature.Settings

/** How many of the places last opened the wheel shows above Ask (2026-10-17). */
internal const val RECENTS = 3

/**
 * The recents above Ask (2026-10-17), from Core's keys, newest first: up to [RECENTS], in rim
 * order, so the newest is the one nearest Ask. Never Ask itself, which is right under them, nor
 * a place Beam has not (an app gone or off).
 */
internal fun beamRecents(recents: List<String>, apps: List<InstalledApp>): List<Place> =
    recents.mapNotNull(Place::of).filter { here(it, apps) }.distinct().take(RECENTS).reversed()

/**
 * Beam's room as laid out (2026-10-17): [top], the recents, above Ask, then [PLACES] with the
 * [agents] pinned to the wheel after Agents and the apps after Atelier, each in pin order. A
 * recent whose app has gone or is off drops out; a pinned app too, until it is on again. Ask is
 * at the size of what is left of [top].
 */
internal fun beamRoom(top: List<Place>, pins: List<String>, apps: List<InstalledApp>, agents: List<String> = emptyList()): List<Place> {
    val pinned = pins.distinct().map { Place.App(it) }.filter { here(it, apps) }
    val agentsPinned = agents.distinct().map { Place.Agent(it) }
    return top.filter { here(it, apps) } + PLACES.flatMap { f ->
        when (f) {
            Feature.Settings -> pinned + Place.Of(f)
            Feature.Agents -> listOf(Place.Of(f)) + agentsPinned
            else -> listOf(Place.Of(f))
        }
    }
}

private fun here(p: Place, apps: List<InstalledApp>): Boolean = when (p) {
    is Place.App -> apps.any { it.id == p.id && it.enabled }
    is Place.Agent -> true
    is Place.Of -> p.feature in PLACES && p.feature != Feature.Ask
}

/** A place's ramp: a feature's, an app's its own hue (Atelier's while it is not found). Main thread only, as [appRamp]. */
internal fun rampOf(p: Place, apps: List<InstalledApp>): Ramp = when (p) {
    is Place.Of -> rampOf(p.feature)
    is Place.App -> apps.firstOrNull { it.id == p.id }?.let(::appRamp) ?: ATELIER
    is Place.Agent -> agentLook(p.id).ramp
}
