package com.bleural.croissant.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings > usage: every job as the C1 and the server counted it, added up, the days to the total. */
class UsageTest {
    private val serverChat = UsageSlot(0.5, false, 1000, 0, 0)
    private val speech = UsageSlot(0.25, false, 0, 3600, 0)
    private val chat = UsageSlot(0.125, false, 2000, 0, 0)

    @Test fun theServersChatFromBeforeTheSwitchIsAddedToTheC1s() {
        val theirs = Usage(30, 0.75, listOf(0.5, 0.25), mapOf(ModelSlot.Chat to serverChat, ModelSlot.Stt to speech))
        val mine = Usage(30, 0.125, listOf(0.0, 0.0, 0.125), mapOf(ModelSlot.Chat to chat))
        val u = Usage.joined(theirs, mine)
        assertEquals(mapOf(ModelSlot.Stt to speech, ModelSlot.Chat to UsageSlot(0.625, false, 3000, 0, 0)), u.slots)
        assertEquals(0.875, u.total, 1e-9)
        // Today with today: both lists end on it.
        assertEquals(listOf(0.0, 0.5, 0.375), u.daily)
        assertEquals(u.total, u.daily.sum(), 1e-9)
    }

    @Test fun aJobRunOnBothIsAddedUp() {
        val theirs = Usage(30, 0.25, listOf(0.25), mapOf(ModelSlot.Stt to speech))
        val cloud = UsageSlot(0.5, false, 120, 60, 0, priced = false)
        val mine = Usage(30, 0.5, listOf(0.0, 0.5), mapOf(ModelSlot.Stt to cloud))
        val u = Usage.joined(theirs, mine)
        assertEquals(UsageSlot(0.75, false, 120, 3660, 0, priced = false), u.slots[ModelSlot.Stt])
        assertEquals(0.75, u.total, 1e-9)
        assertEquals(listOf(0.0, 0.75), u.daily)
    }

    @Test fun withNoServerOnlyTheC1sCount() {
        val u = Usage.joined(null, Usage(30, 0.0, List(30) { 0.0 }, emptyMap()))
        assertNull(u.slots[ModelSlot.Chat])
        assertEquals(30, u.daily.size)
        assertEquals(0.0, u.total, 0.0)
        val alone = Usage.joined(null, Usage(30, 0.125, listOf(0.0, 0.125), mapOf(ModelSlot.Chat to chat)))
        assertEquals(listOf(0.0, 0.125), alone.daily)
        assertEquals(chat, alone.slots[ModelSlot.Chat])
    }
}
