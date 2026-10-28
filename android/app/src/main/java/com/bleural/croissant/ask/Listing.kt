package com.bleural.croissant.ask

import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What a provider offers the key it was asked with: its models by slot, whether that key is
 * on its free tier, and for Ollama what each model takes in memory and which think; [at]
 * when it was asked (ms since the epoch).
 *
 * A free tier's limit is requests a day: [resets] is the time zone whose midnight starts its
 * day (an IANA name), and [shares] says the provider may use what is sent.
 */
data class Listing(
    val models: Map<ModelSlot, List<String>>,
    val free: Boolean = false,
    val sizes: Map<String, Long> = emptyMap(),
    val thinking: List<String> = emptyList(),
    val resets: String? = null,
    val shares: Boolean = false,
    val at: Long = 0,
)

/**
 * Each provider's own list of models, sorted into the device's four slots, so a pick is only
 * offered what can do the job (the server's catalog.py, rule for rule).
 *
 * A list is sorted by what the API says where it says it: Ollama's capabilities, Gemini's
 * supported actions, OpenRouter's modalities and parameters, Mistral's capability flags,
 * Together's type. OpenAI, Groq, DeepSeek and LM Studio list ids alone, so there it is the
 * name. Chat needs tools: the tool loop is how a hold sets a timer or searches. Gemini says
 * what a model is called for, not what comes out of it, so its names finish the job
 * (`-image`, `-tts`, `-transcribe`).
 *
 * A listing also says whether the key it was asked with is on the provider's free tier,
 * where the provider lets that be seen: Gemini does; OpenAI's free tokens do not show
 * through its API.
 */
object Lister {

    /** Ask [p] what it offers [key]. Throws what the provider says when it refuses ([AskError], key redacted). */
    suspend fun list(p: Provider, key: String?, http: OkHttpClient = Http.client): Listing = when {
        p.url.isEmpty() -> throw AskError(0, "${p.title} has no address yet")
        p.kind == Kind.Ollama -> ollama(p, http)
        p.kind == Kind.OpenAi -> openAi(p, key, http)
        else -> gemini(p, key ?: throw AskError(0, "${p.title} has no key"), http)
    }

    /** Each model under every slot it can serve, without the twins of another listed. */
    fun bySlot(found: Map<String, Set<ModelSlot>>): Map<ModelSlot, List<String>> =
        ModelSlot.entries.associateWith { slot -> twinless(found.filterValues { slot in it }.keys.sorted()) }

    // ---- Ollama ----------------------------------------------------------------------

    private suspend fun ollama(p: Provider, http: OkHttpClient): Listing = coroutineScope {
        val tags = getJson(http, Request.Builder().url("${p.url}/api/tags"), null)
        val listed = tags.optJSONArray("models").objects().filter { it.str("model").isNotEmpty() }
        val caps = listed.map { m ->
            async {
                val name = m.str("model")
                val shown = getJson(http, Request.Builder().url("${p.url}/api/show").post(Http.json(JSONObject().put("model", name))), null)
                name to shown.optJSONArray("capabilities").strings().toSet()
            }
        }.awaitAll().toMap()
        Listing(bySlot(caps.mapValues { (_, c) -> if (c.containsAll(listOf("completion", "tools"))) setOf(ModelSlot.Chat) else emptySet() }),
            sizes = listed.mapNotNull { m -> m.optLong("size").takeIf { it > 0 }?.let { m.str("model") to it } }.toMap(),
            thinking = caps.filterValues { "thinking" in it }.keys.sorted())
    }

    // ---- OpenAI-compatible -------------------------------------------------------------

    private suspend fun openAi(p: Provider, key: String?, http: OkHttpClient): Listing {
        val text = http.exchange(Request.Builder().url("${p.url}/models").bearer(key).build(), key) { r -> r.ok(key) { it.body.string() } }
        // {"data": [...]}, or the bare list a few servers answer.
        val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: runCatching { JSONArray(text) }.getOrNull()
            ?: throw AskError(0, "${p.title} listed no models")
        return Listing(bySlot(data.objects().filter { it.str("id").isNotEmpty() }.associate { it.getString("id") to openAi(it.getString("id"), it) }))
    }

    // Together's type, where it gives one.
    private val TOGETHER = mapOf("chat" to setOf(ModelSlot.Chat), "image" to setOf(ModelSlot.TextToImage), "transcribe" to setOf(ModelSlot.Stt))
    // Named for something other than chat with tools on /chat/completions.
    private val OPENAI_NOT = Regex("embed|tts|moderation|realtime|audio|search|sora|davinci|babbage|guard|" +
        "rerank|computer-use|codex|ocr|^chatgpt-|chat-latest|turbo-instruct")
    private val PICTURES = setOf(ModelSlot.TextToImage, ModelSlot.ImageToImage)

