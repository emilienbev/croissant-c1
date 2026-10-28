package com.bleural.croissant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What was asked about a photo and what came back, kept beside it as `<name>.json`
 * so the photo page can show it again. One per photo, the latest exchange replacing
 * the one before.
 */
data class Exchange(val at: Long, val transcript: String, val answer: String)

/**
 * The photos taken on the device: one developed JPEG per shot under `files/photos/`,
 * named by the moment it was taken, so the name is the date and the sort. Newest
 * first. No cap: a shot is 300 kB and the R1 has gigabytes. An exchange about a
 * photo sits beside it as a JSON sidecar and goes with it.
 */
class PhotosStore(private val dir: File) {
    companion object {
        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        /** When a photo was taken, from its name; null for a file that is not ours. */
        fun takenAt(file: File): LocalDateTime? =
            runCatching { LocalDateTime.parse(file.nameWithoutExtension, NAME) }.getOrNull()

        private fun sidecar(photo: File) = File(photo.parentFile, photo.nameWithoutExtension + ".json")

        /** The exchange kept beside a photo, or null when nothing was asked about it (or the file is unreadable). */
        fun exchange(photo: File): Exchange? = runCatching {
            val o = JSONObject(sidecar(photo).readText())
            Exchange(o.getLong("at"), o.getString("transcript"), o.getString("answer"))
        }.getOrNull()
    }

    private val _items = MutableStateFlow(scan())
    val items: StateFlow<List<File>> = _items

    fun list(): List<File> = _items.value

    private fun scan(): List<File> =
        (dir.listFiles { f -> f.extension == "jpg" && takenAt(f) != null } ?: emptyArray())
            .sortedByDescending { it.name }

    @Synchronized
    fun save(jpeg: ByteArray, at: LocalDateTime = LocalDateTime.now()): File {
        dir.mkdirs()
        var f = File(dir, at.format(NAME) + ".jpg")
        var n = 0
        while (f.exists()) f = File(dir, at.plusNanos((++n) * 1_000_000L).format(NAME) + ".jpg")
        write(f, jpeg)
        _items.value = scan()
        return f
    }

    /** Keeps [exchange] beside [photo], replacing any earlier one. */
    @Synchronized
    fun keep(photo: File, exchange: Exchange) {
        val o = JSONObject().put("at", exchange.at).put("transcript", exchange.transcript).put("answer", exchange.answer)
        write(sidecar(photo), o.toString(2).toByteArray())
    }

    @Synchronized
    fun delete(file: File) {
        file.delete()
        sidecar(file).delete()
        _items.value = scan()
    }

    /** Atomic: the bytes go to a temp file, synced, then renamed into place. */
    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        tmp.renameTo(f)
    }
}
