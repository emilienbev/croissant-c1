package com.bleural.croissant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the side wheel keeps (WheelKeep.kt): the recents' order, the pins', an app deleted. */
class WheelKeepTest {
    @Test fun anOpenedPlaceGoesFirstOnce() {
        var r = emptyList<String>()
        for (k in listOf("Timers", "Weather", "Calendar", "Timers")) r = WheelKeep.opened(r, k)
        assertEquals(listOf("Timers", "Calendar", "Weather"), r)
    }

    @Test fun theOldestGoPastWhatIsKept() {
        var r = emptyList<String>()
        repeat(WheelKeep.KEEP + 3) { r = WheelKeep.opened(r, "k$it") }
        assertEquals(WheelKeep.KEEP, r.size)
        assertEquals("k${WheelKeep.KEEP + 2}", r.first())
    }

    @Test fun pinsKeepTheOrderPinned() {
        var p = emptyList<String>()
        p = WheelKeep.pinned(p, "water", true)
        p = WheelKeep.pinned(p, "bill", true)
        p = WheelKeep.pinned(p, "water", true)   // pinned again, it keeps its place
        assertEquals(listOf("water", "bill"), p)
        assertEquals(listOf("bill"), WheelKeep.pinned(p, "water", false))
    }

    @Test fun aDeletedAppLeavesBothLists() {
        val (r, p) = WheelKeep.dropApp(listOf(WheelKeep.app("bill"), "Timers", WheelKeep.app("water")), listOf("bill", "water"), "bill")
        assertEquals(listOf("Timers", WheelKeep.app("water")), r)
        assertEquals(listOf("water"), p)
    }

    @Test fun anAppsKeyIsKnownAsOne() {
        assertEquals("bill", WheelKeep.appOf(WheelKeep.app("bill")))
        assertNull(WheelKeep.appOf("Timers"))
        assertNull(WheelKeep.appOf("app:"))
    }

    @Test fun theListsGoThroughPrefsAsTheyAre() {
        val keys = listOf("Timers", WheelKeep.app("water-2"), "Settings")
        assertEquals(keys, WheelKeep.decode(WheelKeep.encode(keys)))
        assertEquals(emptyList<String>(), WheelKeep.decode(null))
        assertEquals(emptyList<String>(), WheelKeep.decode(""))
    }
}
