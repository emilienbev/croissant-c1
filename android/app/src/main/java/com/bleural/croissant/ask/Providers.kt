package com.bleural.croissant.ask

import com.bleural.croissant.net.ModelSlot
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

/**
 * What a provider's API speaks (the server's providers.py and chat.py): Ollama's own API, as
 * the measured holds were run; any OpenAI-compatible endpoint; Gemini's own API (its OpenAI
 * layer has no transcription and no picture edits).
 */
enum class Kind(val wire: String) {
    Ollama("ollama"), OpenAi("openai"), Gemini("gemini");

    companion object {
        fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A provider the C1 knows by name: its [kind], its [url] (null for the kind's own: Ollama at
 * the server's host, Google's API), whether it is nothing without a key, and [prices] its
 * name at models.dev. [mac]: it runs on the server's machine, so a loopback address in it
 * means the server's host, never the R1 itself.
 */
data class Preset(val kind: Kind, val url: String?, val title: String, val needsKey: Boolean,
                  val prices: String? = null, val mac: Boolean = false)

/**
 * One provider as a call reaches it: [url] where (empty while it has nowhere to go: Ollama
 * before a server is set), [needsKey] whether it answers without one.
 */
data class Provider(val id: String, val kind: Kind, val url: String, val title: String,
                    val needsKey: Boolean = false, val prices: String? = null, val mac: Boolean = false) {
    /** Whether it can be called, given whether a key is kept for it. */
    fun ready(hasKey: Boolean): Boolean = url.isNotEmpty() && (hasKey || !needsKey)
}

/** What the file holds of a provider (no key: those are in keys.json): any of these may be left to its preset. */
data class ProviderSpec(val kind: Kind? = null, val url: String? = null, val title: String? = null, val preset: String? = null)

/** One model at one provider, as a hold runs it: [id] `provider:model`, as the device picks it. */
data class Entry(val id: String, val name: String, val provider: Provider, val model: String)

class ProvidersError(message: String) : IllegalArgumentException(message)

object Providers {
    const val OLLAMA_PORT = 11434
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"

    /** The server's presets less `local` (this machine: the C1 runs no model of its own), in its order. */
    val PRESETS: Map<String, Preset> = linkedMapOf(
        "ollama" to Preset(Kind.Ollama, null, "Ollama", needsKey = false, mac = true),
        "lmstudio" to Preset(Kind.OpenAi, "http://127.0.0.1:1234/v1", "LM Studio", needsKey = false, mac = true),
        "openai" to Preset(Kind.OpenAi, "https://api.openai.com/v1", "OpenAI", true, "openai"),
        "gemini" to Preset(Kind.Gemini, null, "Google Gemini", true, "google"),
        "groq" to Preset(Kind.OpenAi, "https://api.groq.com/openai/v1", "Groq", true, "groq"),
        "openrouter" to Preset(Kind.OpenAi, "https://openrouter.ai/api/v1", "OpenRouter", true, "openrouter"),
        "mistral" to Preset(Kind.OpenAi, "https://api.mistral.ai/v1", "Mistral", true, "mistral"),
        "together" to Preset(Kind.OpenAi, "https://api.together.xyz/v1", "Together", true, "togetherai"),
        "deepseek" to Preset(Kind.OpenAi, "https://api.deepseek.com/v1", "DeepSeek", true, "deepseek"),
    )

    private val ID = Regex("^[A-Za-z0-9._:/-]{1,80}$")

    /**
     * [id] as [spec] says, its preset filling the rest (`_provider` on the server), [host] the
     * server's: where a provider on the Mac is. A provider of one's own is an OpenAI-compatible
     * URL, a key optional. Throws [ProvidersError] for one that cannot be.
     */
    fun resolve(id: String, spec: ProviderSpec, host: String): Provider {
        if (!ID.matches(id)) throw ProvidersError("provider '$id': an id is letters, digits and ._:-")
        val preset = PRESETS[id] ?: spec.preset?.let { PRESETS[it] }
        val kind = spec.kind ?: preset?.kind ?: throw ProvidersError("provider '$id': kind must be one of ${Kind.entries.joinToString { it.wire }}")
        val given = spec.url?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null && !given.startsWith("http://") && !given.startsWith("https://"))
            throw ProvidersError("provider '$id': an address starts with http:// or https://")
        val url = given ?: when {
            kind == Kind.Ollama -> if (host.isBlank()) "" else "http://${bracket(host)}:$OLLAMA_PORT"
            kind == Kind.Gemini -> GEMINI_URL
            else -> preset?.url?.let { if (preset.mac) onHost(it, host) else it }
        } ?: throw ProvidersError("provider '$id': an OpenAI-compatible provider needs a url")
        // A hosted preset is nothing without its key; a server of one's own may need none.
        return Provider(id, kind, url.trimEnd('/'), spec.title?.takeIf { it.isNotBlank() } ?: preset?.title ?: id,
            needsKey = kind == Kind.Gemini || preset?.needsKey == true, prices = preset?.prices, mac = preset?.mac == true && given == null)
    }

    /**
     * [url] with a host only the server's machine can reach (loopback, any) swapped for [host]
     * (the server's `boxes._reachable`); empty while no server is set, rather than the R1.
     */
    fun onHost(url: String, host: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return url
        val h = u.host?.removePrefix("[")?.removeSuffix("]") ?: return url
        val local = h == "localhost" || h == "::1" || h == "::" || h == "0.0.0.0" || h.startsWith("127.")
        if (!local) return url
        if (host.isBlank()) return ""
        return URI(u.scheme, u.userInfo, host, u.port, u.path, u.query, u.fragment).toString()
    }

    private fun bracket(host: String) = if (':' in host) "[$host]" else host

    /** A model by its last part: a hosted model without its maker, a path's folder. */
    fun short(model: String): String = model.trimEnd('/').substringAfterLast('/')

    fun entry(p: Provider, model: String): Entry = Entry("${p.id}:$model", short(model), p, model)

    /**
     * What may be picked for [slot], grouped by provider in [ready]'s order: each one's models
     * as it last listed them ([listed], by provider id).
     */
    fun entries(slot: ModelSlot, ready: List<Provider>, listed: (String) -> Listing?): List<Entry> =
        ready.flatMap { p -> listed(p.id)?.models?.get(slot).orEmpty().map { entry(p, it) } }

    /** `provider:model` as the device picks it, for any of [providers]; null for one it does not have. */
    fun find(id: String?, providers: List<Provider>): Entry? {
        val pid = id?.substringBefore(':', "") ?: return null
        val model = id.substringAfter(':', "")
        val p = providers.firstOrNull { it.id == pid } ?: return null
        return if (model.isEmpty()) null else entry(p, model)
    }
}

/**
 * The providers this C1 reaches (providers.json in the device-protected files, beside
 * keys.json), without their keys: which are added and, where it is not their preset's,
 * their address and name. Ollama is always there, at the server's host unless it is given
 * another. A change that cannot be written is not made (the reason comes back).
 */
class ProvidersStore(private val file: File) {

