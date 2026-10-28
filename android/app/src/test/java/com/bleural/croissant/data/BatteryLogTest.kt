package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class BatteryLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L
    private var now = 1_000L * day
    private fun file() = File(tmp.root, "battery.jsonl")
    private fun log() = BatteryLog(file()) { now }

    @Test fun appendsOneLineAndReloads() {
        val l = log()
        l.append(BatterySample(now - 60_000, 82, false, true, 4338, -2800, 254, "tick"))
        l.append(BatterySample(now, 81, false, false, why = "off"))
        assertEquals(2, file().readLines().size)
        assertEquals(l.list(), log().list())
        assertEquals("off", log().list()[1].why)
        assertEquals(-2800, log().list()[0].uA)
    }

    @Test fun skipsBadLinesAndSorts() {
        file().writeText("{\"at\":${now},\"pct\":50,\"plug\":false,\"on\":true}\nnot json\n{\"at\":${now - 1},\"pct\":51,\"plug\":true,\"on\":false}\n")
        val l = log()
        assertEquals(listOf(51, 50), l.list().map { it.pct })
        assertTrue(l.list()[0].plugged)
    }

    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, utc).toInstant().toEpochMilli()
    private fun inline() = BatteryLog(file(), { now }, { utc }, { it() })
    private fun monthsFile() = File(tmp.root, "battery-months.jsonl")

    private fun daysFile() = File(tmp.root, "battery-days.jsonl")

    @Test fun foldsADaySevenDaysAfterItEnds() {
        now = at(2026, 1, 10)
        val l = inline()
        l.append(BatterySample(at(2026, 1, 10, 20), 90, false, false))
        l.append(BatterySample(at(2026, 1, 10, 22), 84, false, false))
        l.append(BatterySample(at(2026, 1, 10, 23), 80, false, true))
        l.append(BatterySample(at(2026, 1, 11, 1), 77, false, false))
        now = at(2026, 1, 17, 23)                        // 10 January ended at midnight: due 18 January
        l.append(BatterySample(now, 60, true, false))
        assertEquals(5, l.list().size); assertTrue(l.days.value.isEmpty())
        now = at(2026, 1, 18, 1)
        l.append(BatterySample(now, 62, true, false))
        assertEquals(listOf(77, 60, 62), l.list().map { it.pct })
        assertEquals(3, file().readLines().size)
        val d = l.days.value.single()
        assertEquals(LocalDate.of(2026, 1, 10), d.date)
        assertEquals(3, d.totals.samples)
        assertEquals(BatteryStats.Rate(10, 3 * 3_600_000L), d.totals.screenOff)   // 90 to 80 in 3 h; the stretch to the 11th is not the 10th's
        assertEquals(10, d.totals.usedPct)
        assertEquals(0L, d.totals.screenOnMs)          // on only at the day's last sample
        // The run 90 to 77 qualifies as a whole; the 10th has the stretches that end in it, 10 in 3 h.
        assertEquals(BatteryStats.Rate(10, 3 * 3_600_000L), d.totals.runs)
        assertTrue(l.months.value.isEmpty())
        // Reloaded from disk: the same raw samples and the same day, and nothing more to fold.
        val again = inline()
        assertEquals(l.list(), again.list())
        assertEquals(l.days.value, again.days.value)
        assertEquals(1, daysFile().readLines().size)
    }

    @Test fun daysAddUpToTheirMonthNinetyDaysAfterItEnds() {
        now = at(2026, 1, 10)
        val l = inline()
        l.append(BatterySample(at(2026, 1, 10, 8), 90, false, false))
        l.append(BatterySample(at(2026, 1, 10, 10), 84, false, false))
        l.append(BatterySample(at(2026, 1, 31, 8), 70, false, false))
        l.append(BatterySample(at(2026, 1, 31, 10), 66, false, false))
        l.append(BatterySample(at(2026, 2, 1, 8), 50, false, false))
        now = at(2026, 2, 9)                             // the three days folded, January still in days
        l.append(BatterySample(now, 40, false, false))
        assertEquals(3, l.days.value.size); assertTrue(l.months.value.isEmpty())
        now = at(2026, 5, 2)                             // January ended 1 February: its days due 2 May
        l.append(BatterySample(now, 30, false, false))
        val jan = l.months.value.single()
        assertEquals(YearMonth.of(2026, 1), jan.month)
        assertEquals(4, jan.totals.samples)
        assertEquals(BatteryStats.Rate(10, 4 * 3_600_000L), jan.totals.screenOff)
        assertEquals(10, jan.totals.usedPct)
        assertEquals(at(2026, 1, 10, 8), jan.totals.from); assertEquals(at(2026, 1, 31, 10), jan.totals.to)
        assertEquals(listOf(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 9)), l.days.value.map { it.date })
        assertEquals(2, daysFile().readLines().size)
        val again = inline()
        assertEquals(l.days.value, again.days.value); assertEquals(l.months.value, again.months.value)
    }

    @Test fun aFoldCutShortIsDoneAgainWithoutDuplicates() {
        now = at(2026, 1, 10)
        val l = inline()
        l.append(BatterySample(at(2026, 1, 10, 8), 90, false, false))
        l.append(BatterySample(at(2026, 1, 10, 10), 84, false, false))
        // The day was written, then the process died before the raw file was swapped.
        val stale = BatteryStats.day(l.list(), LocalDate.of(2026, 1, 10), utc).let { it.copy(totals = it.totals.copy(samples = 99)) }
        daysFile().writeText(BatteryLog.encodeDay(stale).toString() + "\n")
        now = at(2026, 1, 20)
        val again = inline()
        assertTrue(again.list().isEmpty())
        assertEquals(2, again.days.value.single().totals.samples)
        assertEquals(1, daysFile().readLines().size)
        // The month was written, then the process died before the days file was rewritten.
        monthsFile().writeText(BatteryLog.encodeMonth(BatteryStats.Month(YearMonth.of(2026, 1), BatteryStats.Totals(samples = 99))).toString() + "\n")
        now = at(2026, 6, 1)
        val third = inline()
        assertTrue(third.days.value.isEmpty())
        assertEquals(2, third.months.value.single().totals.samples)
        assertEquals(1, monthsFile().readLines().size)
    }

    @Test fun monthsFromBeforeTheDaysStillRead() {
        monthsFile().writeText("{\"month\":\"2026-08\",\"samples\":10,\"from\":1,\"to\":2,\"on_pct\":5,\"on_ms\":3600000,\"off_pct\":1,\"off_ms\":2,\"charge_pct\":0,\"charge_ms\":0,\"runs_pct\":6,\"runs_ms\":7,\"used\":8,\"screen_on_ms\":9}\n")
        val m = log().months.value.single()
        assertEquals(BatteryStats.Rate(5, 3_600_000L), m.totals.screenOn); assertEquals(8, m.totals.usedPct)
    }
}
