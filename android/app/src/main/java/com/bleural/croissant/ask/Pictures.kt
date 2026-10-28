package com.bleural.croissant.ask

import com.bleural.croissant.data.GeneratedStore
import com.bleural.croissant.data.JsonStore
import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A picture made at a provider (the server's pictures.py); a local model's stay on the Mac.
 *
 * - **OpenAI-compatible:** `/images/generations` from words, `/images/edits` from a photo.
 * - **Gemini:** an Imagen model (`imagen-...`) through `:predict`, from words only; any other
 *   through `:generateContent` asking for an image back, which is also how a photo is edited.
 *
 * As the server's SDKs sent them: a new picture at [width] x [height] (768 x 1024, the R1's
 * 3:4) or that aspect for Gemini, an edit at its photo's own shape; base64 asked for. A
 * provider that refuses the size or the format gets the request again without it. What
 * comes back is the provider's own image (PNG, JPEG, WebP); [Shelf] keeps it as a JPEG. No
 * Android here: the JPEG is made by the caller's function.
 */
class CloudPictures(
    private val provider: Provider,
    val model: String,
    private val key: String?,
    private val http: OkHttpClient = Http.client,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    private val log: (String) -> Unit = {},
) {
    /** The picture [prompt] describes, from nothing or, given [source] (a JPEG), made from that photo. */
    suspend fun render(prompt: String, source: ByteArray? = null): ByteArray = when (provider.kind) {
        Kind.OpenAi -> openAi(prompt, source)
        Kind.Gemini -> gemini(prompt, source)
        Kind.Ollama -> throw IllegalArgumentException("${provider.id}:$model: an ${provider.kind.wire} provider cannot make pictures")
    }

    // ---- OpenAI-compatible -----------------------------------------------------------------

    private suspend fun openAi(prompt: String, source: ByteArray?): ByteArray {
        val params = linkedMapOf<String, Any>("model" to model, "prompt" to prompt, "n" to 1)
        // An edit follows its photo's shape; a new picture is the configured size.
        if (source == null) params["size"] = "${width}x$height"
        params["response_format"] = "b64_json"
        while (true) {
            try {
                // Not again after a time-out: a slow picture may be made, and billed, all the same.
                val r = retriedOnce(afterTimeout = false) { http.exchange(openAiRequest(params, source), key) { it.ok(key) { x -> JSONObject(x.body.string()) } } }
                return openAiPicture(r)
            } catch (e: AskError) {
                // gpt-image models refuse response_format (they always answer in base64) and
                // take three sizes: a refusal that names one drops it and asks again.
                val said = e.message.orEmpty() + e.body
                val drop = listOf("response_format", "size").firstOrNull { it in params && it in said }
                if ((e.code != 400 && e.code != 422) || drop == null) throw e
                log("${provider.title} ($model) refuses $drop=${params[drop]}; asking without it")
                params.remove(drop)
            }
        }
    }

    fun openAiRequest(params: Map<String, Any>, source: ByteArray?): Request {
        val b = Request.Builder().bearer(key)
        if (source == null) return b.url("${provider.url}/images/generations").post(Http.json(JSONObject(params))).build()
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "photo.jpg", source.toRequestBody(JPEG))
            .apply { params.forEach { (k, v) -> addFormDataPart(k, v.toString()) } }
            .build()
        return b.url("${provider.url}/images/edits").post(form).build()
    }

    /** The first picture: its base64, else its address fetched (no key: the provider signs it). */
    private suspend fun openAiPicture(r: JSONObject): ByteArray {
        val item = r.optJSONArray("data")?.optJSONObject(0) ?: throw IOException("the provider sent no picture")
        item.str("b64_json").takeIf { it.isNotEmpty() }?.let { return Base64.getMimeDecoder().decode(it) }
        val url = item.str("url").takeIf { it.isNotEmpty() } ?: throw IOException("the provider sent no picture")
        // The address is signed: said in no error, which goes on the shelf and in the log.
        val got = try {
            http.exchange(Request.Builder().url(url).build(), key) { it.takeIf { r -> r.isSuccessful }?.body?.bytes() }
        } catch (e: IOException) {
            null
        }
        return got ?: throw IOException("the picture could not be fetched from ${provider.title}")
    }

    // ---- Gemini ----------------------------------------------------------------------------

    private suspend fun gemini(prompt: String, source: ByteArray?): ByteArray {
        val key = key ?: throw AskError(0, "${provider.title} has no key")
        val imagen = model.startsWith("imagen")
        if (imagen && source != null) throw IllegalArgumentException("${provider.id}:$model: Imagen makes pictures from words only")
        val body = if (imagen) imagenBody(prompt) else geminiBody(prompt, source)
        val req = Request.Builder().url("${provider.url}/${Lister.model(model)}:${if (imagen) "predict" else "generateContent"}")
            .googKey(key).post(Http.json(body)).build()
        val r = http.exchange(req, key) { it.ok(key) { x -> JSONObject(x.body.string()) } }
        return if (imagen) imagenPicture(r) else geminiPicture(r)
    }

    fun imagenBody(prompt: String): JSONObject = JSONObject()
        .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
        .put("parameters", JSONObject().put("sampleCount", 1).put("aspectRatio", aspect(width, height)))

    fun geminiBody(prompt: String, source: ByteArray?): JSONObject {
        val parts = JSONArray()
        source?.let { parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.getEncoder().encodeToString(it)))) }
        parts.put(JSONObject().put("text", prompt))
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")).apply {
                // An edit keeps its photo's shape; a new picture is the configured one.
                if (source == null) put("imageConfig", JSONObject().put("aspectRatio", aspect(width, height)))
            })
    }

    private fun imagenPicture(r: JSONObject): ByteArray {
        val data = r.optJSONArray("predictions")?.optJSONObject(0)?.str("bytesBase64Encoded").orEmpty()
        if (data.isEmpty()) throw IOException("Imagen sent no picture (refused by its filter?)")
        return Base64.getMimeDecoder().decode(data)
    }

    private fun geminiPicture(r: JSONObject): ByteArray {
        val cands = r.optJSONArray("candidates") ?: JSONArray()
        for (i in 0 until cands.length()) {
            val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val data = parts.optJSONObject(j)?.optJSONObject("inlineData")?.str("data").orEmpty()
                if (data.isNotEmpty()) return Base64.getMimeDecoder().decode(data)
            }
        }
        val said = GeminiSpeech.text(r).trim()
        throw IOException("Gemini sent no picture" + if (said.isNotEmpty()) ": " + said.take(200) else "")
    }

    companion object {
        /** The server's `image_width` and `image_height`: the R1's 3:4. */
        const val WIDTH = 768
        const val HEIGHT = 1024

        private val JPEG = "image/jpeg".toMediaType()

        /** 768 x 1024 as Gemini takes it: "3:4". */
        fun aspect(width: Int, height: Int): String {
            var a = width
            var b = height
            while (b != 0) a = b.also { b = a % b }
            return "${width / a}:${height / a}"
        }

        /**
         * Whether a picture model wants the Transform's description pass first (the server's
         * `Generations.wants_description`): a local one, Z-Image at guidance 0, where an
         * instruction barely pulls and a description of the finished picture lands; never a
         * provider's editor, which is made for instructions.
         */
        fun wantsDescription(local: Boolean): Boolean = local
    }
}

