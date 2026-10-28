package com.bleural.croissant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * One picture the model was asked to make.
 *
 * [file] is null until the device has fetched it from the server; [failed] is the
 * reason the server gave for it never arriving. [tries] counts the fetches so far, so
 * the shelf can say whether the automatic ones are spent. [source] is the photo it was
 * transformed from, by name, when it was not made from nothing; null on every picture
 * made before transforms existed. [c1]: made by a cloud model from this device (ask/Pictures.kt),
 * so there is nothing to ask the server for, and one the app restarted under will not come.
 */
data class Generated(
    val id: String,
    val prompt: String,
    val at: Long,
    val file: File?,
    val failed: String?,
    val tries: Int,
    val source: String? = null,
    val c1: Boolean = false,
) {
    companion object {
        /**
         * How long a picture can be expected before the device gives up on it by itself.
         * The server keeps a picture nobody collected for 24 hours (its
         * `image_keep_hours`), so past that there is nothing left to fetch and saying
         * "being made" would be a lie. This needs no network to know, which is the
         * point: the case it exists for is the one where there is none.
         */
        const val KEEP_MS = 24L * 60 * 60 * 1000
    }

    /**
     * The reason there will be no picture: the server's, or ours once it is too late.
     *
     * Told apart from [failed] because nothing wrote it down - a row that sat pending
     * through the server's whole keep window is lost whether or not anyone was there to
     * hear it fail, and it has to read that way after a reboot with the Mac asleep.
     */
    val lost: String?
        get() = failed ?: "Never arrived".takeIf {
            file == null && System.currentTimeMillis() - at > KEEP_MS
        }

    val pending: Boolean get() = file == null && lost == null
}

/**
 * The pictures made on the server, or here by a cloud model ([Generated.c1]), and kept here,
 * under `files/generated/`.
 *
 * Unlike a photo, whose name is the moment it was taken, a generation is named by the
 * id the server minted: that id is how the picture is fetched, so it has to survive a
 * reboot. `<id>.json` is written the moment the device is told a picture is coming,
 * and `<id>.jpg` appears beside it once the bytes arrive.
 */
class GeneratedStore(internal val dir: File) {
    private val _items = MutableStateFlow(scan())
    val items: StateFlow<List<Generated>> = _items

    fun list(): List<Generated> = _items.value

    fun get(id: String): Generated? = _items.value.firstOrNull { it.id == id }

    /** The picture is being made ([c1]: here): remember it so it can be fetched, and shown while it is not. */
    @Synchronized
    fun add(id: String, prompt: String, at: Long = System.currentTimeMillis(), source: String? = null, c1: Boolean = false) {
        if (json(id).exists()) return
        dir.mkdirs()
        write(json(id), row(id, prompt, at, null, 0, source, c1))
        _items.value = scan()
    }

    /** The bytes arrived. */
    @Synchronized
    fun save(id: String, jpeg: ByteArray) {
        val was = read(json(id)) ?: return
        dir.mkdirs()
        write(jpg(id), jpeg)
        write(json(id), row(id, was.prompt, was.at, null, was.tries + 1, was.source, was.c1))
        _items.value = scan()
    }

    /** It will not arrive: [reason] is shown in its place. */
    @Synchronized
    fun fail(id: String, reason: String) {
        val was = read(json(id)) ?: return
        write(json(id), row(id, was.prompt, was.at, reason, was.tries + 1, was.source, was.c1))
        _items.value = scan()
    }

    /** One more fetch went by without an answer. */
    @Synchronized
    fun tried(id: String) {
        val was = read(json(id)) ?: return
        write(json(id), row(id, was.prompt, was.at, was.failed, was.tries + 1, was.source, was.c1))
        _items.value = scan()
    }

    @Synchronized
    fun delete(id: String) {
        jpg(id).delete()
        json(id).delete()
        _items.value = scan()
    }

    private fun scan(): List<Generated> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull(::read)
            .sortedByDescending { it.at }

    private fun jpg(id: String) = File(dir, "$id.jpg")
    private fun json(id: String) = File(dir, "$id.json")

    private fun read(f: File): Generated? = runCatching {
        val o = JSONObject(f.readText())
        val id = o.getString("id")
        val picture = jpg(id).takeIf { it.exists() }
        Generated(id, o.optString("prompt"), o.getLong("at"), picture,
            o.optString("failed").takeIf { it.isNotEmpty() }, o.optInt("tries"),
            o.optString("source").takeIf { it.isNotEmpty() }, o.optBoolean("c1"))
    }.getOrNull()

    private fun row(id: String, prompt: String, at: Long, failed: String?, tries: Int, source: String?, c1: Boolean) =
        JSONObject().put("id", id).put("prompt", prompt).put("at", at).put("tries", tries)
            .apply { if (failed != null) put("failed", failed) }
            .apply { if (source != null) put("source", source) }
            .apply { if (c1) put("c1", true) }
            .toString(2).toByteArray()

    /** Atomic: the bytes go to a temp file, synced, then renamed into place. */
    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        tmp.renameTo(f)
    }
}
