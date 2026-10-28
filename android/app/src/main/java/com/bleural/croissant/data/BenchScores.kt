package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The model test's last score on the chat model [entry] (`provider:model`) with the variant [prompt]; [at] when it ended, epoch ms. */
data class BenchScore(val entry: String, val prompt: String, val passed: Int, val total: Int, val at: Long)

/**
 * The model test's last score per chat model and prompt variant: scores.json beside
 * prompts.json, rewritten whole (temp + rename) on every change, the file first and then
 * the state, as [PromptsStore]. Only a run of every case on offer that ended with a score
 * is saved (Core `startBench`): one on chosen groups says nothing of the others. A variant
 * renamed takes its scores along; one deleted drops them. A missing or corrupt file starts
 * empty. A change that cannot be written is not made (null when it is, else why not).
 */
class BenchScoresStore(private val file: File) {

    var scores: List<BenchScore> = emptyList()
        private set

    init {
        load()
    }

    /** [s] in place of the score it follows, if any. */
    fun save(s: BenchScore): String? = commit(scores.filterNot { it.entry == s.entry && it.prompt == s.prompt } + s)

    fun rename(prompt: String, to: String): String? = commit(scores.map { if (it.prompt == prompt) it.copy(prompt = to) else it })

    fun drop(prompt: String): String? = commit(scores.filter { it.prompt != prompt })

    private fun load() {
        val a = runCatching { JSONObject(file.readText()).getJSONArray("scores") }.getOrNull() ?: return
        scores = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::decode) }
            .distinctBy { it.entry to it.prompt }
    }

    private fun commit(next: List<BenchScore>): String? {
        if (next == scores) return null
        return try {
            write(next)
            scores = next
            null
        } catch (e: IOException) {
            "it could not be saved"
        }
    }

    private fun write(list: List<BenchScore>) {
        val o = JSONObject().put("scores", JSONArray().also { a -> list.forEach { a.put(encode(it)) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(o.toString(2).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
    }

    private fun encode(s: BenchScore) = JSONObject().put("entry", s.entry).put("prompt", s.prompt)
        .put("passed", s.passed).put("total", s.total).put("at", s.at)

    private fun decode(o: JSONObject): BenchScore? {
        val entry = o.optString("entry").takeIf { it.isNotEmpty() } ?: return null
        val prompt = o.optString("prompt").takeIf { it.isNotEmpty() } ?: return null
        return BenchScore(entry, prompt, o.optInt("passed"), o.optInt("total"), o.optLong("at"))
    }
}
