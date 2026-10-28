package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class TimerPlanTest {
    private val base = OffsetDateTime.parse("2026-09-14T18:00:00+01:00")
    private fun t(id: String, secs: Long) = Timer(id, null, Dates.stamp(base.plusSeconds(secs)), "orange")
    private fun ms(secs: Long) = base.plusSeconds(secs).toInstant().toEpochMilli()

    @Test fun nothingIsNothing() {
        val p = TimerPlan.at(emptyList(), ms(0))
        assertEquals(emptyList<Timer>(), p.due); assertNull(p.nextAt)
    }

    @Test fun dueAndNext() {
        val timers = listOf(t("a", 300), t("b", 60), t("c", -5))
        val p = TimerPlan.at(timers, ms(0))
        assertEquals(listOf("c"), p.due.map { it.id })
        assertEquals(ms(60), p.nextAt)
        val q = TimerPlan.at(timers, ms(60))          // b lands exactly now
        assertEquals(listOf("b", "c"), q.due.map { it.id })
        assertEquals(ms(300), q.nextAt)
    }

    @Test fun aHairEarlyCountsAsNow() {
        val timers = listOf(t("a", 10))
        assertEquals(listOf("a"), TimerPlan.at(timers, ms(10) - 200).due.map { it.id })
        assertEquals(emptyList<Timer>(), TimerPlan.at(timers, ms(10) - 400).due)
        assertEquals(ms(10), TimerPlan.at(timers, ms(10) - 400).nextAt)
    }

    @Test fun aBrokenEndIsIgnored() {
        val p = TimerPlan.at(listOf(Timer("x", null, "soon", "red"), t("a", 5)), ms(0))
        assertEquals(emptyList<Timer>(), p.due); assertEquals(ms(5), p.nextAt)
    }
}
