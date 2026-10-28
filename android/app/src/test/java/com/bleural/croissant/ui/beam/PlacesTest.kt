package com.bleural.croissant.ui.beam

import com.bleural.croissant.WheelKeep
import com.bleural.croissant.atelier.AppManifest
import com.bleural.croissant.atelier.AppSource
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Place
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Beam's room as laid out (Places.kt, 2026-10-17): the recents above Ask, the pins before Settings. */
class PlacesTest {
    private fun app(id: String, on: Boolean = true) =
        InstalledApp(AppManifest(id, 0x1FA2FF, null, "1", 1, emptySet()), File(id), AppSource.Local, 0, enabled = on)

    private val apps = listOf(app("water"), app("bill"), app("off", on = false))

    @Test fun theNewestRecentIsNearestAsk() {
        val top = beamRecents(listOf("Weather", "Timers", "Calendar", "Photos"), apps)
        assertEquals(listOf(Feature.Calendar, Feature.Timers, Feature.Weather).map { Place.Of(it) }, top)
        val room = beamRoom(top, emptyList(), apps)
        assertEquals(Place.Of(Feature.Ask), room[3])
        assertEquals(Place.Of(Feature.Weather), room[2])   // one tick up from Ask
    }

    @Test fun askNeverCountsNorWhatBeamHasNot() {
        val top = beamRecents(listOf("Ask", WheelKeep.app("off"), WheelKeep.app("gone"), "Nothing", WheelKeep.app("water"), "Settings"), apps)
        assertEquals(listOf(Place.Of(Feature.Settings), Place.App("water")), top)
    }

    @Test fun noRecentsLeaveAskFirst() {
        val room = beamRoom(beamRecents(emptyList(), apps), emptyList(), apps)
        assertEquals(PLACES.map { Place.Of(it) }, room)
    }

    @Test fun pinsGoAfterAtelierBeforeSettingsInPinOrder() {
        val room = beamRoom(emptyList(), listOf("bill", "off", "gone", "water"), apps)
        val atelier = room.indexOf(Place.Of(Feature.Atelier))
        assertEquals(listOf(Place.App("bill"), Place.App("water"), Place.Of(Feature.Settings)), room.drop(atelier + 1))
        assertEquals(PLACES.size + 2, room.size)
    }

    @Test fun pinnedAgentsGoRightAfterAgentsInPinOrder() {
        val room = beamRoom(emptyList(), listOf("bill"), apps, agents = listOf("codex", "claude", "codex"))
        val agents = room.indexOf(Place.Of(Feature.Agents))
        assertEquals(listOf(Place.Agent("codex"), Place.Agent("claude"), Place.Of(Feature.Atelier)), room.subList(agents + 1, agents + 4))
        assertEquals(PLACES.size + 3, room.size)
    }

    @Test fun anAgentsKeyNamesItsPlace() {
        assertEquals(Place.Agent("claude"), Place.of(Place.Agent("claude").key))
        assertEquals("agent:claude", Place.Agent("claude").key)
        assertEquals(listOf(Place.Agent("codex")), beamRecents(listOf("agent:codex", "Ask"), apps))
    }

    @Test fun aRecentWhoseAppWentDropsOutOfTheLaidRoom() {
        val laid = listOf(Place.App("water"), Place.Of(Feature.Timers))
        val room = beamRoom(laid, emptyList(), apps.filter { it.id != "water" })
        assertEquals(1, room.indexOf(Place.Of(Feature.Ask)))
    }
}
