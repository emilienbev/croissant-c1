package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One of Ask's prompt variants: [seed] the catalog's seed it came from, null for one made on
 * the device; [edited] whether its text has left the seed's, which stops it following the
 * catalog.
 */
data class Prompt(val name: String, val text: String, val seed: String? = null, val edited: Boolean = false)

/** Every variant, "default" first, and the one picked per chat model (`provider:model` to a name). */
data class Prompts(val variants: List<Prompt>, val picks: Map<String, String>) {
    operator fun get(name: String): Prompt? = variants.firstOrNull { it.name == name }

    /** What a model gets with no pick: the variant seeded from [suggested] (the catalog's seed for it), else the default. */
    fun byDefault(suggested: String): String = variants.firstOrNull { it.seed == suggested }?.name ?: AskCatalog.DEFAULT

    /** What a hold on [entry] gets: its pick, else [byDefault]. */
    fun resolve(entry: String?, suggested: String): String = entry?.let { picks[it] }?.takeIf { get(it) != null } ?: byDefault(suggested)
}

/**
 * Ask's prompt variants on this device: prompts.json in the device-protected files, so a
 * hold before unlock has them, rewritten whole (temp + rename) on every change.
 *
 * The catalog's seeds arrive once each ([seeded]), named as the seed or numbered when the
 * name is taken; a seed the user deleted never comes back. "default" is always there,
 * first, under that name: its text can change, it is never renamed or deleted, and it is
 * what a model gets with no pick and no suggestion. An unedited seed takes the catalog's
 * text at every load, so a re-measured seed reaches devices with the app; once edited it
 * is left alone until [reset]. A wording only holds for the model it was measured on (the
 * same sentence has called a tool 8/8 on one Qwen and 2/8 on another), hence a pick per
 * model. A missing or corrupt file starts again from the seeds. A change that cannot be
 * written is not made (the reason comes back where a call returns one).
 */
class PromptsStore(private val file: File, private val catalog: AskCatalog) {

    var prompts: Prompts = Prompts(emptyList(), emptyMap())
        private set
    private var seeded: Set<String> = emptySet()

    init {
        load()
    }

    fun pick(entry: String, name: String?) {
        commit(prompts.copy(picks = if (name == null || prompts[name] == null) prompts.picks - entry else prompts.picks + (entry to name)))
    }

    /** A new variant, [from]'s text or empty: null when made, else why not. */
    fun add(name: String, from: String? = null): String? {
        val n = name.trim()
        refused(n)?.let { return it }
        return commit(prompts.copy(variants = prompts.variants + Prompt(n, from?.let { prompts[it]?.text }.orEmpty())))
    }

    /** Picks follow the new name, and so does what the variant was seeded from. */
    fun rename(name: String, to: String): String? {
        if (name == AskCatalog.DEFAULT) return "the default keeps its name"
        prompts[name] ?: return "there is no $name"
        val n = to.trim()
        if (n == name) return null
        refused(n, except = name)?.let { return it }
        return commit(Prompts(prompts.variants.map { if (it.name == name) it.copy(name = n) else it },
            prompts.picks.mapValues { (_, v) -> if (v == name) n else v }))
    }

    /** Line endings become `\n`. A seed's text other than the catalog's is edited, and stays as it is. */
    fun save(name: String, text: String) {
        val p = prompts[name] ?: return
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        update(p.copy(text = t, edited = p.seed != null && t != catalog.prompts[p.seed]))
    }

    /** The models that picked it go back to what they get by default. */
    fun delete(name: String): String? {
        if (name == AskCatalog.DEFAULT) return "the default cannot be deleted"
        prompts[name] ?: return "there is no $name"
        return commit(Prompts(prompts.variants.filter { it.name != name }, prompts.picks.filterValues { it != name }))
    }

    /** A seed back to the catalog's text, following it again. */
    fun reset(name: String) {
        val p = prompts[name] ?: return
        val text = p.seed?.let { catalog.prompts[it] } ?: return
        update(p.copy(text = text, edited = false))
    }

    private fun update(p: Prompt) = commit(prompts.copy(variants = prompts.variants.map { if (it.name == p.name) p else it }))

    private fun refused(name: String, except: String? = null): String? = badName(name)
        ?: "there is a $name already".takeIf { prompts.variants.any { it.name != except && it.name.equals(name, ignoreCase = true) } }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        val read = o?.optJSONArray("variants")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::decode) }
        }.orEmpty().distinctBy { it.name.lowercase() }
        val readSeeded = o?.optJSONArray("seeded")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        val readPicks = o?.optJSONObject("picks")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty()

        // The default first, always under its name and as its seed.
        val d = AskCatalog.DEFAULT
        val default = read.firstOrNull { it.name == d }?.copy(seed = d) ?: Prompt(d, catalog.prompts[d].orEmpty(), d)
        var variants = listOf(default) + read.filter { it.name != d }
        var seeds = readSeeded + d
        for ((seed, text) in catalog.prompts) if (seed !in seeds) {
            variants = variants + Prompt(free(seed, variants), text, seed)
            seeds = seeds + seed
        }
        variants = variants.map { p -> p.seed?.takeIf { !p.edited }?.let { catalog.prompts[it] }?.let { p.copy(text = it) } ?: p }
        prompts = Prompts(variants, readPicks.filterValues { n -> variants.any { it.name == n } })
        seeded = seeds
        // Not written, the seeds still serve; the next change that is written catches the file up.
        if (prompts.variants != read || prompts.picks != readPicks || seeded != readSeeded) runCatching { write(prompts) }
    }

    /** [seed], or "[seed] 2", "[seed] 3"... when a variant already has the name. */
    private fun free(seed: String, variants: List<Prompt>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) seed else "$seed $it" }
            .first { n -> variants.none { it.name.equals(n, ignoreCase = true) } }

    /** The file first, then the state, so the two never part: null when written, else why not. */
    private fun commit(p: Prompts): String? = try {
        write(p)
        prompts = p
        null
    } catch (e: IOException) {
        "it could not be saved"
    }

    private fun write(p: Prompts) {
        val o = JSONObject()
            .put("variants", JSONArray().also { a -> p.variants.forEach { a.put(encode(it)) } })
            .put("picks", JSONObject(p.picks))
            .put("seeded", JSONArray(seeded.toList()))
        val tmp = File(file.parentFile, file.name + ".tmp")
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(o.toString(2).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
    }

    private fun encode(p: Prompt) = JSONObject().put("name", p.name)
        .apply { if (p.seed != null) put("seed", p.seed) }
        .put("text", p.text)
        .apply { if (p.edited) put("edited", true) }

    private fun decode(o: JSONObject): Prompt? {
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return Prompt(name, o.optString("text"), o.optString("seed").takeIf { it.isNotEmpty() }, o.optBoolean("edited"))
    }

    companion object {
        const val NAME_MAX = 24

        /** Why [name] (trimmed) cannot name a variant, whatever the others are called; null when it can. */
        fun badName(name: String): String? = when {
            name.isEmpty() -> "a prompt needs a name"
            name.length > NAME_MAX -> "a name is up to $NAME_MAX characters"
            '\n' in name || '\r' in name -> "a name is one line"
            else -> null
        }
    }
}
