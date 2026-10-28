package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Any OpenAI-compatible chat completions endpoint (`POST /chat/completions`, server-sent
 * events): OpenAI, Groq, OpenRouter, Mistral, Together, DeepSeek, LM Studio, one's own.
 * Temperature 0 and `stream_options.include_usage`, each dropped for good the first time
 * the model refuses it ([OPTIONAL]); tool calls assembled from their pieces; seconds timed
 * here, the API giving only tokens. As the server's SDK did (`max_retries=1`), a call that
 * fails before it answers (no connection, 408, 409, 429, 5xx) is tried once more.
 */
class OpenAiChat(
    private val provider: Provider,
    override val model: String,
    private val key: String?,
    private val http: OkHttpClient,
    private val temperature: Double? = 0.0,
    private val reasoning: String? = null,
    private val log: (String) -> Unit = {},
) : ChatApi {

    private val omit: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The request as it goes now: [OPTIONAL]'s parameters less the ones this model has refused. */
    fun body(messages: List<Msg>, tools: List<ToolSpec>?): JSONObject = JSONObject()
        .put("model", model)
        .put("messages", JSONArray(messages.map(::message)))
        .put("stream", true)
        .put("stream_options", JSONObject().put("include_usage", true))
        .apply {
            if (!tools.isNullOrEmpty()) put("tools", JSONArray(tools.map(Chat::spec)))
            temperature?.let { put("temperature", it) }
            reasoning?.let { put("reasoning_effort", it) }
            omit.forEach { remove(it) }
        }

    private fun message(m: Msg): JSONObject = when {
        m.role == "tool" -> JSONObject().put("role", "tool").put("tool_call_id", m.call?.id.orEmpty()).put("content", m.text)
        m.role == "assistant" -> JSONObject().put("role", "assistant").put("content", m.text.ifEmpty { null } ?: JSONObject.NULL).apply {
            if (m.calls.isNotEmpty()) put("tool_calls", JSONArray(m.calls.map { c ->
                JSONObject().put("id", c.id).put("type", "function")
                    .put("function", JSONObject().put("name", c.name).put("arguments", pyJson(c.args)))
            }))
        }
        m.images.isNotEmpty() -> JSONObject().put("role", m.role).put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", m.text))
            .apply {
                m.images.forEach { i ->
                    put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(i))))
                }
            })
        else -> JSONObject().put("role", m.role).put("content", m.text)
    }

    override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
        val clock = Clock()
        var retried = false
        while (true) {
            val body = body(messages, tools)
            var answered = false
            try {
                val req = Request.Builder().url("${provider.url}/chat/completions").bearer(key).post(Http.json(body)).build()
                return http.exchange(req, key) { r ->
                    r.ok(key) {
                        answered = true
                        read(it.body.source(), clock, text)
                    }
                }
            } catch (e: AskError) {
                // A refusal names what it refuses: drop that one for good and send again.
                val drop = OPTIONAL.firstOrNull { it in e.body && body.has(it) }
                if ((e.code == 400 || e.code == 422) && drop != null) {
                    log("${provider.title} ($model) refuses $drop; sending it without from now on")
                    omit += drop
                    continue
                }
                if (retried || answered || !retriable(e.code)) throw e
            } catch (e: IOException) {
                if (retried || answered) throw e
            }
            retried = true
            delay(RETRY_MS)
        }
    }

    private fun read(source: okio.BufferedSource, clock: Clock, text: (String) -> Unit): Reply {
        // Tool calls arrive in pieces: the id and name first, the arguments as fragments of a
        // JSON string, each piece carrying the index of the call it belongs to.
        val parts = sortedMapOf<Int, Array<String>>()
        var usage: JSONObject? = null
        source.sse { data ->
            if (data.startsWith("[DONE]")) return@sse false
            val chunk = JSONObject(data)
            when (val e = chunk.opt("error")) {
                is JSONObject -> throw AskError(0, redact(e.str("message").ifEmpty { "an error during the stream" }, key), redact(data, key))
                is String -> if (e.isNotEmpty()) throw AskError(0, redact(e, key), redact(data, key))
            }
            chunk.optJSONObject("usage")?.let { usage = it }
            val choices = chunk.optJSONArray("choices") ?: JSONArray()
            for (i in 0 until choices.length()) {
                val d = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
                d.str("content").takeIf { it.isNotEmpty() }?.let { clock.mark(); text(it) }
                val tcs = d.optJSONArray("tool_calls") ?: continue
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    clock.mark()
                    val p = parts.getOrPut(tc.optInt("index", 0)) { arrayOf("", "", "") }
                    tc.str("id").takeIf { it.isNotEmpty() }?.let { p[0] = it }
                    tc.optJSONObject("function")?.let { f -> p[1] += f.str("name"); p[2] += f.str("arguments") }
                }
            }
            true
        }
        source.drain()
        val calls = parts.map { (i, p) ->
            val args = runCatching { JSONObject(p[2].ifEmpty { "{}" }) }.getOrElse {
                log("${provider.id}:$model: unreadable arguments for ${p[1]}: ${p[2].take(200)}")
                JSONObject()
            }
            Call(p[1], args, p[0].ifEmpty { "call_$i" })
        }
        return Reply(calls, usage = usage?.let { u ->
            clock.usage(u.optInt("prompt_tokens"), u.optInt("completion_tokens"),
                u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0)
        })
    }

    companion object {
        /** Sent unless the model refuses them; a refusal drops one for good. */
        val OPTIONAL = listOf("temperature", "reasoning_effort", "stream_options")
        /** The SDK's first wait before its one retry. */
        const val RETRY_MS = 500L
    }
}
