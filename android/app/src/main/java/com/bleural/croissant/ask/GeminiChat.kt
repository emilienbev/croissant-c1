package com.bleural.croissant.ask

import com.bleural.croissant.data.ToolSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini's own API (`models/{model}:streamGenerateContent?alt=sse`, the key in
 * `x-goog-api-key`). Its OpenAI layer would do for chat, but not for transcription or
 * picture edits, so one Gemini speaks Gemini throughout. Sent as Google's SDK sent it for
 * the server: the system prompt as `systemInstruction`, the tools as `functionDeclarations`
 * with their schema as `parametersJsonSchema`, one turn of a role together.
 *
 * A model turn goes back as its parts came, raw ([Reply.raw]): Gemini 3's thought signatures
 * must be echoed untouched for a second round of tool calls. A thought's text is never
 * shown. Temperature is left to the model unless one is given: Google advises against
 * lowering it on Gemini 3. [reasoning] is `none` (a thinking budget of 0, which only 2.5
 * models accept) or a thinking level (`minimal`, `low`, `medium`, `high`).
 */
class GeminiChat(
    private val provider: Provider,
    override val model: String,
    private val key: String?,
    private val http: OkHttpClient,
    private val temperature: Double? = null,
    private val reasoning: String? = null,
    private val log: (String) -> Unit = {},
) : ChatApi {

    fun body(messages: List<Msg>, tools: List<ToolSpec>?): JSONObject {
        val system = mutableListOf<String>()
        val contents = JSONArray()

        fun add(role: String, parts: List<JSONObject>) {
            if (parts.isEmpty()) return
            // Consecutive turns of one role go in one content: the function responses of one
            // round together, as the API wants them.
            val last = if (contents.length() > 0) contents.getJSONObject(contents.length() - 1) else null
            if (last != null && last.str("role") == role) parts.forEach { last.getJSONArray("parts").put(it) }
            else contents.put(JSONObject().put("role", role).put("parts", JSONArray(parts)))
        }

        for (m in messages) when (m.role) {
            "system" -> system += m.text
            "user" -> add("user", (if (m.text.isNotEmpty()) listOf(text(m.text)) else emptyList()) + m.images.map { i ->
                JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.getEncoder().encodeToString(i)))
            })
            "assistant" -> add("model", m.raw?.let { raw -> (0 until raw.length()).map { raw.getJSONObject(it) } }
                ?: ((if (m.text.isNotEmpty()) listOf(text(m.text)) else emptyList()) + m.calls.map { c ->
                    JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args).apply { if (c.id.isNotEmpty()) put("id", c.id) })
                }))
            "tool" -> m.call?.let { c ->
                val v = m.value as? JSONObject ?: JSONObject().put("result", m.value?.takeIf { it != JSONObject.NULL } ?: m.text)
                add("user", listOf(JSONObject().put("functionResponse",
                    JSONObject().put("name", c.name).put("response", v).apply { if (c.id.isNotEmpty()) put("id", c.id) })))
            }
        }
        val config = JSONObject().apply {
            temperature?.let { put("temperature", it) }
            when {
                reasoning == "none" -> put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                reasoning != null -> put("thinkingConfig", JSONObject().put("thinkingLevel", reasoning.uppercase()))
            }
        }
        return JSONObject().put("contents", contents).apply {
            val instruction = system.joinToString("\n\n")
            if (instruction.isNotEmpty()) put("systemInstruction", JSONObject().put("role", "user").put("parts", JSONArray().put(text(instruction))))
            if (config.length() > 0) put("generationConfig", config)
            if (!tools.isNullOrEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools.map { t ->
                JSONObject().put("name", t.name).put("description", t.description).put("parametersJsonSchema", t.parameters)
            }))))
        }
    }

    private fun text(s: String) = JSONObject().put("text", s)

    override suspend fun stream(messages: List<Msg>, tools: List<ToolSpec>?, text: (String) -> Unit): Reply {
        val key = key ?: throw AskError(0, "${provider.title} has no key")
        val req = Request.Builder().url("${provider.url}/${Lister.model(model)}:streamGenerateContent?alt=sse")
            .googKey(key).post(Http.json(body(messages, tools))).build()
        val clock = Clock()
        return http.exchange(req, key) { r ->
            r.ok(key) {
                val parts = JSONArray()
                val calls = mutableListOf<Call>()
                var meta: JSONObject? = null
                it.body.source().sse { data ->
                    val chunk = JSONObject(data)
                    chunk.optJSONObject("error")?.let { e -> throw AskError(e.optInt("code"), redact(e.str("message"), key), redact(data, key)) }
                    meta = chunk.optJSONObject("usageMetadata") ?: meta
                    val cands = chunk.optJSONArray("candidates") ?: JSONArray()
                    for (i in 0 until cands.length()) {
                        val cand = cands.optJSONObject(i) ?: continue
                        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                        for (j in 0 until ps.length()) {
                            val part = ps.optJSONObject(j) ?: continue
                            parts.put(part)
                            if (part.optBoolean("thought")) continue
                            part.str("text").takeIf { t -> t.isNotEmpty() }?.let { t -> clock.mark(); text(t) }
                            part.optJSONObject("functionCall")?.let { fc ->
                                clock.mark()
                                calls += Call(fc.str("name"), fc.optJSONObject("args") ?: JSONObject(), fc.str("id"))
                            }
                        }
                        val reason = cand.str("finishReason")
                        if (reason.isNotEmpty() && reason != "STOP" && reason != "MAX_TOKENS") log("${provider.id}:$model finished with $reason")
                    }
                    true
                }
                Reply(calls, raw = parts, usage = meta?.let { m ->
                    clock.usage(m.optInt("promptTokenCount"), m.optInt("candidatesTokenCount") + m.optInt("thoughtsTokenCount"),
                        m.optInt("cachedContentTokenCount"))
                })
            }
        }
    }
}
