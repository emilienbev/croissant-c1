package com.bleural.croissant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bleural.croissant.data.PhotosStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One day of the contact sheet: its day line's word and its photos, newest first. */
data class PhotoDay(val label: String, val photos: List<File>)

/**
 * The photos grouped by the day they were taken, newest day and newest photo first,
 * labelled "Today", "Yesterday", then like the calendar's day lines ("Sun 13 Sept").
 * A file that is not one of ours (no time in its name) is left out.
 */
fun photoDays(files: List<File>, today: LocalDate): List<PhotoDay> {
    val dated = files.mapNotNull { f -> PhotosStore.takenAt(f)?.let { it to f } }.sortedByDescending { it.first }
    val out = ArrayList<PhotoDay>()
    var day: LocalDate? = null
    var photos = ArrayList<File>()
    for ((at, f) in dated) {
        if (at.toLocalDate() != day) {
            day = at.toLocalDate()
            photos = ArrayList()
            out += PhotoDay(if (day == today.minusDays(1)) "Yesterday" else dayLabel(day, today), photos)
        }
        photos += f
    }
    return out
}

/** The contact sheet as lines: a day line, then rows of up to three tiles. */
internal sealed interface SheetLine {
    data class Day(val day: PhotoDay) : SheetLine
    /** [first] is the flat index (newest first) of the row's first tile; [last] marks the last row of its day. */
    data class Tiles(val photos: List<File>, val first: Int, val last: Boolean) : SheetLine
}

/**
 * [days] as the sheet draws them. Shared with the Send picker, which
 * is the same sheet with the tap doing something else, so the two cannot drift.
 */
internal fun sheetLines(days: List<PhotoDay>): List<SheetLine> = buildList {
    var i = 0
    for (d in days) {
        add(SheetLine.Day(d))
        val rows = d.photos.chunked(3)
        for ((r, row) in rows.withIndex()) { add(SheetLine.Tiles(row, i, last = r == rows.lastIndex)); i += row.size }
    }
}

/** The line index of the row holding each tile, by flat index. */
internal fun rowOfTile(lines: List<SheetLine>, tiles: Int): IntArray =
    IntArray(tiles).also { a -> lines.forEachIndexed { li, l -> if (l is SheetLine.Tiles) l.photos.indices.forEach { a[l.first + it] = li } } }

/** Thumbnails at an eighth of the shot (153x204), kept for the session. */
private val thumbs = LruCache<String, Bitmap>(120)

@Composable
internal fun rememberThumb(file: File): State<Bitmap?> {
    val state = remember(file.path) { mutableStateOf(thumbs.get(file.path)) }
    LaunchedEffect(file.path) {
        if (state.value != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
        }
        if (b != null) { thumbs.put(file.path, b); state.value = b }
    }
    return state
}
