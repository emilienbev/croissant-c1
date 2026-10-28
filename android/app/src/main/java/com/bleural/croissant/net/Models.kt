package com.bleural.croissant.net

import com.bleural.croissant.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the server can run, per slot, and which one the device picks.
 * The server keeps the providers and their keys (`server/providers.json`, edited on its
 * own page, `/settings`) and lists every model they offer for each slot, grouped by
 * provider; the device only ever sees names and ids, asks for the list with
 * `GET /providers`, and sends its pick per slot with every hold (`Protocol.start`).
 */
enum class ModelSlot(val key: String) {
    Chat("chat"),
    Stt("stt"),
    TextToImage("text_to_image"),
    ImageToImage("image_to_image"),
}

/**
 * One model the server offers, by its `provider:model` [id]: [provider] is its provider's
 * name ("Ollama", "Google Gemini"), [local] whether it runs on the server's own machine,
 * [free] whether its key is on the provider's free tier. [partials] is for speech: whether
 * its words come in while the button is held by default (on locally), or only at release.
 *
 * What it costs, where the server knows: [title] its name
 * as its maker writes it, [rates] dollars per 1M tokens, [unit] dollars for an hour of
 * speech or one picture, [month] a month at your pace (the last 30 days of the slot's
 * use at this model's rates; none under a week of use), [thinks] whether it thinks before
 * it answers, [bytes] what it takes in the server's memory. On a free tier: [today] the
 * requests made since its day began (its limit is a number a day), [resets] the time zone
 * whose midnight begins that day ("America/Los_Angeles"), [shares] whether the provider
 * may use what is sent.
 */
data class ModelEntry(val id: String, val name: String, val provider: String, val local: Boolean,
                      val partials: Boolean = false, val free: Boolean = false,
                      val title: String? = null, val rates: Rates? = null, val unit: Double? = null,
                      val month: Double? = null, val thinks: Boolean? = null, val bytes: Long? = null,
                      val today: Int? = null, val resets: String? = null, val shares: Boolean = false) {
    /** What a card calls it. */
    val label: String get() = title ?: name
    /** Costs nothing: on the server's own machine, or a free-tier key. */
    val costsNothing: Boolean get() = local || free
}

/** Dollars per 1M tokens read, written, and read from the provider's cache. */
data class Rates(val input: Double?, val output: Double?, val cached: Double?)

data class ModelList(val default: String?, val entries: List<ModelEntry>) {
    /** The entry a hold gets: the picked one while the server still has it, else its default. */
    fun resolve(picked: String?): ModelEntry? =
        entries.firstOrNull { it.id == picked } ?: entries.firstOrNull { it.id == default }
}

/** [tools]: the Ask tools the server runs itself (`image_generate`, `web_search`, `tape_ask`), which a hold offers only then. */
data class ServerModels(val slots: Map<ModelSlot, ModelList>, val tools: Set<String> = emptySet()) {
    fun list(slot: ModelSlot): ModelList = slots[slot] ?: ModelList(null, emptyList())
}

object Models {
    /** The server's list, or an exception when it cannot be reached. Blocking: call off the main thread. */
    fun fetch(server: Prefs.Server): ServerModels {
        val c = URL("${server.httpUrl}/providers").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try {
            if (c.responseCode != 200) throw java.io.IOException("the server answered ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun parse(json: String): ServerModels {
        val o = JSONObject(json)
        val tools = o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        return ServerModels(tools = tools, slots = ModelSlot.entries.associateWith { slot ->
            val s = o.optJSONObject(slot.key) ?: return@associateWith ModelList(null, emptyList())
            val a = s.optJSONArray("entries")
            ModelList(s.optString("default").takeIf { it.isNotEmpty() && !s.isNull("default") },
                List(a?.length() ?: 0) { i ->
                    a!!.getJSONObject(i).let {
                        ModelEntry(it.getString("id"), it.optString("name"), it.optString("provider"),
                            it.optBoolean("local"), it.optBoolean("partials"), it.optBoolean("free"),
                            it.optStringOrNull("title"),
                            it.optJSONObject("rates")?.let { r ->
                                Rates(r.optDoubleOrNull("in"), r.optDoubleOrNull("out"), r.optDoubleOrNull("cached"))
                            },
                            it.optDoubleOrNull("unit"), it.optDoubleOrNull("month"),
                            if (it.has("thinks")) it.optBoolean("thinks") else null,
                            if (it.has("bytes")) it.optLong("bytes") else null,
                            if (it.has("today")) it.optInt("today") else null,
                            it.optStringOrNull("resets"), it.optBoolean("shares"))
                    }
                })
        })
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
