package com.bleural.croissant.ui

import com.bleural.croissant.data.Recording
import com.bleural.croissant.data.RecordingMeta
import com.bleural.croissant.data.Segment
import com.bleural.croissant.data.lengthClock
import com.bleural.croissant.data.lengthWords
import com.bleural.croissant.data.paragraphs
import com.bleural.croissant.data.stampText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class LibraryTest {
    private fun rec(name: String, n: Int) = Recording(File("$name.wav"), RecordingMeta("", 1_000L, RecordingMeta.STOP, number = n))
    @Test fun lengthsAndCounters() {
        assertEquals("12 s", lengthWords(12_400))
        assertEquals("1 min 49 s", lengthWords(109_000))
        assertEquals("17 min", lengthWords(17 * 60_000L))
        assertEquals("1 h 05 min", lengthWords(65 * 60_000L + 12_000))
        assertEquals("17:13", lengthClock(17 * 60_000L + 13_000))
        assertEquals("1:05:12", lengthClock(65 * 60_000L + 12_000))
        assertEquals("0:00:00", counterText(0))
        assertEquals("1:24:07", counterText(84 * 60_000L + 7_000))
    }

    @Test fun groupsByDayNewestFirst() {
        val today = LocalDate.of(2026, 9, 16)
        val lines = libraryLines(listOf(rec("20260915-091200", 1), rec("20260916-104700", 3), rec("20260916-090200", 2), rec("bogus", 4)), today)
        assertEquals(
            listOf(LibraryLine.Day("Today", 2), "Tape 3", "Tape 2", LibraryLine.Day("Yesterday", 1), "Tape 1"),
            lines.map { if (it is LibraryLine.Row) it.rec.title else it },
        )
    }

    @Test fun paragraphsBreakOnGapsAndLength() {
        val segs = listOf(
            Segment(0.0, 4.0, " Right, shall we start? "), Segment(4.2, 8.0, "Sam sent the numbers."),
            Segment(11.0, 14.0, "Yes."),                      // a gap of three seconds
            Segment(14.5, 40.0, "Long."), Segment(40.5, 60.0, "Longer."),   // would run past 45 s from 11.0: a new paragraph
            Segment(60.2, 61.0, "Done."),
        )
        val p = paragraphs(segs)
        assertEquals(listOf(0.0, 11.0, 40.5), p.map { it.start })
        assertEquals("Right, shall we start? Sam sent the numbers.", p[0].text)
        assertEquals("Yes. Long.", p[1].text)
        assertEquals("Longer. Done.", p[2].text)
        assertEquals("0:00", stampText(0.0))
        assertEquals("1:04:30", stampText(3870.0))
    }
}
