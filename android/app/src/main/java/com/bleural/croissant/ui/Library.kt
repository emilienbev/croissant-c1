package com.bleural.croissant.ui

import com.bleural.croissant.data.Recording
import java.time.LocalDate

/** The Library as lines: a day line with its count, then that day's tapes, newest first. */
sealed interface LibraryLine {
    data class Day(val label: String, val count: Int) : LibraryLine
    data class Row(val rec: Recording) : LibraryLine
}

fun libraryLines(items: List<Recording>, today: LocalDate): List<LibraryLine> {
    val dated = items.mapNotNull { r -> r.startedAt?.let { it to r } }.sortedByDescending { it.first }
    val out = ArrayList<LibraryLine>()
    var day: LocalDate? = null
    for ((at, r) in dated) {
        if (at.toLocalDate() != day) {
            day = at.toLocalDate()
            val label = if (day == today.minusDays(1)) "Yesterday" else dayLabel(day, today)
            out += LibraryLine.Day(label, dated.count { it.first.toLocalDate() == day })
        }
        out += LibraryLine.Row(r)
    }
    return out
}
