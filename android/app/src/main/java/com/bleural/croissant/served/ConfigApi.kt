package com.bleural.croissant.served

import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.Listing
import com.bleural.croissant.ask.Provider
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.ask.Providers
import com.bleural.croissant.ask.ProvidersError
import com.bleural.croissant.ask.redact
import com.bleural.croissant.data.AskCatalog
import com.bleural.croissant.data.Prompt
import com.bleural.croissant.data.Prompts
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.send.Response
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * What the phone's page edits on the device, through Core's own calls so the R1's pages see
 * each change (and a renamed prompt's scores follow it); behind this so the routing runs on
 * the JVM.
 */
interface Config {
    /** Every provider added, Ollama first, as reached now. */
    fun providers(): List<Provider>
    /** What [id] was added with; null for one not added. */
    fun spec(id: String): ProviderSpec?
    fun hint(id: String): String?
    fun hasKey(id: String): Boolean
    fun listing(id: String): Listing?
    fun saveProvider(id: String, spec: ProviderSpec): String?
    fun removeProvider(id: String): String?
    fun saveKey(id: String, key: String): String?
    /** [id]'s key, opened: for the export only. */
    fun key(id: String): String?
    /** Ask [id] for its models with its key and keep the list. */
    suspend fun list(id: String): Result<Listing>
    /**
     * [id] as [spec] with [key] (null: the one kept, while its address stays) asked for its
     * models: kept, with the key and the list, only if that works ([ProvidersError] for one
     * that cannot be, nothing kept).
     */
    suspend fun tryProvider(id: String, spec: ProviderSpec, key: String?): Result<Listing>
    /** What the server's machine is called (its name, else its address): where a provider on it is; null with no server. */
    fun place(): String?

    val prompts: Prompts
    fun addPrompt(name: String, from: String?): String?
    fun renamePrompt(name: String, to: String): String?
    fun savePrompt(name: String, text: String)
    fun deletePrompt(name: String): String?
    fun resetPrompt(name: String)
    fun pickPrompt(entry: String, name: String?)

    /** The model picked per slot, by its `provider:model` id. */
    val modelPicks: Map<ModelSlot, String>
    fun pickModel(slot: ModelSlot, id: String?)
}

/** A request refused: [status] and why, which is all the page is told. */
class Refused(val status: Int, why: String) : Exception(why)

/**
 * The page's JSON, under `/api/` (ConfigServer has checked the session):
 *
 *     GET    providers                 every provider with its state, the presets not added, whether keys are taken here, the way
 *     POST   providers                 {id, kind?, url?, title?, preset?}: one added
 *     POST   providers/{id}/try        {key?, url?, kind?, title?, preset?}: asked for its models first, kept only if it lists
 *     PUT    providers/{id}/address    {url}: its address (null or empty: its preset's); a new one drops its key
 *     PUT    providers/{id}/key        {key}: its key, kept sealed; only ever shown back by its last four
 *     POST   providers/{id}/list       its models asked of its API, as the R1's Test
 *     DELETE providers/{id}            gone, its key and its list with it
 *     GET    prompts                   the variants (no texts) and the pick per model
 *     POST   prompts                   {name, from?}: a new variant, empty or a copy
 *     GET    prompts/{name}            one variant with its text
 *     PUT    prompts/{name}            {text}: saved
 *     POST   prompts/{name}/rename     {to}
 *     POST   prompts/{name}/reset      a seed back to the catalog's text
 *     DELETE prompts/{name}
 *     GET    export                    the settings file (ConfigFile), keys in it only where [keys]
 *     POST   import                    a settings file, merged
 *
 * A change answers with what it changed, as GET would. A refusal is `{"error": why}`. No
 * answer carries a key but the export's, and no error carries what was sent. [keys]: whether
 * a key may come in or go out on the way this page is served (ConfigServer.KEYS_OVER_PLAIN_WIFI);
 * [way], that way, which the page warns about on Wi-Fi.
 */
class ConfigApi(private val config: Config, private val keys: Boolean, private val way: Way? = null) {

    companion object {
        const val JSON = "application/json; charset=utf-8"
        const val FILE_NAME = "croissant-settings.json"
        const val KEYS_WIFI = "keys travel over the tailnet only: turn Tailscale on and scan again"
    }

    /** [path] is what follows `/api/`. */
    fun handle(method: String, path: String, body: ByteArray): Response = try {
        route(method, path.substringBefore('?').substringBefore('#').trimEnd('/').split('/').map(::segment), body)
    } catch (e: Refused) {
        error(e.status, e.message.orEmpty())
    }

    private fun route(method: String, p: List<String>, body: ByteArray): Response {
        fun on(verb: String, vararg shape: String): List<String>? =
            if (verb != method || p.size != shape.size || shape.indices.any { shape[it] != "*" && shape[it] != p[it] }) null
            else shape.indices.filter { shape[it] == "*" }.map { p[it] }
        val json by lazy { parse(body) }

        on("GET", "providers")?.let { return ok(providers()) }
        on("POST", "providers")?.let { return add(json) }
        on("POST", "providers", "*", "try")?.let { (id) -> return tryOut(id, json) }
        on("PUT", "providers", "*", "address")?.let { (id) -> return address(id, json) }
        on("PUT", "providers", "*", "key")?.let { (id) -> return key(id, json) }
        on("POST", "providers", "*", "list")?.let { (id) -> return list(id) }
        on("DELETE", "providers", "*")?.let { (id) -> return remove(id) }
        on("GET", "prompts")?.let { return ok(prompts()) }
        on("POST", "prompts")?.let { return addPrompt(json) }
        on("GET", "prompts", "*")?.let { (n) -> return ok(prompt(n)) }
        on("PUT", "prompts", "*")?.let { (n) -> return savePrompt(n, json) }
        on("POST", "prompts", "*", "rename")?.let { (n) -> return rename(n, json) }
        on("POST", "prompts", "*", "reset")?.let { (n) -> prompt(n); config.resetPrompt(n); return ok(prompt(n)) }
        on("DELETE", "prompts", "*")?.let { (n) -> prompt(n); return done(config.deletePrompt(n)) { prompts() } }
        on("GET", "export")?.let { return export() }
        on("POST", "import")?.let { return ok(ConfigFile.apply(config, ConfigFile.read(json, keys))) }
        throw Refused(404, "there is nothing here")
    }

    // ---- providers and keys ----------------------------------------------------------

    private fun providers(): JSONObject {
        val added = config.providers()
        val presets = JSONArray()
        for ((id, p) in Providers.PRESETS) if (added.none { it.id == id })
            presets.put(JSONObject().put("id", id).put("title", p.title).put("kind", p.kind.wire).put("needsKey", p.needsKey).put("mac", p.mac))
        return JSONObject().put("keys", keys).put("way", way?.name?.lowercase() ?: JSONObject.NULL)
            .put("providers", JSONArray(added.map(::provider))).put("presets", presets)
    }

    /**
     * One provider as the page shows it: [Provider]'s own, its key by its last four, how many
     * models it listed per slot; [on] the server's machine by its name, for one that follows it.
     */
    private fun provider(p: Provider): JSONObject {
        val has = config.hasKey(p.id)
        val l = config.listing(p.id)
        return JSONObject().put("id", p.id).put("title", p.title).put("kind", p.kind.wire).put("url", p.url)
            .put("address", config.spec(p.id)?.url ?: JSONObject.NULL).put("preset", p.id in Providers.PRESETS)
            .put("mac", p.mac).put("on", (if (p.mac) config.place() else null) ?: JSONObject.NULL).put("needsKey", p.needsKey).put("hasKey", has).put("key", config.hint(p.id) ?: JSONObject.NULL)
            .put("ready", p.ready(has))
            .put("listed", l?.let { JSONObject().apply { for ((s, m) in it.models) put(s.key, m.size) } } ?: JSONObject.NULL)
            .put("free", l?.free == true)
    }

    private fun add(o: JSONObject): Response {
        val id = o.text("id") ?: throw Refused(400, "a provider needs an id")
        if (config.spec(id) != null) throw Refused(400, "$id is added already")
        val kind = o.text("kind")?.let { Kind.of(it) ?: throw Refused(400, "kind is one of ${Kind.entries.joinToString { k -> k.wire }}") }
        return done(config.saveProvider(id, ProviderSpec(kind, o.text("url"), o.text("title"), o.text("preset")))) { providers() }
    }

    /**
     * Tried before it is kept: [id] as the body has it (one not added yet, or one added with a
     * new address or key), asked for its models; kept, with the key and the list, only if that
     * works. The error is the provider's own, the key taken out.
     */
    private fun tryOut(id: String, o: JSONObject): Response {
        val key = o.text("key")?.trim()?.ifEmpty { null }
        if (key != null && !keys) throw Refused(403, KEYS_WIFI)
        val before = config.spec(id)
        val url = o.text("url")?.trim()?.ifEmpty { null }
        val spec = when {
            before == null -> ProviderSpec(o.text("kind")?.let { Kind.of(it) ?: throw Refused(400, "kind is one of ${Kind.entries.joinToString { k -> k.wire }}") },
                url, o.text("title"), o.text("preset"))
            o.has("url") -> before.copy(url = url)
            else -> before
        }
        val r = runBlocking { config.tryProvider(id, spec, key) }
        r.exceptionOrNull()?.let { e ->
            val kept = runCatching { config.key(id) }.getOrNull()
            val why = redact(redact(e.message ?: e.javaClass.simpleName, key), kept).take(300)
            throw Refused(if (e is ProvidersError) 400 else 502, why)
        }
        return ok(providers())
    }

    private fun address(id: String, o: JSONObject): Response {
        val spec = config.spec(id) ?: throw Refused(404, "there is no $id")
        return done(config.saveProvider(id, spec.copy(url = o.text("url")?.trim()?.ifEmpty { null }))) { providers() }
    }

    private fun key(id: String, o: JSONObject): Response {
        if (!keys) throw Refused(403, KEYS_WIFI)
        val k = o.text("key") ?: throw Refused(400, "there is no key in that")
        return done(config.saveKey(id, k)) { providers() }
    }

    /** Its API asked, as the R1's Test: the error is the provider's own, the key taken out (again, after Lister). */
    private fun list(id: String): Response {
        if (config.spec(id) == null) throw Refused(404, "there is no $id")
        val r = runBlocking { config.list(id) }
        r.exceptionOrNull()?.let { e ->
            val key = runCatching { config.key(id) }.getOrNull()
            throw Refused(502, redact(e.message ?: e.javaClass.simpleName, key).take(300))
        }
        return ok(providers())
    }

    private fun remove(id: String): Response {
        if (config.spec(id) == null) throw Refused(404, "there is no $id")
        return done(config.removeProvider(id)) { providers() }
    }

    // ---- prompts ------------------------------------------------------------------------

    private fun prompts(): JSONObject {
        val ps = config.prompts
        return JSONObject().put("prompts", JSONArray(ps.variants.map { head(it) })).put("picks", JSONObject(ps.picks))
    }

    private fun head(p: Prompt) = JSONObject().put("name", p.name).put("seed", p.seed ?: JSONObject.NULL)
        .put("edited", p.edited).put("default", p.name == AskCatalog.DEFAULT).put("chars", p.text.length)

    /** [name] with its text; [Refused] 404 when there is none. */
    private fun prompt(name: String): JSONObject = config.prompts[name]?.let { head(it).put("text", it.text) }
        ?: throw Refused(404, "there is no $name")

    private fun addPrompt(o: JSONObject): Response {
        val name = o.text("name") ?: throw Refused(400, "a prompt needs a name")
        val from = o.text("from")?.also { prompt(it) }
        return done(config.addPrompt(name, from)) { prompts() }
    }

    private fun savePrompt(name: String, o: JSONObject): Response {
        prompt(name)
        val text = o.text("text") ?: throw Refused(400, "there is no text in that")
        if (text.length > ConfigFile.TEXT_MAX) throw Refused(400, "a prompt is up to ${ConfigFile.TEXT_MAX} characters")
        config.savePrompt(name, text)
        return ok(prompt(name))
    }

    private fun rename(name: String, o: JSONObject): Response {
        prompt(name)
        return done(config.renamePrompt(name, o.text("to") ?: throw Refused(400, "a new name is needed"))) { prompts() }
    }

    // ---- the file -----------------------------------------------------------------------

    private fun export(): Response = Response(200, JSON, ConfigFile.export(config, keys).toString(2).toByteArray(),
        listOf("Content-Disposition" to "attachment; filename=\"$FILE_NAME\""))

    // ---- the wire -----------------------------------------------------------------------

    /** A path segment as it was sent (`encodeURIComponent`): a `+` is itself, not a space. */
    private fun segment(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw Refused(404, "there is nothing here")
    }

    /**
     * The body as an object. Never org.json's own message: Android's quotes the whole body
     * back, a key with it. org.json recurses once per bracket, so a body of nothing but
     * brackets runs out of stack: refused like any other that is not JSON.
     */
    private fun parse(body: ByteArray): JSONObject = try {
        if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
    } catch (e: JSONException) {
        throw Refused(400, "that is not JSON")
    } catch (e: StackOverflowError) {
        throw Refused(400, "that is not JSON")
    }

    private fun done(why: String?, then: () -> JSONObject): Response = if (why == null) ok(then()) else throw Refused(400, why)

    private fun ok(o: JSONObject) = Response(200, JSON, o.toString().toByteArray())

    private fun error(status: Int, why: String) = Response(status, JSON, JSONObject().put("error", why).toString().toByteArray())
}

/** [k] as text, null when it is missing or null; [Refused] when it is something else. */
internal fun JSONObject.text(k: String): String? {
    val v = opt(k)
    if (v == null || v == JSONObject.NULL) return null
    return v as? String ?: throw Refused(400, "$k is text")
}