    var specs: Map<String, ProviderSpec> = emptyMap()
        private set

    init {
        load()
    }

    /** Every provider, Ollama first, as reached with the server at [host]; one that can no longer be is left out. */
    fun providers(host: String): List<Provider> = specs.mapNotNull { (id, s) -> runCatching { Providers.resolve(id, s, host) }.getOrNull() }

    /** Add [id], or change it: null when kept, else why not. */
    @Synchronized
    fun save(id: String, spec: ProviderSpec): String? {
        try {
            Providers.resolve(id, spec, "")
        } catch (e: ProvidersError) {
            return e.message
        }
        return commit(specs + (id to spec))
    }

    /** [id] gone; Ollama only goes back to its preset. */
    @Synchronized
    fun remove(id: String): String? = commit(if (id == OLLAMA) specs + (id to ProviderSpec()) else specs - id)

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()?.optJSONObject("providers")
        val read = o?.keys()?.asSequence()?.mapNotNull { id ->
            o.optJSONObject(id)?.let { s ->
                id to ProviderSpec(Kind.of(s.optString("kind")), s.text("url"), s.text("title"), s.text("preset"))
            }
        }?.toMap().orEmpty()
        specs = mapOf(OLLAMA to (read[OLLAMA] ?: ProviderSpec())) + read
    }

    private fun JSONObject.text(k: String): String? = optString(k).takeIf { has(k) && !isNull(k) && it.isNotEmpty() }

    private fun commit(s: Map<String, ProviderSpec>): String? = try {
        val o = JSONObject()
        for ((id, p) in s) o.put(id, JSONObject().apply {
            p.kind?.let { put("kind", it.wire) }
            p.url?.let { put("url", it) }
            p.title?.let { put("title", it) }
            p.preset?.let { put("preset", it) }
        })
        writeAtomically(file, JSONObject().put("providers", o).toString(2))
        specs = s
        null
    } catch (e: IOException) {
        "it could not be saved"
    }

    companion object {
        const val OLLAMA = "ollama"
    }
}

/** [text] as [file], whole or not at all (temp + rename), as the other stores write. */
internal fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    file.parentFile?.mkdirs()
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
}
