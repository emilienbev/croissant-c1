package com.bleural.croissant.ask

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One model's rates in dollars per 1M tokens (null where models.dev gives none), its name,
 * whether it thinks, whether it is deprecated; and its context and output limits in tokens
 * and whether it reads pictures, as models.dev gives them.
 */
data class Price(
    val name: String, val input: Double? = null, val output: Double? = null, val cached: Double? = null,
    val audio: Double? = null, val thinks: Boolean = false, val deprecated: Boolean = false,
    val context: Long? = null, val maxOut: Long? = null, val images: Boolean = false,
) {
    /**
     * What [c] comes to: tokens read (of them cached), seconds heard (live words' passes
     * billed alike), tokens written, pictures made. Null when a count it has needs a rate the
     * model has none for. Seconds and pictures become tokens at Gemini's counts
     * ([Prices.AUDIO_TOKENS_PER_S], [Prices.IMAGE_TOKENS]), near enough for any provider.
     */
    fun cost(c: Counts): Double? {
        val heard = (c.secs + c.live) * Prices.AUDIO_TOKENS_PER_S
        val written = c.out + c.pictures * Prices.IMAGE_TOKENS
        val parts = listOf(c.input - c.cached to input, c.cached to (cached ?: input), heard to (audio ?: input), written to output)
        if (parts.any { (n, rate) -> n != 0.0 && rate == null }) return null
        return parts.filter { it.first != 0.0 }.sumOf { (n, rate) -> n * rate!! } / 1e6
    }
}

/**
 * What a model costs: models.dev's catalogue, the one source of prices (the server's
 * prices.py). Dollars per 1M tokens as the provider bills them; a model it does not know has
 * no price, and the device says nothing about one.
 *
 * The catalogue (about 5 MB) is fetched when the copy kept in [file] (prices.json in the
 * device-protected files) is a day old or missing, read as it streams in, and only the
 * presets' providers are kept of it (about 100 KB). [log] gets what the server logged.
 */
class Prices(private val file: File?, private val clock: () -> Long = System::currentTimeMillis, private val log: (String) -> Unit = {}) {

    @Volatile var bySource: Map<String, Map<String, Price>> = emptyMap()
        private set

    private val lock = Mutex()

    init {
        try {
            if (file != null && file.isFile) bySource = kept(JSONObject(file.readText()))
        } catch (e: Exception) {
            log("could not read ${file?.name}: ${e.message}")
        }
    }

    fun get(p: Provider, model: String): Price? = bySource[p.prices.orEmpty()]?.get(model)

