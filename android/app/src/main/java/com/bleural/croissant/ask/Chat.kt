package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.blackholeSink
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * One chat call, whoever runs the model (server/tools/c1_chat.py is its Python copy): Ollama's own API, any
 * OpenAI-compatible endpoint, or Gemini's own API. The tool loop ([Loop]) talks in [Msg] and
 * gets back text as it comes and one [Reply]; each client turns that into what its API wants.
 *
 * What each client sends by default is what the measured holds were run with: temperature
 * 0, so a tool call is decoded the same way every time, and no reasoning stream on a
 * push-to-talk device. A client can be given otherwise (`temperature`, `reasoning`), and an
 * OpenAI-compatible model that refuses one of the two gets it dropped, once, with a line in
 * the log, rather than failing every hold.
 */
interface ChatApi {
    val model: String

    /** [text] gets each piece of the answer as it comes (on the calling thread or an IO one); the end is one [Reply]. [tools] none when null. */
    suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit = {}): Reply

    /** The whole text of one call, with its counts. */
    suspend fun complete(messages: List<Msg>): Pair<String, Usage?> {
        val out = StringBuilder()
        val reply = stream(messages, null) { out.append(it) }
        return out.toString() to reply.usage
    }
}

/** A tool call: [args] as the model gave them, [id] the provider's, echoed back with its result ("" where it gives none). */
data class Call(val name: String, val args: JSONObject, val id: String = "")

/**
 * One message: [role] system, user, assistant or tool. [images] JPEGs, on a user message;
 * [calls] on an assistant message; [call] on a tool message, the call it answers, and
 * [value] its result unflattened. [raw] on an assistant message: the client's own record of
 * the turn, sent back as it came (Gemini's parts carry thought signatures that must go back
 * untouched).
 */
data class Msg(
    val role: String,
    val text: String = "",
    val images: List<ByteArray> = emptyList(),
    val calls: List<Call> = emptyList(),
    val call: Call? = null,
    val value: Any? = null,
    val raw: JSONArray? = null,
)

/** One model call's counts: the prompt it read and what it wrote, with the seconds; [cachedTokens] of the prompt, read from the provider's cache. */
data class Usage(val promptTokens: Int, val promptS: Double, val outTokens: Int, val outS: Double, val cachedTokens: Int = 0)

/** What a streamed call ends with, after its text. */
data class Reply(val calls: List<Call>, val raw: JSONArray? = null, val usage: Usage? = null)

object Chat {
    /**
     * The client for [model] at [p], [key] its key (null where it needs none). One per model
     * and kept, as the server keeps one per entry: an OpenAI-compatible client remembers what
     * its model refused. [http] is [Http]'s unless a test gives its own; [log] gets the lines
     * the server logged (a refusal, an odd finish), never a key.
     */
    fun of(p: Provider, model: String, key: String?, http: OkHttpClient? = null, log: (String) -> Unit = {}): ChatApi = when (p.kind) {
        Kind.Ollama -> OllamaChat(p, model, http ?: Http.slow)
        Kind.OpenAi -> OpenAiChat(p, model, key, http ?: Http.client, log = log)
        Kind.Gemini -> GeminiChat(p, model, key, http ?: Http.client, log = log)
    }

    /** A tool's spec as Ollama and OpenAI take it in `tools`; Gemini's is made from the same three fields. */
    fun spec(t: ToolSpec): JSONObject = JSONObject().put("type", "function").put("function", t.toJson())

    /**
     * One cheap request to [p] at a hold's press (Ollama's version, a provider's base URL), so
     * the pool holds an open connection, TLS done, when the hold's words come. No key goes;
     * the answer is thrown away, and a failure says nothing: the hold's own call will.
     */
    fun warm(p: Provider, http: OkHttpClient = Http.client) {
        val url = if (p.kind == Kind.Ollama) "${p.url}/api/version" else p.url
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: Response) = response.close()
        })
    }
}

/**
 * Timings for an API that reports tokens but not seconds: to the first output, then to the
 * end (Ollama times itself).
 */
internal class Clock(private val now: () -> Long = System::nanoTime) {
    private val t0 = now()
    private var first: Long? = null

    fun mark() {
        if (first == null) first = now()
    }

