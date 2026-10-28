package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A tool as a hold offers it: its spec ([name], [description], [parameters]) and [app], the
 * name of the app it comes from, null for a built-in one. An app's comes in with its own
 * description and is offered with its app's name added.
 */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject, val app: String? = null) {
    /** The spec on the wire. */
    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("parameters", parameters)
}

/** What a hold sends: its variant's name (for the logs), the whole system prompt, and the tools on offer (none with a photo). */
data class HoldPrompt(val variant: String, val system: String, val tools: List<ToolSpec>) {
    fun specs(): JSONArray = JSONArray().also { a -> tools.forEach { a.put(it.toJson()) } }

    /**
     * For the logs, the same on both ends: the variant, the prompt's length in code points (as
     * Python's `len`) and the first 8 hex of its sha256.
     */
    val tag: String get() {
        val sha = MessageDigest.getInstance("SHA-256").digest(system.toByteArray())
        return "$variant, ${system.codePointCount(0, system.length)} chars, " + sha.take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Ask's system prompt, put together here for every hold: the variant's text, then the
 * tools on offer by name and description (the built-in ones, then each enabled app's),
 * then the clock. A photo hold is offered no tools: the photo rules for its
 * model go in the list's place. Blocks are joined by a blank line; an empty one is left out.
 * server/tools/c1_prompt.py does the same in Python, and both are held to
 * test/resources/ask/prompts.golden.json.
 *
 * Measured on qwen3.5:9b-mlx (2026-09-14, eval_tools): any formatting rule in the system
 * prompt ("answer in plain text only, no markdown") stops the model calling tools (0/15
 * against 25/25 without it), and persona text ahead of the tool rules costs calls too. So
 * the seeds keep the persona to a clause and ask for no format; the loop scrubs markdown (ask/Loop.kt).
 */
object AskPrompt {

    fun hold(catalog: AskCatalog, variant: String, text: String, entry: String?, now: OffsetDateTime, photo: Boolean,
             serverTools: Set<String>?, apps: List<ToolSpec>): HoldPrompt {
        val tools = if (photo) emptyList() else offered(catalog, serverTools, apps)
        val blocks = mutableListOf(trim(text))
        if (photo) blocks += trim(catalog.photoFor(entry))
        else {
            val (own, others) = tools.partition { it.app == null }
            if (own.isNotEmpty()) blocks += catalog.parts.tools + "\n" + own.joinToString("\n", transform = ::line)
            if (others.isNotEmpty()) blocks += catalog.parts.apps + "\n" + others.joinToString("\n", transform = ::line)
        }
        blocks += clock(now, catalog.parts.clock)
        return HoldPrompt(variant, blocks.filter { it.isNotEmpty() }.joinToString("\n\n"), tools)
    }

    /**
     * The tools a hold offers, in order: the catalog's (a server one only while [serverTools]
     * has it; before the server has listed any, all of them), then the apps', each described
     * with its app's name. A name already on offer is not offered twice.
     */
    fun offered(catalog: AskCatalog, serverTools: Set<String>?, apps: List<ToolSpec>): List<ToolSpec> {
        val own = catalog.tools.filter { !it.server || serverTools == null || it.name in serverTools }
            .map { ToolSpec(it.name, it.description, it.parameters) }
        val theirs = apps.map { it.copy(description = "${it.description} (the ${it.app} app)") }
        return (own + theirs).distinctBy { it.name }
    }

    /** A tool's line in the prompt: its name and its description on one line. */
    fun line(spec: ToolSpec): String = "- ${spec.name}: " + spec.description.replace(SPACE, " ").trim(' ')

    /**
     * The clock line, from the device's own time and offset, so "tomorrow at 1 pm" resolves in
     * its zone. English names whatever the locale, and an offset never written `Z`.
     */
    fun clock(now: OffsetDateTime, template: String): String = template
        .replace("{date}", now.format(DATE))
        .replace("{offset}", now.format(OFFSET))
        .replace("{week}", (1L..7L).joinToString(", ") { now.plusDays(it).format(DAY) })

    /** Only these four, so Kotlin and Python cannot differ on other whitespace. */
    private fun trim(s: String) = s.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

    private val SPACE = Regex("[ \t\r\n]+")
    private val DATE = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy, HH:mm", Locale.ENGLISH)
    private val OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd", Locale.ENGLISH)
}
