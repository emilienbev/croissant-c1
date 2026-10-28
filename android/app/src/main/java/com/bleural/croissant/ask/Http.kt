package com.bleural.croissant.ask

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * How the C1 reaches a provider (the server's clients.py): one OkHttp client for all of
 * them, so its pool keeps a connection open between the calls of a hold, and a key that
 * goes only in a header. OkHttp writes a URL into its exceptions; a header it never does.
 */
object Http {
    /** As the server's clients: 60 s for a provider to answer. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Ollama: a model being loaded on the Mac says nothing until it is in memory (the server's waits for ever). */
    val slow: OkHttpClient by lazy { client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    private val JSON_TYPE = "application/json".toMediaType()

    fun json(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON_TYPE)
}

/**
 * A provider said no: [code] its HTTP status (0 for an error inside a stream), the message
 * its provider's own (key redacted), [body] what it answered, redacted too, for the rules
 * that read it (a refused parameter, a free tier's quota).
 */
class AskError(val code: Int, message: String, val body: String = "") : IOException(message) {
    companion object {
        /** What a refusal says: Ollama's `error`, OpenAI's and Gemini's `error.message`, else the body cut short. */
        fun of(r: Response, key: String?): AskError {
            val body = redact(runCatching { r.body.string() }.getOrDefault(""), key)
            return AskError(r.code, "${r.code}: " + said(body).ifEmpty { r.message.ifEmpty { "no answer" } }, body)
        }

        fun said(body: String): String {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return body.trim().take(300)
            val e = o.opt("error")
            return when (e) {
                is String -> e
                is JSONObject -> e.optString("message").ifEmpty { e.toString() }
                else -> o.optString("message").ifEmpty { body.trim() }
            }.take(300)
        }
    }
}

/** [text] with every [key] in it replaced: what any provider error passes through before it can reach a log or a screen. */
fun redact(text: String, key: String?): String = if (key.isNullOrEmpty()) text else text.replace(key, "[key]")

/**
 * One request on [this], read by [read] while it is open. Cancelling the coroutine cancels
 * the call, so a hold given up stops reading at once rather than at the next byte. An
 * exception that would carry the key loses it.
 */
internal suspend fun <T> OkHttpClient.exchange(request: Request, key: String?, read: (Response) -> T): T = coroutineScope {
    val call = newCall(request)
    // Undispatched, so it is already waiting when the call starts, however busy the caller's thread.
    val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
    try {
        withContext(Dispatchers.IO) {
            try {
                call.execute().use(read)
            } catch (e: JSONException) {
                // A line that is not the JSON it should be: org.json quotes the whole of it, so it is cut and redacted.
                throw AskError(0, "an unreadable answer (" + redact(e.message.orEmpty(), key).take(120) + ")")
            } catch (e: IOException) {
                // A call cancelled with its coroutine is that cancellation, not an error of its own.
                ensureActive()
                if (e !is AskError && key != null && e.message?.contains(key) == true) throw AskError(0, redact(e.message.orEmpty(), key))
                throw e
            }
        }
    } finally {
        watch.cancel()
    }
}

/** Whether OpenAI's SDK (`max_retries=1`) makes a call again after this status: 408, 409, 429, 5xx. */
internal fun retriable(code: Int): Boolean = code == 408 || code == 409 || code == 429 || code >= 500

/**
 * [call] as OpenAI's SDK made it for the server: one that fails before it answers (no
 * connection, a [retriable] status) is made once more, after half a second. Not after a
 * time-out when [afterTimeout] is false (a picture: a slow one may be made, and billed,
 * even so), the client's own or a gateway's (504). Gemini's SDK never retried, so its calls
 * do not come through here.
 */
internal suspend fun <T> retriedOnce(afterTimeout: Boolean = true, call: suspend () -> T): T = try {
    call()
} catch (e: AskError) {
    if (!retriable(e.code) || (!afterTimeout && e.code == 504)) throw e
    delay(OpenAiChat.RETRY_MS)
    call()
} catch (e: InterruptedIOException) {
    if (!afterTimeout) throw e
    delay(OpenAiChat.RETRY_MS)
    call()
} catch (e: IOException) {
    delay(OpenAiChat.RETRY_MS)
    call()
}

/** [read] on a success, else the provider's refusal. */
internal fun <T> Response.ok(key: String?, read: (Response) -> T): T = if (isSuccessful) read(this) else throw AskError.of(this, key)

/**
 * [k]'s text, "" when it is missing or JSON null. Not `optString`: Android's org.json gives
 * "null" for a JSON null, and OpenAI streams `"content": null` beside every tool call.
 */
internal fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** A key in OpenAI's header ("none" for a server that takes any). */
internal fun Request.Builder.bearer(key: String?): Request.Builder = keyed { header("Authorization", "Bearer " + (key ?: "none")) }

/** A key in Gemini's header. */
internal fun Request.Builder.googKey(key: String): Request.Builder = keyed { header("x-goog-api-key", key) }

/**
 * OkHttp refuses a header value it cannot send and quotes the whole value in why (all but
 * `Authorization`'s): a key that slipped past [KeysStore]'s check is said without it.
 */
private inline fun Request.Builder.keyed(put: Request.Builder.() -> Request.Builder): Request.Builder = try {
    put()
} catch (_: IllegalArgumentException) {
    throw AskError(0, "the key has a character that cannot be sent: type it again")
}
