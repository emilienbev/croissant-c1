package com.bleural.croissant.data

import android.content.Context
import org.json.JSONObject

/**
 * One sentence of the model test and the tool it should call: "" none, "*" any but a
 * picture; [noAt] an `at` is a date made up; [args] (a JSON object, as text) what the call
 * must also pass, each argument compared ignoring case.
 */
data class BenchCase(val say: String, val expect: String, val group: BenchGroup, val noAt: Boolean = false, val args: String? = null)

/**
 * A group of the model test's cases, what its testing row offers: a built-in feature's,
 * [name] the catalog's tag (agenda, timers, web, pictures, tapes, questions) and [app] null,
 * or an enabled app's examples, [name] the app's name and [app] its id, so an app is never
 * taken for a feature of the same name.
 */
data class BenchGroup(val name: String, val app: String? = null)

/**
 * One of Ask's built-in tools: [server] true for one the server runs (`"runs": "server"`:
 * `image_generate` and `web_search`), which a hold offers only when the server's list says
 * it runs it.
 */
data class CatalogTool(val name: String, val description: String, val parameters: JSONObject, val server: Boolean)

/** The fixed parts of a hold's prompt: the heads of the two tool lists, and the clock line with {date}, {offset} and {week}. */
data class PromptParts(val tools: String, val apps: String, val clock: String)

/**
 * The whole system prompt of each model call besides a hold's, fixed (shown, never edited):
 * the Transform button's description pass, the Translator's with {a} and {b}, a tape
 * question's, an app's `ask()`.
 */
data class CallPrompts(val transform: String, val translate: String, val read: String, val app: String) {
    /** The Translator's prompt from [from] into [to], each named by its word. */
    fun translateRules(from: Language, to: Language): String = translate.replace("{a}", from.word).replace("{b}", to.word)
}

/**
 * Ask's built-in tools (the device's and the server's two), the seed prompt variants, the fixed
 * parts of a hold's prompt, the other calls' fixed prompts ([calls]) and the model test's
 * cases (assets/ask/catalog.json). A hold's prompt is put together from these here
 * (data/AskPrompt.kt); the server's tools/c1_prompt.py reads the same file to do the same
 * for eval_tools and fake_r1.
 *
 * [prompts] are the seeds a device's variants start from (data/Prompts.kt), "default"
 * first; [photo] a photo hold's rules, by the same names. A model gets the seed
 * [suggested] for its name, else "default".
 */
class AskCatalog(json: String) {
    private val o = JSONObject(json)

    /** In the file's order: the device's, the server's two, then `tape_ask`. */
    val tools: List<CatalogTool> = o.getJSONArray("tools").let { a ->
        List(a.length()) { i ->
            val t = a.getJSONObject(i)
            CatalogTool(t.getString("name"), t.getString("description"), t.getJSONObject("parameters"), t.optString("runs") == "server")
        }
    }

    val prompts: Map<String, String> = named(o.getJSONObject("prompts"))
    val photo: Map<String, String> = named(o.getJSONObject("photo"))

    val parts: PromptParts = o.getJSONObject("parts").let { PromptParts(it.getString("tools"), it.getString("apps"), it.getString("clock")) }

    /** Read when first asked for. */
    val calls: CallPrompts by lazy {
        o.getJSONObject("calls").let { c -> CallPrompts(c.getString("transform"), c.getString("translate"), c.getString("read"), c.getString("app")) }
    }

    private val suggest: List<Pair<String, String>> = o.optJSONObject("suggest")?.let { s ->
        s.keys().asSequence().map { it to s.getString(it) }.toList()
    }.orEmpty()

    /** In the file's order: a feature's cases together, the features in the order the test offers them. */
    val cases: List<BenchCase> = o.getJSONArray("cases").let { a ->
        List(a.length()) { i ->
            val c = a.getJSONObject(i)
            BenchCase(c.getString("say"), c.getString("expect"), BenchGroup(c.optString("group")), c.optBoolean("no_at"),
                c.optJSONObject("args")?.toString())
        }
    }

    /** The seed a model gets when none is picked: by its name ([entry] is `provider:model`), else "default". */
    fun suggested(entry: String?): String {
        val model = entry?.substringAfter(':')?.lowercase() ?: return DEFAULT
        return suggest.firstOrNull { (prefix, name) -> model.startsWith(prefix) && name in prompts }?.second ?: DEFAULT
    }

    /** A photo hold's rules for [entry]: by its model, as [suggested], else the default's. */
    fun photoFor(entry: String?): String = photo[suggested(entry)] ?: photo[DEFAULT].orEmpty()

    private fun named(g: JSONObject): Map<String, String> {
        val names = g.keys().asSequence().toList()
        return (listOf(DEFAULT) + (names - DEFAULT)).filter { g.has(it) }.associateWith { g.getString(it) }
    }

    companion object {
        const val ASSET = "ask/catalog.json"
        const val DEFAULT = "default"

        fun load(ctx: Context) = AskCatalog(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
