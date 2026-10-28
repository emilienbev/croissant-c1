package com.bleural.croissant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.OffsetDateTime

/**
 * One collection in one JSON file (an array), rewritten atomically (temp + rename) on
 * every change and observable for the screens. Subclasses say how an item is encoded
 * and which items to keep; [purge] runs at load and before every write. A corrupt
 * file starts empty rather than crashing the app.
 */
abstract class JsonStore<T>(
    private val file: File,
    protected val clock: () -> OffsetDateTime,
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items

    protected abstract fun encode(item: T): JSONObject
    protected abstract fun decode(o: JSONObject): T
    protected abstract fun keep(item: T, now: OffsetDateTime): Boolean

    fun list(): List<T> = _items.value

    /** Subclasses call this last in their `init`, once everything encode/decode needs exists. */
    protected fun load() {
        val parsed = runCatching {
            if (!file.exists()) emptyList() else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
            }
        }.getOrDefault(emptyList())
        val kept = purge(parsed)
        _items.value = kept
        if (kept.size != parsed.size) write(kept)
    }

    protected fun commit(list: List<T>) {
        val kept = purge(list)
        write(kept)
        _items.value = kept
    }

    private fun purge(list: List<T>): List<T> {
        val now = clock()
        return list.filter { keep(it, now) }
    }

    private fun write(list: List<T>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(encode(it)) } }
        val tmp = File(file.parentFile, file.name + ".tmp")
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(arr.toString(2).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw ToolError("could not write ${file.name}")
    }

    companion object {
        private val random = SecureRandom()
        /** `r_5592d2`: a prefix per collection and six hex digits, like the fake device's. */
        fun newId(prefix: String) = prefix + "_" + random.nextInt(0x1000000).toString(16).padStart(6, '0')

        /** Absent and JSON null both read as null; a number or boolean is taken as its text. */
        fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else get(key).toString()
    }
}
