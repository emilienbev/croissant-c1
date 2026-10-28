package com.bleural.croissant.served

import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.ask.Providers
import com.bleural.croissant.ask.ProvidersError
import com.bleural.croissant.data.Prompt
import com.bleural.croissant.data.PromptsStore
import com.bleural.croissant.net.ModelSlot
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A settings file read and checked whole: what it would change, nothing changed yet. */
data class Contents(
    val providers: Map<String, ProviderSpec>,
    val keys: Map<String, String>,
    val prompts: List<Prompt>,
    val picks: Map<String, String>,
    val models: Map<ModelSlot, String>,
)

/**
 * One C1's settings as a file a phone saves and gives another C1's page: the providers with
 * their addresses, their keys (where keys may travel), Ask's prompts with their texts, the
 * prompt picked per model and the model picked per slot.
 *
 * ```
 * {"format": "croissant-settings", "version": 1,
 *  "providers": {"ollama": {}, "mybox": {"kind": "openai", "url": "http://...", "title": "My box"}},
 *  "keys": {"gemini": "..."},
 *  "prompts": [{"name": "default", "text": "...", "seed": "default", "edited": true}],
 *  "picks": {"ollama:qwen3.5:9b-mlx": "default"},
 *  "models": {"chat": "ollama:qwen3.5:9b-mlx"}}
 * ```
 *
 * Imported by merging: each provider, key and prompt by its name, the file's winning, and
 * nothing here deleted (a C1 set up from another keeps what it had of its own). A file is
 * checked whole before anything is changed, and refused if any of it is not ours: another
 * format or version, a field we do not write, a provider, key or name the R1 would refuse,
 * more of anything or longer than a C1 holds.
 */
object ConfigFile {
    const val FORMAT = "croissant-settings"
    const val VERSION = 1

    /** What a file may hold, past which it is not one of ours: far beyond any C1's own. */
    const val PROVIDERS_MAX = 50
    const val PROMPTS_MAX = 100
    const val PICKS_MAX = 200
    /** A prompt's text, here and on the page (ConfigApi): Ask's run to about 3,000. */
    const val TEXT_MAX = 32_000
    /** A provider's name, a model's `provider:model` id. */
    const val LINE_MAX = 200
    const val URL_MAX = 2_048

    private const val NOT_OURS = "that is not a Croissant settings file"
    private val TOP = setOf("format", "version", "providers", "keys", "prompts", "picks", "models")
    private val SPEC = setOf("kind", "url", "title", "preset")
    private val PROMPT = setOf("name", "text", "seed", "edited")

    /** This C1's settings, keys in them only when [keys]. Throws [Refused] when a kept key cannot be opened. */
    fun export(c: Config, keys: Boolean): JSONObject {
        val ps = c.prompts
        val providers = c.providers()
        val o = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("providers", JSONObject().apply { for (p in providers) put(p.id, spec(c.spec(p.id) ?: ProviderSpec())) })
        if (keys) o.put("keys", JSONObject().apply {
            for (p in providers) if (c.hasKey(p.id)) put(p.id, try {
                c.key(p.id)
            } catch (e: IOException) {
                throw Refused(500, e.message ?: "a key cannot be opened")
            })
        })
        return o.put("prompts", JSONArray(ps.variants.map { p ->
            JSONObject().put("name", p.name).put("text", p.text).apply { p.seed?.let { put("seed", it) } }.put("edited", p.edited)
        }))
            .put("picks", JSONObject(ps.picks))
            .put("models", JSONObject().apply { for ((slot, id) in c.modelPicks) put(slot.key, id) })
    }