    /** Fetch the catalogue when the kept copy is a day old or missing; offline, the kept copy or no prices. */
    suspend fun refresh(http: OkHttpClient = Http.client) = lock.withLock {
        if (file != null && file.isFile && clock() - file.lastModified() < MAX_AGE_MS) return@withLock
        val fetched = try {
            http.exchange(Request.Builder().url(URL).build(), null) { r -> r.ok(null) { read(it.body.source()) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("could not fetch prices from models.dev: ${e.message}")
            return@withLock
        }
        bySource = fetched
        log("prices from models.dev for ${fetched.values.sumOf { it.size }} models")
        try {
            if (file != null) writeAtomically(file, write(fetched).toString() + "\n")
        } catch (e: IOException) {
            log("could not keep the prices: ${e.message}")
        }
    }

    companion object {
        const val URL = "https://models.dev/api.json"
        const val MAX_AGE_MS = 24 * 3600 * 1000L

        // Gemini's counts, used as an approximation for every provider: a second of audio is 32
        // tokens heard, a picture about 1,290 tokens written.
        const val AUDIO_TOKENS_PER_S = 32
        const val IMAGE_TOKENS = 1290

        /** The presets' names at models.dev: what is kept of the catalogue. */
        val SOURCES: Set<String> = Providers.PRESETS.values.mapNotNull { it.prices }.toSet()

        /**
         * The catalogue's providers in [keep], by model, read off [source] as it comes: the top
         * object's other values are skipped byte by byte, never held, and only a kept
         * provider's value is parsed. A provider the catalogue lacks has no models.
         */
        fun read(source: BufferedSource, keep: Set<String> = SOURCES): Map<String, Map<String, Price>> {
            val out = HashMap<String, Map<String, Price>>()
            if (source.nonBlank() != OPEN) throw IOException("the catalogue is not an object")
            while (true) {
                when (source.nonBlank()) {
                    CLOSE -> break
                    COMMA -> continue
                    QUOTE -> {
                        val name = source.key()
                        if (source.nonBlank() != COLON) throw IOException("the catalogue is not an object")
                        if (name in keep) {
                            val o = JSONObject(Buffer().also { source.value(it) }.readUtf8())
                            val models = o.optJSONObject("models")
                            out[name] = models?.keys()?.asSequence()?.associateWith { price(models.optJSONObject(it) ?: JSONObject()) }.orEmpty()
                        } else source.value(null)
                    }
                    else -> throw IOException("the catalogue is not an object")
                }
            }
            return keep.associateWith { out[it].orEmpty() }
        }

        /** One model as models.dev describes it (`_price`). */
        fun price(d: JSONObject): Price {
            val cost = d.optJSONObject("cost") ?: JSONObject()
            val limit = d.optJSONObject("limit") ?: JSONObject()
            val name = listOf(d.opt("name"), d.opt("id")).firstOrNull(::pyTruthy)?.toString().orEmpty()
            val input = d.optJSONObject("modalities")?.optJSONArray("input")
            return Price(name, num(cost, "input"), num(cost, "output"), num(cost, "cache_read"), num(cost, "input_audio"),
                thinks = pyTruthy(d.opt("reasoning")), deprecated = d.opt("status") == "deprecated",
                context = tokens(limit, "context"), maxOut = tokens(limit, "output"),
                images = input != null && (0 until input.length()).any { input.opt(it) == "image" })
        }

        private fun num(o: JSONObject, k: String): Double? = (o.opt(k) as? Number)?.toDouble()

        /** A limit as models.dev gives it: a positive count, else unknown. */
        private fun tokens(o: JSONObject, k: String): Long? = num(o, k)?.takeIf { it > 0 }?.toLong()

        // The kept copy: by source, by model, the fields as the server's asdict writes them.
        private fun kept(o: JSONObject): Map<String, Map<String, Price>> = o.keys().asSequence().associateWith { s ->
            val models = o.getJSONObject(s)
            models.keys().asSequence().associateWith { m ->
                val p = models.getJSONObject(m)
                Price(p.optString("name"), num(p, "input"), num(p, "output"), num(p, "cached"), num(p, "audio"),
                    p.optBoolean("thinks"), p.optBoolean("deprecated"), num(p, "context")?.toLong(), num(p, "max_out")?.toLong(),
                    p.optBoolean("images"))
            }
        }

        private fun write(by: Map<String, Map<String, Price>>): JSONObject = JSONObject().apply {
            for ((s, models) in by) put(s, JSONObject().apply {
                for ((m, p) in models) put(m, JSONObject().put("name", p.name).put("input", p.input.orNull()).put("output", p.output.orNull())
                    .put("cached", p.cached.orNull()).put("audio", p.audio.orNull()).put("thinks", p.thinks)
                    .put("deprecated", p.deprecated).put("context", p.context.orNull()).put("max_out", p.maxOut.orNull())
                    .put("images", p.images))
            })
        }

        private fun Any?.orNull(): Any = this ?: JSONObject.NULL

        private const val OPEN = '{'.code.toByte()
        private const val CLOSE = '}'.code.toByte()
        private const val OPEN_LIST = '['.code.toByte()
        private const val CLOSE_LIST = ']'.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val ESCAPE = '\\'.code.toByte()
        private const val COMMA = ','.code.toByte()
        private const val COLON = ':'.code.toByte()

        private fun blank(b: Byte) = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()

        /** The next byte that is not whitespace. */
        private fun BufferedSource.nonBlank(): Byte {
            while (true) readByte().let { if (!blank(it)) return it }
        }

        /** A key, its opening quote read: its bytes as they are, escapes and all (a preset's name has none). */
        private fun BufferedSource.key(): String {
            val b = Buffer()
            while (true) {
                val c = readByte()
                if (c == QUOTE) return b.readUtf8()
                b.writeByte(c.toInt())
                if (c == ESCAPE) b.writeByte(readByte().toInt())
            }
        }

        /**
         * One value, copied into [into] when given: an object or an array to its closing
         * bracket, a string to its closing quote (brackets and quotes inside strings, escaped
         * or not, are the string's), anything else up to the comma or bracket after it.
         */
        private fun BufferedSource.value(into: Buffer?) {
            val first = nonBlank()
            into?.writeByte(first.toInt())
            if (first != OPEN && first != OPEN_LIST && first != QUOTE) {
                while (request(1) && buffer[0].let { it != COMMA && it != CLOSE && it != CLOSE_LIST && !blank(it) }) {
                    val b = readByte()
                    into?.writeByte(b.toInt())
                }
                return
            }
            var depth = if (first == QUOTE) 0 else 1
            var quoted = first == QUOTE
            while (true) {
                val b = readByte()
                into?.writeByte(b.toInt())
                if (quoted) {
                    if (b == ESCAPE) {
                        val c = readByte()
                        into?.writeByte(c.toInt())
                    } else if (b == QUOTE) {
                        quoted = false
                        if (depth == 0) return
                    }
                } else when (b) {
                    QUOTE -> quoted = true
                    OPEN, OPEN_LIST -> depth++
                    CLOSE, CLOSE_LIST -> if (--depth == 0) return
                }
            }
        }
    }
}
