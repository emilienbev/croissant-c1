package com.bleural.croissant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A room laid out again (RoomState.lay, 2026-10-17), as Beam's is: its recents above Ask, its
 * pinned apps before Settings, and Home that always leads to Ask.
 */
class RoomLayTest {
    private fun RoomState.settle() {
        var frames = 0
        while (!settled) {
            if (frames++ > 900) fail("the room never settled: pos $pos open $open home $home pull $pull")
            advance(1f / 60f)
        }
    }

    private val base = Feature.room.map { Place.Of(it) }
    private val recents = listOf(Place.Of(Feature.Calendar), Place.Of(Feature.Weather), Place.Of(Feature.Timers))

    /** Beam's room on Home, laid out with [top] above Ask, as at the start. */
    private fun beam(top: List<Place> = recents): RoomState =
        RoomState(leavesToEntry = true).apply { settle(); lay(top + base, top.size) }

    /** What RoomWheel does with one tick. */
    private fun RoomState.tick(delta: Int) {
        when {
            openTarget > 0.5f -> if (delta < 0) closePanel()
            homeTarget > 0.5f -> if (delta > 0) depart()
            delta < 0 && posTarget < 0.5f -> goHome()
            else -> step(delta)
        }
    }

    @Test fun onHomeTheRoomIsLaidOnAsk() {
        val r = beam()
        assertEquals(3, r.entry)
        assertEquals(3f, r.pos, 0f)
        assertEquals(Feature.Ask, r.last)
    }

    @Test fun downFromHomeIsAskThenUpThroughTheRecentsAndHome() {
        val r = beam()
        r.tick(1); r.settle()
        assertEquals(Feature.Ask, r.focused)
        r.tick(-1); r.settle(); assertEquals(Feature.Timers, r.focused)     // the newest, nearest Ask
        r.tick(-1); r.settle(); assertEquals(Feature.Weather, r.focused)
        r.tick(-1); r.settle(); assertEquals(Feature.Calendar, r.focused)
        r.tick(-1); r.settle()
        assertEquals(1f, r.home, 0f)                                         // one more is Home
    }

    @Test fun leavingHomeLandsOnAskWhateverWasLeft() {
        val r = beam()
        r.tick(1); r.settle()
        r.goTo(r.placeOf(Feature.Photos)); r.settle()
        r.goHome(); r.settle()
        r.lay(recents + base, 3)        // Beam lays it out on arriving
        r.tick(1); r.settle()
        assertEquals(Feature.Ask, r.focused)
    }

    @Test fun aTickBeforeHomeHasArrivedStillLandsOnAsk() {
        val r = beam()
        r.tick(1); r.settle()
        repeat(3) { r.tick(-1) }; r.settle()           // on the topmost recent
        r.tick(-1); repeat(3) { r.advance(1f / 60f) }  // Home on its way, not laid yet
        r.tick(1); r.settle()
        assertEquals(3f, r.pos, 0f)
    }

    @Test fun aRecentsCountChangedOnHomeKeepsAskInTheSlot() {
        val r = beam(recents.take(1))
        assertEquals(1f, r.pos, 0f)
        r.lay(recents + base, 3)
        assertEquals(3f, r.pos, 0f)
        assertEquals(3f, r.posTarget, 0f)
        r.tick(1); r.settle()
        assertEquals(Feature.Ask, r.focused)
    }

    @Test fun laidAgainAwayFromHomeThePlaceInHandStays() {
        val r = beam()
        r.tick(1); r.settle()
        r.goTo(r.placeOf(Feature.Metronome)); r.settle()
        val pinned = base.dropLast(1) + Place.App("water") + base.last()
        r.lay(recents + pinned, 3)                    // pinned while away from Home
        assertEquals(Feature.Metronome, r.focused)
        r.lay(pinned, 0)                              // and the recents gone: still there
        assertEquals(Feature.Metronome, r.focused)
        assertTrue(r.settled)
    }

    @Test fun placeOfIsTheRoomsOwnPlaceNotTheRecent() {
        val r = beam()
        assertEquals(3 + Feature.room.indexOf(Feature.Timers), r.placeOf(Feature.Timers))
        assertEquals(3, r.placeOf(Feature.Ask))
    }

    @Test fun theSideButtonAndTheLeftEdgeLandOnAsk() {
        val r = beam()
        r.tick(1); r.settle()
        r.goTo(0); r.settle()
        r.cutToAsk()
        assertEquals(Feature.Ask, r.last)
        assertEquals(1f, r.open, 0f)
        r.closePanel(); r.goTo(8); r.settle()
        r.dragHomeFrom(8f, 1f)
        assertEquals(3f, r.pos, 0f)                   // Home all the way: the room rode up to Ask
        r.settleHomeFrom(8f, toHome = true); r.settle()
        assertEquals(3f, r.pos, 0f)
    }

    @Test fun anAppsPlaceOpensWhatItSays() {
        val r = beam(listOf(Place.App("water")))
        var asked: Place? = null
        r.opens = { p -> if (p is Place.App) { asked = p; true } else false }
        r.tick(1); r.settle()
        r.tick(-1); r.settle()
        r.openPanel()
        assertEquals(Place.App("water"), asked)
        assertEquals(0f, r.openTarget, 0f)            // the room stays on its place under the page
        r.tick(1); r.settle()
        r.openPanel()
        assertEquals(1f, r.openTarget, 0f)            // Ask opens as ever
    }

    @Test fun everyOtherRoomStillLeavesHomeWhereItWasLeft() {
        val r = RoomState()
        r.leaveHome(); r.settle()
        r.goTo(5); r.settle()
        r.goHome(); r.settle()
        r.depart(); r.settle()
        assertEquals(5f, r.pos, 0f)
    }
}
