package com.bleural.croissant.ui

import com.bleural.croissant.data.Timer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class CountdownTest {
    @Test fun formats() {
        assertEquals("0:00", countdownText(0))
        assertEquals("0:01", countdownText(1))          // any fraction of a second still counts
        assertEquals("0:59", countdownText(59_000))
        assertEquals("1:00", countdownText(60_000))
        assertEquals("2:00", countdownText(119_500))
        assertEquals("1:00:00", countdownText(3_600_000))
        assertEquals("1:01:01", countdownText(3_661_000))
        assertEquals("99:59:59", countdownText(359_999_000))
    }

    @Test fun millisLeftFromTheWallClock() {
        val t = Timer("t_1", null, "2026-09-14T18:02:00+01:00", "orange")
        val end = OffsetDateTime.parse("2026-09-14T18:02:00+01:00").toInstant().toEpochMilli()
        assertEquals(120_000L, t.millisLeft(end - 120_000))
        assertEquals(0L, t.millisLeft(end + 5))
    }
}