    /** [o] checked whole. [keys]: whether keys may come in here; a file carrying one is refused where not. */
    fun read(o: JSONObject, keys: Boolean): Contents {
        if (o.opt("format") != FORMAT) throw Refused(400, NOT_OURS)
        if (o.opt("version") != VERSION) throw Refused(400, "that file is from another version of Croissant")
        only(o, TOP)

        val providers = LinkedHashMap<String, ProviderSpec>()
        obj(o, "providers")?.let { all ->
            if (all.length() > PROVIDERS_MAX) throw Refused(400, "a file holds up to $PROVIDERS_MAX providers")
            for (id in all.keys()) {
                val s = all.opt(id) as? JSONObject ?: throw Refused(400, NOT_OURS)
                only(s, SPEC)
                val kind = s.text("kind")?.let { Kind.of(it) ?: throw Refused(400, NOT_OURS) }
                val spec = ProviderSpec(kind, s.text("url"), s.text("title"), s.text("preset"))
                if ((spec.url?.length ?: 0) > URL_MAX || (spec.title?.length ?: 0) > LINE_MAX || (spec.preset?.length ?: 0) > LINE_MAX)
                    throw Refused(400, NOT_OURS)
                try {
                    Providers.resolve(id, spec, "")
                } catch (e: ProvidersError) {
                    throw Refused(400, e.message.orEmpty())
                }
                providers[id] = spec
            }
        }

        val given = LinkedHashMap<String, String>()
        obj(o, "keys")?.let { all ->
            if (all.length() > PROVIDERS_MAX) throw Refused(400, NOT_OURS)
            for (id in all.keys()) {
                val k = (all.opt(id) as? String)?.trim() ?: throw Refused(400, NOT_OURS)
                KeysStore.refused(k)?.let { throw Refused(400, "the key for $id: $it") }
                if (id !in providers) throw Refused(400, "a key for $id, a provider the file does not add")
                given[id] = k
            }
        }
        if (given.isNotEmpty() && !keys) throw Refused(403, ConfigApi.KEYS_WIFI)

        val prompts = mutableListOf<Prompt>()
        (o.opt("prompts") ?: JSONArray()).let { a ->
            if (a !is JSONArray) throw Refused(400, NOT_OURS)
            if (a.length() > PROMPTS_MAX) throw Refused(400, "a file holds up to $PROMPTS_MAX prompts")
            for (i in 0 until a.length()) {
                val p = a.opt(i) as? JSONObject ?: throw Refused(400, NOT_OURS)
                only(p, PROMPT)
                val name = p.text("name")?.trim() ?: throw Refused(400, NOT_OURS)
                PromptsStore.badName(name)?.let { throw Refused(400, it) }
                if (prompts.any { it.name.equals(name, ignoreCase = true) }) throw Refused(400, "the file has $name twice")
                val edited = p.opt("edited") ?: false
                if (edited !is Boolean) throw Refused(400, NOT_OURS)
                val text = p.text("text") ?: throw Refused(400, NOT_OURS)
                if (text.length > TEXT_MAX) throw Refused(400, "$name: a prompt is up to $TEXT_MAX characters")
                if ((p.text("seed")?.length ?: 0) > LINE_MAX) throw Refused(400, NOT_OURS)
                prompts += Prompt(name, text, p.text("seed"), edited)
            }
        }

        val picks = LinkedHashMap<String, String>()
        obj(o, "picks")?.let { all ->
            if (all.length() > PICKS_MAX) throw Refused(400, NOT_OURS)
            for (entry in all.keys()) {
                val name = all.opt(entry) as? String ?: throw Refused(400, NOT_OURS)
                if (entry.length > LINE_MAX) throw Refused(400, NOT_OURS)
                if (entry.isBlank() || prompts.none { it.name.equals(name, ignoreCase = true) })
                    throw Refused(400, "a pick of $name, a prompt the file does not have")
                picks[entry] = name
            }
        }

        val models = LinkedHashMap<ModelSlot, String>()
        obj(o, "models")?.let { all ->
            for (k in all.keys()) {
                val slot = ModelSlot.entries.firstOrNull { it.key == k } ?: throw Refused(400, NOT_OURS)
                models[slot] = (all.opt(k) as? String)?.takeIf { it.isNotBlank() && it.length <= LINE_MAX } ?: throw Refused(400, NOT_OURS)
            }
        }
        return Contents(providers, given, prompts, picks, models)
    }

    /**
     * [f] merged into [c]: the providers first (a new address drops a key), then their keys,
     * the prompts (an unedited seed here and in the file follows this C1's catalog), the
     * picks. What is the same already is left alone, so a provider's list is kept. What
     * changed, counted.
     */
    fun apply(c: Config, f: Contents): JSONObject {
        for ((id, spec) in f.providers) if (c.spec(id) != spec) c.saveProvider(id, spec)?.let { throw Refused(500, "$id: $it") }
        for ((id, k) in f.keys) if (runCatching { c.key(id) }.getOrNull() != k) c.saveKey(id, k)?.let { throw Refused(500, "the key for $id: $it") }
        fun here(name: String) = c.prompts.variants.firstOrNull { it.name.equals(name, ignoreCase = true) }
        for (p in f.prompts) {
            val h = here(p.name)
            when {
                h == null -> { c.addPrompt(p.name, null)?.let { throw Refused(500, it) }; c.savePrompt(p.name, p.text) }
                p.seed != null && !p.edited && h.seed == p.seed -> c.resetPrompt(h.name)
                else -> c.savePrompt(h.name, p.text)
            }
        }
        for ((entry, name) in f.picks) c.pickPrompt(entry, here(name)?.name ?: name)
        for ((slot, id) in f.models) c.pickModel(slot, id)
        return JSONObject().put("providers", f.providers.size).put("keys", f.keys.size).put("prompts", f.prompts.size)
            .put("picks", f.picks.size).put("models", f.models.size)
    }

    private fun spec(s: ProviderSpec) = JSONObject().apply {
        s.kind?.let { put("kind", it.wire) }
        s.url?.let { put("url", it) }
        s.title?.let { put("title", it) }
        s.preset?.let { put("preset", it) }
    }

    private fun obj(o: JSONObject, k: String): JSONObject? = when (val v = o.opt(k)) {
        null -> null
        is JSONObject -> v
        else -> throw Refused(400, NOT_OURS)
    }

    private fun only(o: JSONObject, allowed: Set<String>) {
        if (o.keys().asSequence().any { it !in allowed }) throw Refused(400, NOT_OURS)
    }
}
