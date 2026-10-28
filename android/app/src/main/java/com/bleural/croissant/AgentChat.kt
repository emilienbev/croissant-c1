package com.bleural.croissant

import org.json.JSONObject

/** One thing in a session's conversation: your words, the agent's, or one of its tool calls. */
sealed interface ChatItem {
    data class You(val words: String) : ChatItem
    data class Said(val words: String) : ChatItem
    /** A tool call by ACP's [kind] (read, edit, execute, search, fetch, ...); [status] pending, in_progress, completed or failed. */
    data class Tool(val id: String, val title: String, val kind: String, val status: String) : ChatItem
}

/** A permission the agent waits on, with its own options; [kind] allow_once, allow_always, reject_once or reject_always. */
data class AskOption(val id: String, val name: String, val kind: String)
data class Asking(val request: String, val title: String, val options: List<AskOption>)

/**
 * An open session's conversation, built from the bridge's log of it (acp-bridge/acp_bridge/
 * server.py): every update in seq order, one already heard skipped ([with]). [sending] and
 * [unsent] are a hold's words on their way, or that did not get there.
 */
data class AgentChat(
    val agent: String,
    val session: String,
    val cwd: String,
    val seq: Int = 0,
    /** The bridge's run [seq] counts in; another run's log starts again from 1. */
    val epoch: String? = null,
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val asking: Asking? = null,
    val error: String? = null,
    /** The bridge has it open and has sent what it holds. */
    val opened: Boolean = false,
    val sending: String? = null,
    val unsent: String? = null,
) {
    val key get() = "$agent/$session"

    /** A new chat's page before its first words: the bridge makes the session at the first hold. */
    val blank get() = session.isEmpty()

    /** This conversation with the log's entry [seq], ACP's session/update or one of the bridge's. */
    fun with(seq: Int, update: JSONObject): AgentChat {
        if (seq <= this.seq) return this
        val next = copy(seq = seq)
        return when (update.optString("sessionUpdate")) {
            "user_message_chunk" -> next.text(update, you = true)
            "agent_message_chunk" -> next.text(update, you = false)
            "tool_call" -> next.copy(items = items + ChatItem.Tool(
                update.optString("toolCallId"), update.optString("title"), update.optString("kind", "other"),
                update.optString("status").ifEmpty { "pending" }))
            "tool_call_update" -> {
                val id = update.optString("toolCallId")
                next.copy(items = items.map { item ->
                    if (item is ChatItem.Tool && item.id == id) item.copy(
                        title = update.optString("title").ifEmpty { item.title },
                        status = update.optString("status").ifEmpty { item.status })
                    else item
                })
            }
            "bridge_turn" -> if (update.optBoolean("running")) next.copy(running = true, error = null)
                else next.copy(running = false, asking = null, error = update.optString("error").ifEmpty { null })
            "bridge_permission" -> {
                val o = update.optJSONArray("options")
                val options = (0 until (o?.length() ?: 0)).map { i ->
                    o!!.getJSONObject(i).let { AskOption(it.optString("optionId"), it.optString("name"), it.optString("kind")) }
                }
                val title = update.optJSONObject("toolCall")?.optString("title").orEmpty()
                next.copy(asking = Asking(update.optString("request"), title, options))
            }
            "bridge_permitted" -> if (asking?.request == update.optString("request")) next.copy(asking = null) else next
            else -> next
        }
    }

    /** A message chunk: added to the last item when it is the same speaker's words, else a new one. */
    private fun text(update: JSONObject, you: Boolean): AgentChat {
        val content = update.optJSONObject("content") ?: return this
        if (content.optString("type") != "text") return this
        val words = content.optString("text")
        val last = items.lastOrNull()
        val joined = when {
            you && last is ChatItem.You -> items.dropLast(1) + ChatItem.You(last.words + words)
            !you && last is ChatItem.Said -> items.dropLast(1) + ChatItem.Said(last.words + words)
            you -> items + ChatItem.You(words)
            else -> items + ChatItem.Said(words)
        }
        return copy(items = joined)
    }
}