    fun usage(promptTokens: Int, outTokens: Int, cachedTokens: Int = 0): Usage {
        val end = now()
        val f = first ?: end
        return Usage(promptTokens, (f - t0) / 1e9, outTokens, (end - f) / 1e9, cachedTokens)
    }
}

/** Ollama's stream: one JSON object a line. */
internal fun BufferedSource.ndjson(each: (JSONObject) -> Unit) {
    while (true) {
        val line = readUtf8Line() ?: return
        if (line.isNotBlank()) each(JSONObject(line))
    }
}

/**
 * Server-sent events, as OpenAI and Gemini stream: each event's `data` lines joined by a
 * newline, handed over at the blank line that ends it (or at the end); comments (`:`) and
 * the other fields skipped. [each] returns false to stop reading.
 */
internal fun BufferedSource.sse(each: (String) -> Boolean) {
    val data = StringBuilder()
    var has = false
    while (true) {
        val line = readUtf8Line()
        if (line == null || line.isEmpty()) {
            if (has && !each(data.toString())) return
            data.setLength(0); has = false
            if (line == null) return
            continue
        }
        if (line.startsWith("data:")) {
            if (has) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
            has = true
        }
    }
}

/**
 * What is left after the last event (`[DONE]`, then the stream's end), read so an HTTP/1.1
 * connection (LM Studio, one's own server) goes back to the pool; a second at most, for a
 * server that keeps the stream open.
 */
internal fun BufferedSource.drain() {
    timeout().deadline(1, TimeUnit.SECONDS)
    try {
        readAll(blackholeSink())
    } catch (_: IOException) {
        // Closed with the response: that connection is not kept.
    }
}

/**
 * [v] as Python's `json.dumps(v, ensure_ascii=False)` writes it: ", " and ": " between, only
 * quotes, backslashes and control characters escaped, and a whole number without a point:
 * deliberately not Python's `2.0`, since the device's results reached the server through
 * Android's org.json, which writes 2.0 as 2. What
 * the server put in a tool message and in an OpenAI call's arguments, so a round's prompt is
 * the same bytes, and the same tokens, as when the loop ran there.
 */
fun pyJson(v: Any?): String = StringBuilder().also { pyJson(it, v) }.toString()

/** Whether Python takes [v], a JSON value, for true: not null, false, zero, "" or an empty array or object. */
fun pyTruthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    is JSONArray -> v.length() > 0
    is JSONObject -> v.length() > 0
    else -> true
}

private fun pyJson(out: StringBuilder, v: Any?) {
    when (v) {
        null, JSONObject.NULL -> out.append("null")
        is Boolean -> out.append(if (v) "true" else "false")
        is String -> pyString(out, v)
        is Int, is Long, is Short, is Byte, is BigInteger -> out.append(v.toString())
        is Number -> out.append(pyNumber(v.toDouble()))
        is JSONObject -> {
            out.append('{')
            var first = true
            for (k in v.keys()) {
                if (!first) out.append(", ")
                first = false
                pyString(out, k); out.append(": "); pyJson(out, v.opt(k))
            }
            out.append('}')
        }
        is JSONArray -> {
            out.append('[')
            for (i in 0 until v.length()) {
                if (i > 0) out.append(", ")
                pyJson(out, v.opt(i))
            }
            out.append(']')
        }
        is Map<*, *> -> pyJson(out, JSONObject(v))
        is Collection<*> -> pyJson(out, JSONArray(v))
        else -> pyString(out, v.toString())
    }
}

private fun pyString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c == '\b' -> out.append("\\b")
        c == '\u000c' -> out.append("\\f")
        c < ' ' -> out.append("\\u%04x".format(c.code))
        else -> out.append(c)
    }
    out.append('"')
}

/** A whole number as one; else Python's `repr`: plain digits, or an exponent below 1e-4 ("1e-05"). */
private fun pyNumber(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == Math.rint(d) && Math.abs(d) < 9.2e18) return d.toLong().toString()
    val b = BigDecimal(d.toString()).stripTrailingZeros()
    val exp = b.precision() - b.scale() - 1
    if (exp >= -4) return b.toPlainString()
    val digits = b.unscaledValue().abs().toString()
    val mantissa = digits.take(1) + if (digits.length > 1) "." + digits.drop(1) else ""
    return (if (d < 0) "-" else "") + mantissa + "e-" + (-exp).toString().padStart(2, '0')
}
