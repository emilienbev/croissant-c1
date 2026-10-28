package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Ollama's own API (`POST /api/chat`, a JSON object a line): how Ask's prompts were
 * measured, with Ollama's own timings. Sent as the server's client sent it: `think: false`,
 * `options.temperature` 0, the tools whole (an empty list when none); tool calls come back
 * whole with their arguments as objects, and a tool's result goes back by its name.
 * A tool's schema goes as it is: the server's client cut it to the fields its model knows
 * (type, required, items, `$defs`; a property's type, items, description, enum), which only
 * differs for a schema with more in it.
 */
class OllamaChat(
    private val provider: Provider,
    override val model: String,
    private val http: OkHttpClient,
    private val temperature: Double? = 0.0,
    private val reasoning: String? = null,
) : ChatApi {

    fun body(messages: List<Msg>, tools: List<ToolSpec>?): JSONObject {
        // qwen3.5 and Gemma are reasoning models and would stream their thinking first. On a
        // push-to-talk device that is latency with nothing to show for it.
        val think: Any = if (reasoning == null || reasoning == "none") false else reasoning
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray(messages.map(::message)))
            .put("tools", JSONArray(tools.orEmpty().map(Chat::spec)))
            .put("stream", true)
            .put("think", think)
            .put("options", JSONObject().apply { temperature?.let { put("temperature", it) } })
    }

    private fun message(m: Msg): JSONObject {
        if (m.role == "tool") return JSONObject().put("role", "tool").put("tool_name", m.call?.name.orEmpty()).put("content", m.text)
        return JSONObject().put("role", m.role).put("content", m.text).apply {
            if (m.images.isNotEmpty()) put("images", JSONArray(m.images.map { Base64.getEncoder().encodeToString(it) }))
            if (m.calls.isNotEmpty()) put("tool_calls", JSONArray(m.calls.map { c ->
                JSONObject().put("function", JSONObject().put("name", c.name).put("arguments", c.args))
            }))
        }
    }

    override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
        val req = Request.Builder().url("${provider.url}/api/chat").post(Http.json(body(messages, tools))).build()
        return http.exchange(req, null) { r ->
            r.ok(null) {
                val calls = mutableListOf<Call>()
                var usage: Usage? = null
                it.body.source().ndjson { part ->
                    part.str("error").takeIf { e -> e.isNotEmpty() }?.let { e -> throw AskError(0, e) }
                    if (part.optBoolean("done")) {
                        usage = Usage(part.optInt("prompt_eval_count"), part.optLong("prompt_eval_duration") / 1e9,
                            part.optInt("eval_count"), part.optLong("eval_duration") / 1e9)
                    }
                    val m = part.optJSONObject("message") ?: return@ndjson
                    m.str("content").takeIf { c -> c.isNotEmpty() }?.let(text)
                    val tc = m.optJSONArray("tool_calls") ?: return@ndjson
                    for (i in 0 until tc.length()) {
                        val f = tc.optJSONObject(i)?.optJSONObject("function") ?: continue
                        calls += Call(f.str("name"), f.optJSONObject("arguments") ?: JSONObject())
                    }
                }
                Reply(calls, usage = usage)
            }
        }
    }
}