    fun openAi(model: String, d: JSONObject): Set<ModelSlot> {
        val n = model.lowercase()
        val arch = d.optJSONObject("architecture")
        if (arch != null) {                 // OpenRouter: chat only (no /images, no /audio there)
            val textOut = arch.optJSONArray("output_modalities").strings() == listOf("text")
            return if (textOut && "tools" in d.optJSONArray("supported_parameters").strings()) setOf(ModelSlot.Chat) else emptySet()
        }
        // Mistral before Together: a Mistral model also has a `type` ("base"), its capabilities say more.
        val caps = d.optJSONObject("capabilities")
        if (caps != null && caps.has("completion_chat")) {                     // Mistral
            return (if (caps.optBoolean("completion_chat") && caps.optBoolean("function_calling")) setOf(ModelSlot.Chat) else emptySet()) +
                (if ("voxtral" in n) setOf(ModelSlot.Stt) else emptySet())
        }
        (d.opt("type") as? String)?.let { return TOGETHER[it].orEmpty() }   // Together
        // The rest say nothing but the name.
        if ("realtime" !in n && Regex("whisper|transcri|voxtral").containsMatchIn(n)) return setOf(ModelSlot.Stt)
        if (Regex("gpt-image|dall-e-2").containsMatchIn(n)) return PICTURES
        if ("dall-e" in n) return setOf(ModelSlot.TextToImage)
        if ("image" in n || OPENAI_NOT.containsMatchIn(n)) return emptySet()
        return setOf(ModelSlot.Chat)
    }

    // ---- Gemini ------------------------------------------------------------------------

    private suspend fun gemini(p: Provider, key: String, http: OkHttpClient): Listing {
        val found = LinkedHashMap<String, Set<ModelSlot>>()
        var page: String? = null
        do {
            val url = "${p.url}/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "1000")
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
            val o = getJson(http, Request.Builder().url(url).googKey(key), key)
            for (m in o.optJSONArray("models").objects()) {
                val name = m.str("name").removePrefix("models/").takeIf { it.isNotEmpty() } ?: continue
                found[name] = gemini(name, m.optJSONArray("supportedGenerationMethods").strings())
            }
            page = o.str("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        val usable = geminiOpen(p, key, http, found.filterValues { it.isNotEmpty() }.keys.toList())
        val models = bySlot(found.filterKeys { it in usable })
        val free = geminiFree(p, key, http, models[ModelSlot.Chat].orEmpty())
        // Gemini's free tier: a cap a day per model, back at midnight Pacific; Google may use what is sent.
        return Listing(models, free = free, resets = if (free) "America/Los_Angeles" else null, shares = free)
    }

    // Called with generateContent, but not for an answer in words; or an alias (`-latest`) of a model listed by its own name.
    private val GEMINI_NOT = Regex("tts|lyria|embedding|computer-use|robotics|deep-research|antigravity|live|native-audio|aqa|-latest$")

    fun gemini(name: String, actions: List<String>): Set<ModelSlot> {
        val n = name.lowercase()
        if (n.startsWith("imagen") && "predict" in actions) return setOf(ModelSlot.TextToImage)   // words only
        if ("generateContent" !in actions || GEMINI_NOT.containsMatchIn(n)) return emptySet()
        if ("image" in n || "nano-banana" in n) return PICTURES
        if ("transcribe" in n) return setOf(ModelSlot.Stt)
        // A Gemini hears audio as well as it reads; Gemma does not.
        return if (n.startsWith("gemini")) setOf(ModelSlot.Chat, ModelSlot.Stt) else setOf(ModelSlot.Chat)
    }

    /**
     * The models this key may call. Gemini lists some it then refuses to new keys ("no longer
     * available to new users", a 404); counting a word's tokens, which is free, says so
     * without running the model.
     */
    private suspend fun geminiOpen(p: Provider, key: String, http: OkHttpClient, names: List<String>): Set<String> = coroutineScope {
        val gate = Semaphore(8)
        names.map { name ->
            async {
                gate.withPermit {
                    val req = Request.Builder().url("${p.url}/${model(name)}:countTokens").googKey(key).post(Http.json(hi())).build()
                    name to http.exchange(req, key) { r -> r.code == 404 }
                }
            }
        }.awaitAll().filter { !it.second }.map { it.first }.toSet()
    }

    // A free key is refused a Pro model outright: Google's 429 names a quota of its free tier
    // ("...PerModel-FreeTier", "free_tier_..."), its limit 0. A paid key is answered, for a
    // fraction of a cent. A Pro model closed to new keys (404) or failing (5xx) passes to the
    // next; the newest first, its plain name before its variants. A Pro preview can take a
    // minute to say 504, so it has PROBE_MS in all, after which it cannot tell: not free.
    private val FREE_TIER = Regex("free.?tier", RegexOption.IGNORE_CASE)
    private val PLAIN_PRO = Regex("-pro(?:-preview)?$")
    const val PROBE_MS = 10_000L

    /** Whether [key] is on Gemini's free tier, from the first Pro model in [chat] that answers within [budget] ms. */
    suspend fun geminiFree(p: Provider, key: String, http: OkHttpClient, chat: List<String>, budget: Long = PROBE_MS): Boolean {
        val newest = chat.filter { "-pro" in it }.sortedDescending().sortedBy { !PLAIN_PRO.containsMatchIn(it) }
        val body = hi().put("generationConfig", JSONObject().put("maxOutputTokens", 16))
        return withTimeoutOrNull(budget) {
            for (model in newest) {
                val req = Request.Builder().url("${p.url}/${model(model)}:generateContent").googKey(key).post(Http.json(body)).build()
                val said = try {
                    http.exchange(req, key) { r -> if (r.isSuccessful) null else AskError.of(r, key) }
                } catch (e: IOException) {
                    // A timeout of its own, the network, or the call cancelled as the budget ran out
                    // (its I/O error can come before the timeout is seen): cannot tell.
                    return@withTimeoutOrNull false
                } ?: return@withTimeoutOrNull false
                if (said.code != 404 && said.code < 500) return@withTimeoutOrNull said.code == 429 && FREE_TIER.containsMatchIn(said.body)
            }
            false
        } ?: false
    }

    /** As Google's SDK names a model in a path: `models/` first unless it is there. */
    fun model(name: String): String = if (name.startsWith("models/") || name.startsWith("tunedModels/")) name else "models/$name"

    private fun hi() = JSONObject().put("contents", JSONArray().put(
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "hi"))).put("role", "user")))

    // ---- twins -------------------------------------------------------------------------

    // A dated snapshot, or a preview, of a model listed by its plain name too
    // (gpt-4o-2024-08-06 and gemini-3.1-flash-lite-preview beside gpt-4o and gemini-3.1-flash-lite).
    private val TWIN = Regex("^(.+?)(?:-preview)?(?:-(\\d{4}-\\d{2}-\\d{2}|\\d{4}|\\d{2}-\\d{4}))?$")

    /** Without the twins ([TWIN]) of a model also listed by its plain name. */
    fun twinless(names: List<String>): List<String> {
        val have = names.toSet()
        return names.filter { n -> TWIN.matchEntire(n)?.groupValues?.get(1)?.let { it != n && it in have } != true }
    }

    // ---- plumbing ----------------------------------------------------------------------

    private suspend fun getJson(http: OkHttpClient, req: Request.Builder, key: String?): JSONObject =
        http.exchange(req.build(), key) { r -> r.ok(key) { JSONObject(it.body.string()) } }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) "" else optString(it) }
}