/**
 * The provider's pictures kept on the shelf (Photos > Generated), as the server's come there
 * through net/Generations.kt: the row is written in [store] before the request goes, marked as
 * the C1's (`Generated.c1`, nothing to fetch from the server), so a picture the provider is
 * making is one the shelf already shows as being made; the JPEG lands beside it, or the
 * reason it will not. Each is made in [scope], side by side, and
 * counted in [ledger] once it lands; one stopped meanwhile is dropped and not counted, as
 * the server drops it. [toJpeg] turns the provider's image (PNG, JPEG, WebP) into the JPEG
 * the shelf keeps, the server's at quality 92 (the caller's: Android's decoder and encoder);
 * [log] never gets the prompt.
 */
class Shelf(
    private val store: GeneratedStore,
    private val scope: CoroutineScope,
    private val ledger: Ledger,
    private val toJpeg: (ByteArray) -> ByteArray,
    private val log: (String) -> Unit = {},
    private val nanos: () -> Long = System::nanoTime,
) {
    private val making = ConcurrentHashMap<String, Job>()

    /** Held while a picture lands or is stopped, so the two cannot cross. */
    private val lock = Any()

    /**
     * [prompt] started on [pictures] ([entry] its `provider:model`), from [photo] (a JPEG) for
     * a transform, [named] the photo the row points at; the id back at once ([id] the
     * device's when it made one, as for Try again). It writes the row: off the main thread.
     */
    fun start(pictures: CloudPictures, entry: String, prompt: String, photo: ByteArray? = null, named: String? = null,
              id: String = JsonStore.newId("g")): String {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                val t0 = nanos()
                val made = try {
                    toJpeg(pictures.render(prompt, photo))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (making[id] !== self) return@launch
                        log("generation $id with $entry failed: ${e.message}")
                        store.fail(id, e.message ?: e.javaClass.simpleName)
                    }
                    return@launch
                }
                synchronized(lock) {
                    if (making[id] !== self) return@launch
                    store.save(id, made)
                    ledger.add(if (photo != null) ModelSlot.ImageToImage else ModelSlot.TextToImage, entry, Counts(pictures = 1.0))
                }
                log("generated $id with $entry in %.1fs, ${made.size / 1024} kB".format(Locale.ROOT, (nanos() - t0) / 1e9))
            } finally {
                making.remove(id, self)
            }
        }
        // Known as being made here before its row exists, so nothing reads the row as the server's.
        making[id] = job
        try {
            store.add(id, prompt, source = named, c1 = true)
        } catch (e: Exception) {
            making.remove(id, job)
            job.cancel()
            throw e
        }
        job.start()
        return id
    }

    /** Whether [id] is being made here, not on the server: nothing to fetch for it there. */
    fun has(id: String): Boolean = making.containsKey(id)

    /** Stop [id] (the owner stopped it or threw it away): the request dropped wherever it is, its row gone. False for one not made here. */
    fun stop(id: String): Boolean {
        val job = synchronized(lock) { making.remove(id)?.also { store.delete(id) } } ?: return false
        job.cancel()
        log("stopped $id")
        return true
    }

    /** Until each picture being made has landed or failed. */
    suspend fun join() = making.values.toList().forEach { it.join() }
}
