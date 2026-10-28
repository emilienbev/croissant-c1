package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What one call took (a ledger line's counts): tokens read ([input], of them [cached] from the
 * provider's cache), tokens written ([out]), seconds of audio heard ([secs], and [live] for
 * the live words' passes, billed alike but not what was said), pictures made.
 */
data class Counts(val input: Double = 0.0, val cached: Double = 0.0, val out: Double = 0.0,
                  val secs: Double = 0.0, val live: Double = 0.0, val pictures: Double = 0.0) {
    operator fun plus(o: Counts) = Counts(input + o.input, cached + o.cached, out + o.out, secs + o.secs, live + o.live, pictures + o.pictures)
    operator fun times(k: Double) = Counts(input * k, cached * k, out * k, secs * k, live * k, pictures * k)
}

/** One line of the ledger: [t] when (seconds since the epoch), [slot] its key, [model] `provider:model`. */
data class Row(val t: Long, val slot: String, val model: String, val counts: Counts)

/**
 * What the C1 ran, counted (the server's ledger.py): one line per model call in usage.jsonl
 * in the device-protected files, with the model and its counts, never what was said.
 * Settings > usage reads it, and so does each model's month at your pace ([Costs]). Counts
 * are what the provider reports. Lines older than [KEEP_DAYS] leave the file when it is
 * read, and memory as they age. [clock] in ms since the epoch.
 *
 * A line that cannot be written is kept in memory and lost at the next start: a count is
 * never worth failing the call it counts.
 */
class Ledger(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {

    private var rows = ArrayList<Row>()

    init {
        val f = file
        if (f != null && f.isFile) {
            rows = runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNullTo(ArrayList()) { line ->
                runCatching { row(JSONObject(line)) }.getOrNull()
            }
            val kept = since(clock() / 1000.0 - KEEP_DAYS * DAY)
            if (kept.size < rows.size) {
                rows = ArrayList(kept)
                try {
                    writeAtomically(f, kept.joinToString("") { json(it) + "\n" })
                } catch (_: IOException) {
                    // Trimmed again at the next start.
                }
            }
        }
    }

    /** One call of [model] (`provider:model`) for [slot]. */
    @Synchronized
    fun add(slot: ModelSlot, model: String, counts: Counts) {
        val row = Row(clock() / 1000, slot.key, model,
            counts.run { Counts(round1(input), round1(cached), round1(out), round1(secs), round1(live), round1(pictures)) })
        // Old rows leave memory as they leave the file, at most about once a day.
        if (rows.isNotEmpty() && rows[0].t < row.t - (KEEP_DAYS + 1) * DAY) rows = ArrayList(since((row.t - KEEP_DAYS * DAY).toDouble()))
        rows.add(row)
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write((json(row) + "\n").toByteArray()) }
        } catch (_: IOException) {
            // Kept in memory until the next start.
        }
    }

    /** The lines written at [start] (seconds since the epoch) or after. */
    @Synchronized
    fun since(start: Double): List<Row> = rows.filter { it.t >= start }

    /** When the oldest line kept was written (seconds since the epoch). */
    @Synchronized
    fun first(): Long? = rows.minOfOrNull { it.t }

    companion object {
        /** Twice the window Usage shows. */
        const val KEEP_DAYS = 60
        const val DAY = 86400L

        /** The counts' names on a line, as the server writes them. */
        private val KEYS = listOf("in", "cached", "out", "secs", "live", "pictures")

        private fun Counts.values() = listOf(input, cached, out, secs, live, pictures)

        private fun json(r: Row): String = JSONObject().put("t", r.t).put("slot", r.slot).put("model", r.model).apply {
            KEYS.zip(r.counts.values()).forEach { (k, v) -> if (v != 0.0) put(k, v) }
        }.toString()

        private fun row(o: JSONObject): Row {
            val v = KEYS.map { o.optDouble(it, 0.0) }
            return Row(o.optLong("t", 0), o.optString("slot"), o.optString("model"), Counts(v[0], v[1], v[2], v[3], v[4], v[5]))
        }

        /** Python's `round(v, 1)`: the exact value to one decimal, half to even. */
        private fun round1(v: Double): Double = if (!v.isFinite()) v else BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toDouble()
    }
}

/**
 * [inner] with every call it makes written in [ledger] under [entry] (`provider:model`), as
 * its provider counted it (the server's MeteredChat): a hold's rounds, the single calls, the
 * model test's. A call that reports no counts is not written.
 */
class Metered(private val inner: ChatApi, private val entry: String, private val ledger: Ledger) : ChatApi {
    override val model: String get() = inner.model

    override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
        val reply = inner.stream(messages, tools, text)
        reply.usage?.let { u ->
            withContext(Dispatchers.IO) {
                ledger.add(ModelSlot.Chat, entry, Counts(input = u.promptTokens.toDouble(), cached = u.cachedTokens.toDouble(), out = u.outTokens.toDouble()))
            }
        }
        return reply
    }
}
