package com.bleural.croissant.backup

import com.bleural.croissant.ask.writeAtomically
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What the last restore came to: `{"name": "...", "ok": true}`, or `"ok": false` with the
 * error naming the file or both versions. Kept in filesDir/restore-outcome.json, never in an
 * archive, for the page (until put away) and for the CLI, which waits for it.
 */
data class RestoreOutcome(val name: String, val ok: Boolean, val error: String? = null) {
    fun toJson(): String = JSONObject().put("name", name).put("ok", ok).apply { error?.let { put("error", it) } }.toString()

    /** Throws [IOException] when it cannot be written. */
    fun write(file: File) = writeAtomically(file, toJson())

    companion object {
        fun failed(name: String, why: String) = RestoreOutcome(name, false, why)

        /** Null when there is none. One that cannot be read is a failed outcome saying so. */
        fun read(file: File): RestoreOutcome? {
            if (!file.exists()) return null
            return try {
                val o = JSONObject(file.readText())
                RestoreOutcome(o.getString("name"), o.getBoolean("ok"), o.optString("error").ifEmpty { null })
            } catch (e: Exception) {
                failed("", "${file.path} cannot be read (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }
}

/** How far a restore got: what a start after a crash goes by. */
internal enum class Phase {
    /** The current files moving into the aside folder: put back what is there. */
    Aside,
    /** Every current file aside, the archive's moving in and the prefs being set: undo both. */
    In,
    /** All in place: only the aside folder, the staging and the zip are left to delete. */
    Applied,
}

/**
 * restore/journal.json, written (whole, synced) before each phase: the zip's name, the phase,
 * the top-level names that stay where they are, and the prefs as they were before, so the
 * old ones go back after a crash.
 */
internal data class Journal(val name: String, val phase: Phase, val stays: Set<String>, val prefs: Map<String, Any>) {

    /** Throws [IOException] when it cannot be written. */
    fun write(file: File) = writeAtomically(file, JSONObject()
        .put("name", name).put("phase", phase.name).put("stays", JSONArray(stays.sorted()))
        .put("prefs", TypedPrefs.encode(prefs)).toString())

    companion object {
        /** Throws [Unfit] or [IOException] saying why it cannot be read. */
        fun read(file: File): Journal {
            val o = try { JSONObject(file.readText()) } catch (e: JSONException) { throw Unfit("it is not JSON: ${e.message}") }
            val phase = Phase.entries.firstOrNull { it.name == o.optString("phase") } ?: throw Unfit("its phase is \"${o.optString("phase")}\"")
            val stays = o.optJSONArray("stays") ?: throw Unfit("it has no stays")
            val prefs = o.optJSONObject("prefs") ?: throw Unfit("it has no prefs")
            return Journal(o.optString("name"), phase, (0 until stays.length()).map { stays.getString(it) }.toSet(), TypedPrefs.decode(prefs))
        }
    }
}
