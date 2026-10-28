package com.bleural.croissant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatTest {

    private fun chunk(kind: String, text: String) =
        JSONObject().put("sessionUpdate", kind).put("content", JSONObject().put("type", "text").put("text", text))

    private fun play(vararg updates: JSONObject): AgentChat =
        updates.foldIndexed(AgentChat("codex", "s1", "/w")) { i, c, u -> c.with(i + 1, u) }

    @Test fun joinsChunksIntoTurns() {
        val c = play(
            chunk("user_message_chunk", "make "), chunk("user_message_chunk", "a file"),
            chunk("agent_message_chunk", "I'll "), chunk("agent_message_chunk", "do it."),
            chunk("user_message_chunk", "thanks"),
        )
        assertEquals(listOf(ChatItem.You("make a file"), ChatItem.Said("I'll do it."), ChatItem.You("thanks")), c.items)
        assertEquals(5, c.seq)
    }

    @Test fun aToolCallIsOneLineUpdatedInPlace() {
        val c = play(
            chunk("agent_message_chunk", "Creating it."),
            JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "t1").put("title", "printf hi > a.txt").put("kind", "execute").put("status", "in_progress"),
            JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "t1").put("status", "completed"),
            chunk("agent_message_chunk", "done"),
        )
        assertEquals(listOf(ChatItem.Said("Creating it."), ChatItem.Tool("t1", "printf hi > a.txt", "execute", "completed"), ChatItem.Said("done")), c.items)
    }

    @Test fun aPermissionWaitsUntilAnswered() {
        val ask = JSONObject().put("sessionUpdate", "bridge_permission").put("request", "7")
            .put("toolCall", JSONObject().put("title", "Run command"))
            .put("options", org.json.JSONArray()
                .put(JSONObject().put("optionId", "a").put("name", "Yes, proceed").put("kind", "allow_once"))
                .put(JSONObject().put("optionId", "r").put("name", "No").put("kind", "reject_once")))
        val running = play(JSONObject().put("sessionUpdate", "bridge_turn").put("running", true), ask)
        assertTrue(running.running)
        assertEquals("Run command", running.asking?.title)
        assertEquals(listOf("allow_once", "reject_once"), running.asking?.options?.map { it.kind })
        val answered = running.with(3, JSONObject().put("sessionUpdate", "bridge_permitted").put("request", "7").put("option", "a"))
        assertNull(answered.asking)
        val ended = answered.with(4, JSONObject().put("sessionUpdate", "bridge_turn").put("running", false).put("stopReason", "end_turn"))
        assertFalse(ended.running)
        assertNull(ended.error)
    }

    @Test fun anUpdateHeardBeforeIsSkipped() {
        val c = play(chunk("agent_message_chunk", "once"))
        assertEquals(c, c.with(1, chunk("agent_message_chunk", "once")))
        assertEquals(listOf(ChatItem.Said("once")), c.items)
    }
}
