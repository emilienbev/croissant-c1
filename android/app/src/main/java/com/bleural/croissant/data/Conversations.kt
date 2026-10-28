package com.bleural.croissant.data

import com.bleural.croissant.Part
import com.bleural.croissant.ToolCall
import com.bleural.croissant.data.JsonStore.Companion.stringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.OffsetDateTime

/**
 * One hold of a conversation: what was asked, and what came back in the order it came
 * (answer text, tool lines and pictures, the same [Part]s the live exchange is drawn
 * from, so a kept turn and the one in flight look alike).
 *
 * [state] is [TurnState.Asking] from the moment of the hold until the answer is on this
 * disk, whether it is streaming in live or still being answered after a new hold took over
 * the page (VoiceService). [photo] is the library photo the hold carried, by name.
 */
data class Turn(
    val id: String,
    val at: Long,
    val question: String,
    val parts: List<Part> = emptyList(),
    val state: TurnState = TurnState.Asking,
    val error: String? = null,
    val photo: String? = null,
) {
    val answer: String get() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}

enum class TurnState { Asking, Done, Failed }

/** One Ask conversation: its turns, oldest first. Never empty. */
data class Conversation(val id: String, val turns: List<Turn>) {
    val at: Long get() = turns.first().at
    /** The last time anything was asked in it: History sorts and dates by this. */
    val last: Long get() = turns.last().at
    val title: String get() = turns.firstOrNull { it.question.isNotBlank() }?.question.orEmpty()
}

/**
 * `conversations.json`: every Ask conversation, kept until deleted from History.
 *
 * Text only (a photo is a name in the library), so the whole file stays small enough to
 * rewrite on every change like the other stores. A conversation is written at its first
 * hold and each turn twice more at most: when the question is heard and when the answer
 * is in.
 */
class ConversationsStore(
    file: File,
    clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : JsonStore<Conversation>(file, clock) {

    companion object {
        /** Earlier turns a hold shows the model. */
        const val PRIOR = 6

        private val random = SecureRandom()
        private fun hex(n: Int) = buildString { repeat(n) { append("0123456789abcdef"[random.nextInt(16)]) } }

        /** `c_` and sixteen hex digits. */
        fun newConvId() = "c_" + hex(16)
        /** `t_` and sixteen hex digits. */
        fun newTurnId() = "t_" + hex(16)
    }

    init { load() }

    override fun keep(item: Conversation, now: OffsetDateTime) = item.turns.isNotEmpty()

    fun get(id: String): Conversation? = list().firstOrNull { it.id == id }

    /** The conversation and turn with this turn id, if it is still kept. */
    fun find(turnId: String): Pair<Conversation, Turn>? {
        for (c in list()) c.turns.firstOrNull { it.id == turnId }?.let { return c to it }
        return null
    }

    /** What a hold shows the model of [conv] before its question: the last answered turns, oldest first. */
    fun prior(conv: String): List<Pair<String, String>> =
        get(conv)?.turns.orEmpty()
            .filter { it.state == TurnState.Done && it.question.isNotBlank() }
            .takeLast(PRIOR)
            .map { it.question to it.answer }

    /** A hold has begun: its turn goes at the end of [conv], which is made if it is new. */
    @Synchronized
    fun begin(conv: String, turn: Turn) {
        val all = list()
        val c = all.firstOrNull { it.id == conv }
        commit(if (c == null) all + Conversation(conv, listOf(turn))
               else all.map { if (it.id == conv) it.copy(turns = it.turns + turn) else it })
    }

    /** Replace the turn with [turnId] by what [f] makes of it. Nothing happens if it is gone. */
    @Synchronized
    fun update(turnId: String, f: (Turn) -> Turn) {
        val all = list()
        if (all.none { c -> c.turns.any { it.id == turnId } }) return
        commit(all.map { c -> c.copy(turns = c.turns.map { if (it.id == turnId) f(it) else it }) })
    }

    /** The hold came to nothing (no speech, or its words never came back): it leaves the record. */
    @Synchronized
    fun drop(turnId: String) {
        commit(list().map { c -> c.copy(turns = c.turns.filter { it.id != turnId }) })
    }

    @Synchronized
    fun delete(ids: Set<String>) {
        commit(list().filter { it.id !in ids })
    }

    override fun encode(item: Conversation): JSONObject = JSONObject()
        .put("id", item.id)
        .put("turns", JSONArray().also { a -> item.turns.forEach { a.put(encodeTurn(it)) } })

    override fun decode(o: JSONObject): Conversation {
        val a = o.getJSONArray("turns")
        return Conversation(o.getString("id"), List(a.length()) { decodeTurn(a.getJSONObject(it)) })
    }

    private fun encodeTurn(t: Turn) = JSONObject()
        .put("id", t.id)
        .put("at", t.at)
        .put("q", t.question)
        .put("state", t.state.name)
        .apply { if (t.error != null) put("error", t.error) }
        .apply { if (t.photo != null) put("photo", t.photo) }
        .put("parts", JSONArray().also { a ->
            t.parts.forEach { p ->
                a.put(when (p) {
                    is Part.Text -> JSONObject().put("text", p.text)
                    is Part.Tool -> JSONObject()
                        .put("tool", p.call.label)
                        .apply { if (p.call.value != null) put("value", p.call.value) }
                        .apply { if (p.call.failed) put("failed", true) }
                        .apply { if (p.call.generation != null) put("generation", p.call.generation) }
                })
            }
        })

    private fun decodeTurn(o: JSONObject): Turn {
        val a = o.optJSONArray("parts") ?: JSONArray()
        val parts = List(a.length()) { i ->
            val p = a.getJSONObject(i)
            if (p.has("text")) Part.Text(p.getString("text"))
            else Part.Tool(ToolCall(
                call = "k$i",
                label = p.optString("tool"),
                value = p.stringOrNull("value"),
                running = false,
                failed = p.optBoolean("failed"),
                generation = p.stringOrNull("generation"),
            ))
        }
        return Turn(
            id = o.getString("id"),
            at = o.getLong("at"),
            question = o.optString("q"),
            parts = parts,
            state = runCatching { TurnState.valueOf(o.optString("state")) }.getOrDefault(TurnState.Done),
            error = o.stringOrNull("error"),
            photo = o.stringOrNull("photo"),
        )
    }
}
