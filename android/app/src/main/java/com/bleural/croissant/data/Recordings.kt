package com.bleural.croissant.data

import com.bleural.croissant.audio.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One piece of a transcript: seconds into the recording, and the words. */
data class Segment(val start: Double, val end: Double, val text: String)

/**
 * The sidecar beside a recording, `<name>.json`. [ended] is `recording` while the
 * file is open, `stop` once stopped, `interrupted` when a crash or a reboot ended
 * it and the store repaired the file. [transcript] is what the server has sent back
 * so far, kept as it arrives so a dropped link loses nothing; [sent] is the server's
 * id for the upload once one has completed. [number] is the tape's, given once when it
 * starts and never reused while it exists; [name] is what the user called it, null for
 * "Tape [number]".
 */
data class RecordingMeta(
    val startedAt: String,
    val durationMs: Long,
    val ended: String,
    val sent: String? = null,
    val transcript: List<Segment>? = null,
    val number: Int? = null,
    val name: String? = null,
) {
    val transcribed: Boolean get() = transcript != null && sent != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("startedAt", startedAt); put("durationMs", durationMs); put("ended", ended)
        if (sent != null) put("sent", sent)
        if (number != null) put("number", number)
        if (name != null) put("name", name)
        if (transcript != null) put("transcript", JSONArray().apply {
            for (s in transcript) put(JSONObject().put("start", s.start).put("end", s.end).put("text", s.text))
        })
    }

    companion object {
        const val RECORDING = "recording"
        const val STOP = "stop"
        const val INTERRUPTED = "interrupted"

        fun fromJson(o: JSONObject): RecordingMeta = RecordingMeta(
            startedAt = o.getString("startedAt"),
            durationMs = o.getLong("durationMs"),
            ended = o.optString("ended", STOP),
            sent = o.optString("sent").takeIf { it.isNotEmpty() },
            number = if (o.has("number")) o.getInt("number") else null,
            name = o.optString("name").takeIf { it.isNotEmpty() },
            transcript = o.optJSONArray("transcript")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { Segment(it.getDouble("start"), it.getDouble("end"), it.getString("text")) } }
            },
        )
    }
}

/** A recording on the device: its WAV and what the sidecar says about it, if there is one. */
data class Recording(val file: File, val meta: RecordingMeta?) {
    val startedAt: LocalDateTime? get() = RecordingsStore.startedAt(file)
    /** From the sidecar, else from the file's length. */
    val durationMs: Long get() = meta?.durationMs ?: Wav.millis((file.length() - Wav.HEADER_BYTES).coerceAtLeast(0))
    val number: Int get() = meta?.number ?: 0
    /** What the list and the page call it: the name it was given, else "Tape 3". */
    val title: String get() = meta?.name ?: "Tape $number"
}

/**
 * The recordings made on the device: one WAV per recording under `files/recordings/`,
 * named by the moment it started, so the name is the date and the sort. Newest first.
 * No cap: an hour is 115 MB and the R1 has 103 GB free. The sidecar goes with the
 * file. On construction the store repairs any file a crash left open: the header's
 * sizes from the file's length, the sidecar marked interrupted, and numbers any tape
 * that has no number yet, oldest first after the highest there is.
 */
class RecordingsStore(private val dir: File) {
    companion object {
        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** When a recording started, from its name; null for a file that is not ours. */
        fun startedAt(file: File): LocalDateTime? =
            runCatching { LocalDateTime.parse(file.nameWithoutExtension, NAME) }.getOrNull()

        fun sidecar(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".json")

        fun meta(wav: File): RecordingMeta? = runCatching { RecordingMeta.fromJson(JSONObject(sidecar(wav).readText())) }.getOrNull()
    }

    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items

    init { repair(); number(); _items.value = scan() }

    fun list(): List<Recording> = _items.value

    private fun scan(): List<Recording> =
        (dir.listFiles { f -> f.extension == "wav" && startedAt(f) != null } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { Recording(it, meta(it)) }

    /** A fresh, unique file for a recording starting at [at]; nothing is written. */
    @Synchronized
    fun newFile(at: LocalDateTime = LocalDateTime.now()): File {
        dir.mkdirs()
        var f = File(dir, at.format(NAME) + ".wav")
        var n = 0
        while (f.exists() || sidecar(f).exists()) f = File(dir, at.plusSeconds((++n).toLong()).format(NAME) + ".wav")
        return f
    }

    /** Writes [meta] beside [wav], replacing any earlier one, and re-reads the list. */
    @Synchronized
    fun keep(wav: File, meta: RecordingMeta) {
        write(sidecar(wav), meta.toJson().toString(2).toByteArray())
        _items.value = scan()
    }

    /** The number the next tape gets: one past the highest there is, so tape 1 on an empty shelf. */
    @Synchronized
    fun nextNumber(): Int = (_items.value.maxOfOrNull { it.number } ?: 0) + 1

    /** Calls [wav] [name]; blank, or its default, goes back to "Tape n". */
    @Synchronized
    fun rename(wav: File, name: String) {
        val m = meta(wav) ?: return
        val n = name.trim().takeIf { it.isNotEmpty() && it != "Tape ${m.number ?: 0}" }
        keep(wav, m.copy(name = n))
    }

    @Synchronized
    fun delete(wav: File) {
        wav.delete()
        sidecar(wav).delete()
        _items.value = scan()
    }

    @Synchronized
    fun refresh() { _items.value = scan() }

    /**
     * A file whose header still says zero, or whose sidecar still says recording, was
     * left open by a crash or a reboot: the sizes come from the file's length and the
     * sidecar says interrupted. What was written stays.
     */
    private fun repair() {
        for (f in dir.listFiles { f -> f.extension == "wav" && startedAt(f) != null } ?: emptyArray()) {
            val m = meta(f)
            val open = m?.ended == RecordingMeta.RECORDING || (m == null && Wav.dataSize(f) == 0)
            if (!open) continue
            runCatching { Wav.repair(f) }
            val duration = Wav.millis((f.length() - Wav.HEADER_BYTES).coerceAtLeast(0))
            val started = m?.startedAt ?: startedAt(f)!!.toString()
            val fixed = m?.copy(durationMs = duration, ended = RecordingMeta.INTERRUPTED) ?: RecordingMeta(started, duration, RecordingMeta.INTERRUPTED)
            write(sidecar(f), fixed.toJson().toString(2).toByteArray())
        }
    }

    /** Tapes from before numbering, or without a sidecar: numbered in the order they were made. */
    private fun number() {
        val files = (dir.listFiles { f -> f.extension == "wav" && startedAt(f) != null } ?: emptyArray()).sortedBy { it.name }
        var next = (files.maxOfOrNull { meta(it)?.number ?: 0 } ?: 0) + 1
        for (f in files) {
            val m = meta(f)
            if (m?.number != null) continue
            val base = m ?: RecordingMeta(startedAt(f)!!.toString(), Wav.millis((f.length() - Wav.HEADER_BYTES).coerceAtLeast(0)), RecordingMeta.STOP)
            write(sidecar(f), base.copy(number = next++).toJson().toString(2).toByteArray())
        }
    }

    /** Atomic: the bytes go to a temp file, synced, then renamed into place. */
    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        tmp.renameTo(f)
    }
}
