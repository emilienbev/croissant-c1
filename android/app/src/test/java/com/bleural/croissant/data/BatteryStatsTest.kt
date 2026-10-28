package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BatteryStatsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val h = 3_600_000L
    /** Tue 15 Sept 2026, 13:09 Paris. */
    private val now = ZonedDateTime.of(2026, 9, 15, 13, 9, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()

    private fun s(hoursAgo: Double, pct: Int, plugged: Boolean = false, on: Boolean = false) =
        BatterySample((nowMs - hoursAgo * h).toLong(), pct, plugged, on)

    /** Unplugged from 82 at 06:09 dropping 3 per hour, the hour before that on the charger at 100. */
    private fun day(): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        for (i in 8 downTo 7) out += s(i.toDouble(), 100, plugged = true)
        for (i in 6 downTo 0) out += s(i.toDouble(), 82 - (6 - i) * 3, on = i % 2 == 0)
        return out
    }

    @Test fun runsSplitOnChargerRiseAndGap() {
        val runs = BatteryStats.runs(day())
        assertEquals(1, runs.size)
        assertEquals(82, runs[0].from); assertEquals(64, runs[0].to); assertEquals(18, runs[0].drop)
        assertEquals(6 * h, runs[0].ms)
        assertTrue(runs[0].qualifies)
        val rise = day() + s(-1.0, 90) + s(-2.0, 85)   // charged while off: a rise without a plugged sample
        assertEquals(2, BatteryStats.runs(rise).size)
        assertEquals(1, BatteryStats.runs(day() + s(-1.0, 90)).size)   // one sample after the rise is no run yet
        val gap = listOf(s(10.0, 90), s(9.0, 88), s(5.0, 80), s(2.5, 76), s(0.0, 70))   // four hours of silence: the device was off
        val g = BatteryStats.runs(gap)
        assertEquals(2, g.size); assertEquals(88, g[0].to); assertEquals(80, g[1].from)
    }

    @Test fun lifeIsHundredOverTheRate() {
        // 18 percent in 6 hours: 3 per hour, so 33 h 20 for a full charge.
        assertEquals(2000L, BatteryStats.lifeMinutes(day()))
        assertNull(BatteryStats.lifeMinutes(listOf(s(1.0, 80), s(0.5, 78))))
        assertNull(BatteryStats.lifeMinutes(day(), since = nowMs + 1))
    }

    @Test fun hourlyBarsTakeTheLastSampleOfEachHour() {
        val (bars, labels) = BatteryStats.hourly(day(), now)
        assertEquals(24, bars.size)
        assertEquals(64, bars[23].value); assertFalse(bars[23].plugged)
        assertEquals(100, bars[16].value); assertTrue(bars[16].plugged)
        assertNull(bars[0].value)
        // 24 bars from 14:00 yesterday: 18 at index 4, 0 at 10, 6 at 16, 12 at 22.
        assertEquals(listOf(4 to "18", 10 to "0", 16 to "6", 22 to "12"), labels)
    }

    @Test fun weekBarsNameMidnight() {
        val (bars, labels) = BatteryStats.hourly(day(), now, 168)
        assertEquals(168, bars.size)
        // 168 bars from 14:00 Tue 8 Sept: midnight is the weekday, the other quarters the hour.
        assertEquals(4 to "18", labels[0]); assertEquals(10 to "Wed", labels[1]); assertEquals(16 to "6", labels[2])
        assertEquals(listOf("Wed", "Thu", "Fri", "Sat", "Sun", "Mon", "Tue"), labels.map { it.second }.filter { it.first().isLetter() })
    }

    @Test fun ratesReachBackUntilTheyQualify() {
        val since = nowMs - 24 * h
        // In the window, 2 off the screen in an hour: short of 5. Two days back 2 more in two hours, still short; three days back 2 more.
        val samples = listOf(s(72.0, 90), s(71.0, 88), s(50.0, 80), s(49.0, 79), s(48.0, 78), s(1.0, 60), s(0.0, 58))
        val r = BatteryStats.reach(samples, since)
        assertEquals(BatteryStats.Rate(6, 4 * h), r.screenOff.rate)
        assertEquals(nowMs - 72 * h, r.screenOff.from)
        assertEquals("1.5 %/h · 3 d", BatteryStats.fmtReached(r.screenOff, since, nowMs))
        // Enough in the window: it stays there, no span.
        val inWindow = BatteryStats.reach(day(), since)
        assertEquals(BatteryStats.Rate(9, 3 * h), inWindow.screenOff.rate)
        assertEquals(since, inWindow.screenOff.from)
        assertEquals("3.0 %/h", BatteryStats.fmtReached(inWindow.screenOff, since, nowMs))
        // The whole log short of it: no value.
        assertNull(BatteryStats.fmtReached(BatteryStats.reach(listOf(s(1.0, 60), s(0.0, 58)), since).screenOff, since, nowMs))
        // The raw log short of it, a folded month makes up the rest.
        val month = BatteryStats.Month(
            java.time.YearMonth.of(2026, 5),
            BatteryStats.Totals(100, nowMs - 120 * 24 * h, nowMs - 100 * 24 * h, screenOff = BatteryStats.Rate(20, 10 * h)),
        )
        val folded = BatteryStats.reach(listOf(s(1.0, 60), s(0.0, 58)), since, months = listOf(month))
        assertEquals(BatteryStats.Rate(22, 11 * h), folded.screenOff.rate)
        assertEquals("2.0 %/h · 120 d", BatteryStats.fmtReached(folded.screenOff, since, nowMs))
        // Days before months: two days back is enough, the month is not reached.
        fun day(daysAgo: Long, off: BatteryStats.Rate) = BatteryStats.Day(
            now.toLocalDate().minusDays(daysAgo), BatteryStats.Totals(10, nowMs - daysAgo * 24 * h, nowMs - daysAgo * 24 * h + 20 * h, screenOff = off),
        )
        val days = listOf(day(9, BatteryStats.Rate(2, 2 * h)), day(8, BatteryStats.Rate(2, 2 * h)))
        val both = BatteryStats.reach(listOf(s(1.0, 60), s(0.0, 58)), since, days, listOf(month))
        assertEquals(BatteryStats.Rate(6, 5 * h), both.screenOff.rate)
        assertEquals(nowMs - 9 * 24 * h, both.screenOff.from)
        // A day whose last sample is in the window counts in it, with no span.
        val recent = BatteryStats.reach(listOf(s(1.0, 60), s(0.0, 58)), since, listOf(day(0, BatteryStats.Rate(4, 2 * h))))
        assertEquals(BatteryStats.Rate(6, 3 * h), recent.screenOff.rate); assertEquals(since, recent.screenOff.from)
    }

    @Test fun spans() {
        assertEquals("25 h", BatteryStats.fmtSpan(24 * h + 1))
        assertEquals("47 h", BatteryStats.fmtSpan(47 * h))
        assertEquals("2 d", BatteryStats.fmtSpan(48 * h))
        assertEquals("4 d", BatteryStats.fmtSpan(72 * h + 1))
    }

    @Test fun dailyBarsArePercentUsedOnBattery() {
        val samples = listOf(s(30.0, 90), s(26.0, 70, plugged = true), s(25.0, 100), s(23.0, 80)) + day()
        val (month, mlabels) = BatteryStats.daily(samples, now, 30)
        assertEquals(30, month.size)
        assertEquals(18, month[29].value)            // today: 82 to 64 since 06:09
        assertEquals(20, month[28].value)            // yesterday: 100 to 80 in the afternoon; the 90 to 70 fell on a charger sample
        assertNull(month[0].value)
        assertEquals(listOf(0, 7, 14, 21, 28), mlabels.map { it.first })
        assertEquals("17", mlabels[0].second)         // 17 August
    }

    @Test fun chartMeanAndLife() {
        val c = BatteryStats.chart(day(), BatteryStats.Window.Day, nowMs, zone)
        assertTrue(c.levels)
        assertEquals((100 + 100 + 82 + 79 + 76 + 73 + 70 + 67 + 64) / 9, c.mean)
        assertEquals(2000L, c.lifeMinutes)
        val w = BatteryStats.chart(day(), BatteryStats.Window.Week, nowMs, zone)
        assertTrue(w.levels); assertEquals(168, w.bars.size); assertEquals(c.mean, w.mean)
        assertEquals(c.bars, w.bars.takeLast(24))
        val m = BatteryStats.chart(day(), BatteryStats.Window.Month, nowMs, zone)
        assertFalse(m.levels); assertEquals(18, m.mean)
        assertNull(BatteryStats.chart(emptyList(), BatteryStats.Window.Day, nowMs, zone).mean)
    }

    @Test fun onBatteryAndScreenOn() {
        assertEquals(6 * 60L, BatteryStats.onBatteryMinutes(day(), nowMs))
        assertNull(BatteryStats.onBatteryMinutes(day() + s(0.0, 64, plugged = true), nowMs))
        assertNull(BatteryStats.onBatteryMinutes(emptyList(), nowMs))
        // on at 6, 4, 2 and 0 hours ago, each held until the next sample: 3 hours plus the last 9 minutes.
        assertEquals(3 * 60L + 9, BatteryStats.screenOnMinutes(day(), nowMs - 24 * h, nowMs + 9 * 60_000))
        assertEquals(60L, BatteryStats.screenOnMinutes(day(), nowMs - 5 * h, nowMs - 3 * h))
    }

    @Test fun ratesSplitByScreenAndCharger() {
        // day(): six one-hour stretches of 3 each, opened alternately by a screen-on and a screen-off sample.
        val r = BatteryStats.rates(day())
        assertEquals(BatteryStats.Rate(9, 3 * h), r.screenOn)
        assertEquals(BatteryStats.Rate(9, 3 * h), r.screenOff)
        assertEquals(BatteryStats.Rate(), r.charge)     // on the charger only at 100
        assertEquals(3.0, r.screenOn.perHour, 1e-9)
        assertEquals(BatteryStats.Rate(3, h), BatteryStats.rates(day(), since = nowMs - h).screenOn)
    }

    @Test fun ratesLeaveOutRebootsTheTopAndCorrections() {
        val samples = listOf(
            s(10.0, 40, plugged = true), s(9.5, 60, plugged = true), s(9.0, 80, plugged = true),
            s(8.5, 97, plugged = true), s(8.4, 100, plugged = true),   // the tail: the level reached 100 somewhere in it
            s(8.0, 100),                                             // unplugged
            s(7.0, 96), s(6.9, 99),                                  // a correction upwards
            s(6.0, 95).copy(why = "start"), s(5.99, 95).copy(why = "boot"),   // off for 54 min, back on
            s(5.0, 93), s(4.0, 91, plugged = true), s(3.5, 99, plugged = true),
        )
        val r = BatteryStats.rates(samples)
        // 40 to 97 in 1.5 h and 91 to 99 in 0.5 h: 65 in 2 h; 97 to 100 and the unplug at 100 left out.
        assertEquals(BatteryStats.Rate(65, 2 * h), r.charge)
        // 100 to 96, then 95 to 93 from the boot, then 93 to 91 up to the plug: 8 in 2.99 h.
        assertEquals(8, r.screenOff.pct)
        assertEquals((2.99 * h).toDouble(), r.screenOff.ms.toDouble(), 2.0)
        assertEquals(BatteryStats.Rate(), r.screenOn)
        assertTrue(r.charge.qualifies); assertFalse(BatteryStats.Rate(4, h).qualifies); assertFalse(BatteryStats.Rate(20, 29 * 60_000L).qualifies)
    }

    @Test fun formatting() {
        assertEquals("3.0 %/h", BatteryStats.fmtRate(BatteryStats.Rate(35, 710 * 60_000L)))
        assertEquals("45 %/h", BatteryStats.fmtRate(BatteryStats.Rate(36, 48 * 60_000L)))
        assertEquals("10 %/h", BatteryStats.fmtRate(BatteryStats.Rate(10, h)))
        assertEquals("16 h 40", BatteryStats.fmt(1000))
        assertEquals("5 h 05", BatteryStats.fmt(305))
        assertEquals("40 min", BatteryStats.fmt(40))
        assertEquals("0 min", BatteryStats.fmt(0))
    }

    @Test fun foldedDaysFeedTheMonthChartLifeAndScreenOn() {
        val since = nowMs - 30 * 24 * h
        val d = BatteryStats.Day(
            now.toLocalDate().minusDays(10),
            BatteryStats.Totals(50, nowMs - 10 * 24 * h, nowMs - 10 * 24 * h + 20 * h, runs = BatteryStats.Rate(30, 10 * h), usedPct = 30, screenOnMs = 2 * h),
        )
        val (bars, _) = BatteryStats.daily(day(), now, 30, listOf(d))
        assertEquals(30, bars[19].value); assertEquals(18, bars[29].value)
        // 18 in 6 h raw and 30 in 10 h folded: 48 in 16 h, 3 an hour, 33 h 20 for a full charge.
        assertEquals(2000L, BatteryStats.lifeMinutes(day(), since, listOf(d)))
        assertEquals(2000L, BatteryStats.chart(day(), BatteryStats.Window.Month, nowMs, zone, listOf(d)).lifeMinutes)
        assertEquals(3 * 60L + 9 + 120, BatteryStats.screenOnMinutes(day(), since, nowMs + 9 * 60_000, listOf(d)))
        assertEquals(3 * 60L + 9, BatteryStats.screenOnMinutes(day(), nowMs - 24 * h, nowMs + 9 * 60_000, listOf(d)))
    }
}
