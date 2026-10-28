package com.bleural.croissant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bleural.croissant.data.Generated
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One day of the generated shelf: its day line's word and its pictures, newest first. */
data class GeneratedDay(val label: String, val items: List<Generated>)

/**
 * The generated pictures grouped by the day they were asked for, newest first, with
 * the same day words the photo sheet uses. The same shape as `photoDays`, over a
 * different item: a generation carries its own clock rather than wearing it as a
 * filename, because its name has to be the server's id.
 */
fun generatedDays(items: List<Generated>, today: LocalDate): List<GeneratedDay> {
    val zone = ZoneId.systemDefault()
    val dated = items.sortedByDescending { it.at }
        .map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), zone) to it }
    val out = ArrayList<GeneratedDay>()
    var day: LocalDate? = null
    var group = ArrayList<Generated>()
    for ((at, g) in dated) {
        if (at.toLocalDate() != day) {
            day = at.toLocalDate()
            group = ArrayList()
            out += GeneratedDay(if (day == today.minusDays(1)) "Yesterday" else dayLabel(day, today), group)
        }
        group += g
    }
    return out
}

/** What stands in for a picture on its page: [word], and whether it is a failure. */
data class GeneratedStatus(val word: String, val failed: Boolean)

/**
 * The line the picture page shows where the picture would be.
 *
 * [asking] is a fresh ask to the server in flight, [unreadable] a file that is on the
 * device but will not decode. A picture still coming says one steady thing: the page
 * fetches it every few seconds by itself, and a word that flickered with each of those
 * would read as trouble rather than as waiting. It stops saying it after a day, which
 * is `Generated.lost`: by then there is nothing left on the Mac to come.
 */
fun generatedStatus(item: Generated, asking: Boolean, unreadable: Boolean = false): GeneratedStatus {
    val lost = item.lost
    return when {
        asking -> GeneratedStatus("Asking again", false)
        unreadable -> GeneratedStatus("Picture will not open", true)
        // A reason relayed from the server starts however the server wrote it.
        lost != null -> GeneratedStatus(lost.replaceFirstChar { it.uppercase() }, true)
        else -> GeneratedStatus("Being made", false)
    }
}

/** The shelf as lines: a day line, then rows of up to three tiles. */
internal sealed interface ShelfLine {
    data class Day(val day: GeneratedDay) : ShelfLine
    data class Tiles(val items: List<Generated>, val first: Int, val last: Boolean) : ShelfLine
}

/** [days] as the shelf draws them, shared with the Send picker as the sheet's is. */
internal fun shelfLines(days: List<GeneratedDay>): List<ShelfLine> = buildList {
    var i = 0
    for (d in days) {
        add(ShelfLine.Day(d))
        val rows = d.items.chunked(3)
        for ((r, row) in rows.withIndex()) { add(ShelfLine.Tiles(row, i, last = r == rows.lastIndex)); i += row.size }
    }
}

/** The line index of the row holding each tile, by flat index. */
internal fun rowOfShelfTile(lines: List<ShelfLine>, tiles: Int): IntArray =
    IntArray(tiles).also { a -> lines.forEachIndexed { li, l -> if (l is ShelfLine.Tiles) l.items.indices.forEach { a[l.first + it] = li } } }

/** Thumbnails at an eighth of the picture, kept for the session, as the photo sheet does. */
private val thumbs = LruCache<String, Bitmap>(60)

@Composable
internal fun rememberGeneratedThumb(file: java.io.File): State<Bitmap?> {
    val state = remember(file.path) { mutableStateOf(thumbs.get(file.path)) }
    LaunchedEffect(file.path) {
        if (state.value != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
        }
        if (b != null) { thumbs.put(file.path, b); state.value = b }
    }
    return state
}