/**
 * What each provider last listed (listings.json in the device-protected files), by provider
 * id. [VERSION] is bumped when [Lister] lists differently, so a file from before is asked
 * again rather than read. Nothing in it is secret.
 */
class ListingsStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    var listed: Map<String, Listing> = emptyMap()
        private set

    init {
        listed = runCatching { read(JSONObject(file.readText())) }.getOrDefault(emptyMap())
    }

    /** What [id]'s API just listed, kept with the time: the listing as kept. */
    @Synchronized
    fun keep(id: String, listing: Listing): Listing = listing.copy(at = clock()).also { write(listed + (id to it)) }

    /** [id] gone (removed, or its key changed): its list goes with it. */
    @Synchronized
    fun drop(id: String) {
        if (id in listed) write(listed - id)
    }

    /** Whether [id]'s list is older than [ms], or missing. */
    fun stale(id: String, ms: Long): Boolean = listed[id]?.let { clock() - it.at > ms } ?: true

    private fun write(l: Map<String, Listing>) {
        val o = JSONObject()
        for ((id, v) in l) o.put(id, JSONObject()
            .put("models", JSONObject().also { m -> v.models.forEach { (s, names) -> m.put(s.key, JSONArray(names)) } })
            .put("free", v.free).put("sizes", JSONObject(v.sizes)).put("thinking", JSONArray(v.thinking))
            .put("resets", v.resets ?: JSONObject.NULL).put("shares", v.shares).put("at", v.at))
        try {
            writeAtomically(file, JSONObject().put("version", VERSION).put("listed", o).toString(1))
        } catch (_: IOException) {
            // Still served from memory; asked again after a restart.
        }
        listed = l
    }

    private fun read(o: JSONObject): Map<String, Listing> {
        if (o.optInt("version") != VERSION) return emptyMap()
        val l = o.optJSONObject("listed") ?: return emptyMap()
        return l.keys().asSequence().associateWith { id ->
            val v = l.getJSONObject(id)
            val m = v.optJSONObject("models")
            val sizes = v.optJSONObject("sizes")
            Listing(
                ModelSlot.entries.associateWith { s -> m?.optJSONArray(s.key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() },
                v.optBoolean("free"),
                sizes?.keys()?.asSequence()?.associateWith { sizes.getLong(it) }.orEmpty(),
                v.optJSONArray("thinking")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                v.optString("resets").takeIf { !v.isNull("resets") && it.isNotEmpty() },
                v.optBoolean("shares"),
                v.optLong("at"),
            )
        }
    }

    companion object {
        const val VERSION = 1

        /** A list this old is asked for again when a model page opens; Ollama's is asked every time. */
        const val MAX_AGE_MS = 24 * 3600 * 1000L
    }
}
