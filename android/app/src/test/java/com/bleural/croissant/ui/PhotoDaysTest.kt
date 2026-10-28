package com.bleural.croissant.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PhotoDaysTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 15)
    private fun f(name: String) = File("$name.jpg")
    // Newest first, as the store lists them; a file that is not ours among them.
    private val files = listOf(
        "20260915-130400-000", "20260915-090000-000", "20260914-183005-120", "20260914-120000-000",
        "other", "20260901-080000-000", "20260901-075959-999",
    ).map(::f)

    @Test fun dayLinesWithCountsNewestFirst() {
        val days = photoDays(files, today)
        assertEquals(listOf("Today" to 2, "Yesterday" to 2, "Tue 1 Sept" to 2), days.map { it.label to it.photos.size })
        assertEquals(listOf("20260915-130400-000.jpg", "20260915-090000-000.jpg"), days[0].photos.map { it.name })
        assertEquals(listOf("20260901-080000-000.jpg", "20260901-075959-999.jpg"), days[2].photos.map { it.name })
    }

    @Test fun ordersNewestFirstWhateverTheInputOrder() {
        val days = photoDays(files.reversed(), today)
        assertEquals(listOf("Today", "Yesterday", "Tue 1 Sept"), days.map { it.label })
        assertEquals("20260915-130400-000.jpg", days[0].photos[0].name)
    }

    @Test fun emptyAndForeignFiles() {
        assertEquals(emptyList<PhotoDay>(), photoDays(emptyList(), today))
        assertEquals(emptyList<PhotoDay>(), photoDays(listOf(f("other")), today))
    }
}
