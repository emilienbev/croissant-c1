package com.bleural.croissant.atelier

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What an Atelier app may use beyond its own page, each named in `croissant.json`'s `uses` and
 * listed on the install sheet. The bridge refuses a call the app did not declare.
 */
enum class Use(val key: String) {
    /** `fetch(url)`: the internet, never the tailnet or the LAN. */
    Network("network"),
    /** `ask(text)`: the server's chat model. */
    Model("model"),
    /** `onWords(...)`: a hold's words on its page, in place of Ask. */
    Words("words"),
}

/**
 * An app's `croissant.json`, read and checked. Everything an app says about itself, and nothing
 * about how it looks beyond its [hue] and its [glyph]: the design draws the rest. Not its id:
 * the device gives it one ([AppId]), so two apps can never take each other's place or data.
 *
 * - [name]: the header word and its label in Atelier.
 * - [hue]: `#RRGGBB`, as 0xRRGGBB.
 * - [glyph]: a Lucide icon's name (lucide.dev), or null; the design falls back to Atelier's.
 * - [croissant]: the pieces and bridge it was written for, required; one newer than [CROISSANT] is refused.
 */
data class AppManifest(
    val name: String,
    val hue: Int,
    val glyph: String?,
    val version: String,
    val croissant: Int,
    val uses: Set<Use>,
    val tools: List<AppTool> = emptyList(),
) {
    companion object {
        /** The version this Croissant runs: the pieces and the bridge of `atelier/prelude.js`. */
        const val CROISSANT = 1
        const val FILE = "croissant.json"
        const val SCRIPT = "croissant.js"

        private val HUE = Regex("#([0-9a-fA-F]{6})")
        private val GLYPH = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private const val NAME_MAX = 24

        /** [text] as a croissant.json, or why not in words a page can show. An `id` in it is ignored. */
        fun parse(text: String): Result<AppManifest> = runCatching {
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw BadApp("croissant.json is not JSON")
            }
            if (!o.has("croissant")) throw BadApp("croissant.json: no \"croissant\" version")
            val croissant = (o.opt("croissant") as? Int) ?: throw BadApp("croissant.json: \"croissant\" is not a number")
            if (croissant < 1) throw BadApp("croissant.json: \"croissant\" is not a number")
            if (croissant > CROISSANT) throw BadApp("it needs a newer Croissant (croissant $croissant)")
            val name = o.optString("name").trim().also {
                if (it.isEmpty()) throw BadApp("croissant.json: no name")
                if (it.length > NAME_MAX) throw BadApp("croissant.json: the name is longer than $NAME_MAX letters")
            }
            val hue = HUE.matchEntire(o.optString("hue"))?.groupValues?.get(1)?.toInt(16)
                ?: throw BadApp("croissant.json: hue is not #RRGGBB")
            val glyph = o.optString("glyph").trim().ifEmpty { null }
                ?.also { if (!GLYPH.matches(it)) throw BadApp("croissant.json: glyph is not a Lucide icon's name") }
            val uses = (o.optJSONArray("uses") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i ->
                    val k = a.optString(i)
                    Use.entries.firstOrNull { it.key == k } ?: throw BadApp("croissant.json: uses \"$k\", which Croissant does not have")
                }.toSet()
            }
            val tools = (o.optJSONArray("tools") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i -> AppTool.parse(a.optJSONObject(i) ?: throw BadApp("croissant.json: tools[$i] is not an object")) }
            }
            if (tools.map { it.name }.toSet().size != tools.size) throw BadApp("croissant.json: two tools have the same name")
            AppManifest(name, hue, glyph, o.optString("version").trim().ifEmpty { "0" }, croissant, uses, tools)
        }
    }
}

/**
 * A sentence that should call a tool, for the model test; with [args] (a JSON object, as
 * text), each argument named there must also come out as given, compared ignoring case.
 */
data class ToolExample(val say: String, val args: String? = null) {
    fun toJson(): Any = if (args == null) say else JSONObject().put("say", say).put("args", JSONObject(args))

    companion object {
        /** A sentence, or {"say": ..., "args": {...}}. */
        fun parse(v: Any?, tool: String): ToolExample? = when (v) {
            is String -> v.trim().ifEmpty { null }?.let { ToolExample(it) }
            is JSONObject -> {
                val say = v.optString("say").trim().ifEmpty { throw BadApp("croissant.json: an example of tool $tool has no say") }
                val args = when (val a = v.opt("args")) {
                    null, JSONObject.NULL -> null
                    is JSONObject -> a.toString()
                    else -> throw BadApp("croissant.json: an example of tool $tool has args that are not an object")
                }
                ToolExample(say, args)
            }
            else -> throw BadApp("croissant.json: an example of tool $tool is not a sentence")
        }
    }
}

/**
 * A tool an app offers Ask (`tools` in croissant.json), handled by its `onTool` with its page
 * open: Ask's call opens the app's page, the app acts there, and what its handler returns
 * goes back to the model. [parameters] is a JSON Schema object, as text; [examples] are
 * sentences that should call it, which the model test asks.
 */
data class AppTool(val name: String, val description: String, val parameters: String, val examples: List<ToolExample>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description)
        .put("parameters", JSONObject(parameters)).put("examples", JSONArray(examples.map { it.toJson() }))

    companion object {
        /** Short enough that the app's id ([AppId.MAX]), an underscore and it fit a tool name's 64. */
        private val NAME = Regex("[a-z][a-z0-9_]{0,22}")

        fun parse(o: JSONObject): AppTool {
            val name = o.optString("name").also { if (!NAME.matches(it)) throw BadApp("croissant.json: a tool's name is not lowercase letters, digits and _, up to 23") }
            val description = o.optString("description").trim().ifEmpty { throw BadApp("croissant.json: tool $name has no description") }
            val params = when (val p = o.opt("parameters")) {
                null, JSONObject.NULL -> JSONObject().put("type", "object").put("properties", JSONObject())
                is JSONObject -> p.also { if (p.optString("type", "object") != "object") throw BadApp("croissant.json: tool $name's parameters are not an object schema") }
                else -> throw BadApp("croissant.json: tool $name's parameters are not an object schema")
            }
            if (!params.has("type")) params.put("type", "object")
            val examples = o.optJSONArray("examples")?.let { a -> (0 until a.length()).mapNotNull { ToolExample.parse(a.opt(it), name) } }.orEmpty()
            return AppTool(name, description, params.toString(), examples)
        }
    }
}

/** An app that cannot be installed or run, with the reason in a page's words. */
class BadApp(message: String) : Exception(message)
