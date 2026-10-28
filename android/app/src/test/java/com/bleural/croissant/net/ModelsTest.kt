package com.bleural.croissant.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    // As croissant-server's GET /providers answers (server/croissant_server/providers.py, `public`).
    private val json = """{"chat": {"default": "ollama:gemma4:26b-nvfp4", "entries": [
        {"id": "ollama:gemma4:26b-nvfp4", "name": "Gemma 4 26B", "provider": "Ollama", "local": true},
        {"id": "gemini:gemini-3.8-flash", "name": "Gemini Flash", "provider": "Google Gemini", "local": false}]},
      "stt": {"default": "local:whisper-large-v3-turbo", "entries": [
        {"id": "local:whisper-large-v3-turbo", "name": "whisper-large-v3-turbo", "provider": "This server", "local": true, "partials": true},
        {"id": "groq:whisper-large-v3-turbo", "name": "Whisper at Groq", "provider": "Groq", "local": false, "partials": false}]},
      "text_to_image": {"default": null, "entries": []},
      "image_to_image": {"default": "local:z-image", "entries": [
        {"id": "local:z-image", "name": "z-image", "provider": "This server", "local": true}]}}"""

    @Test fun readsEverySlot() {
        val m = Models.parse(json)
        assertEquals(listOf("Gemma 4 26B", "Gemini Flash"), m.list(ModelSlot.Chat).entries.map { it.name })
        assertTrue(m.list(ModelSlot.Stt).entries[0].partials)
        assertEquals(false, m.list(ModelSlot.Stt).entries[1].local)
        assertNull(m.list(ModelSlot.TextToImage).default)
        assertEquals(0, m.list(ModelSlot.TextToImage).entries.size)
    }

    @Test fun theAskToolsTheServerRunsAndNoneFromAnOlderOne() {
        assertEquals(setOf("web_search", "tape_ask"), Models.parse("""{"tools": ["web_search", "tape_ask"]}""").tools)
        assertTrue(Models.parse(json).tools.isEmpty())
    }

    @Test fun aPickTheServerDroppedFallsBackToItsDefault() {
        val chat = Models.parse(json).list(ModelSlot.Chat)
        assertEquals("gemini:gemini-3.8-flash", chat.resolve("gemini:gemini-3.8-flash")?.id)
        assertEquals("ollama:gemma4:26b-nvfp4", chat.resolve("openai:gone")?.id)
        assertEquals("ollama:gemma4:26b-nvfp4", chat.resolve(null)?.id)
    }

    @Test fun readsWhatAModelCosts() {
        val e = Models.parse("""{"chat": {"default": null, "entries": [
            {"id": "gemini:gemini-3.1-flash-lite", "name": "gemini-3.1-flash-lite", "provider": "Google Gemini",
             "local": false, "free": true, "title": "3.1 Flash Lite", "rates": {"in": 0.25, "out": 1.5, "cached": null},
             "thinks": false, "month": 0.39, "today": 12, "resets": "America/Los_Angeles", "shares": true},
            {"id": "ollama:qwen3.5:9b", "name": "qwen3.5:9b", "provider": "Ollama", "local": true, "bytes": 6600000000}]}}""")
            .list(ModelSlot.Chat).entries
        assertEquals("3.1 Flash Lite", e[0].label)
        assertEquals(Rates(0.25, 1.5, null), e[0].rates)
        assertEquals(0.39, e[0].month!!, 1e-9)
        assertEquals(false, e[0].thinks)
        assertTrue(e[0].costsNothing)
        assertEquals(12, e[0].today)
        assertEquals("America/Los_Angeles", e[0].resets)
        assertTrue(e[0].shares)
        assertEquals("qwen3.5:9b", e[1].label)
        assertEquals(6_600_000_000L, e[1].bytes)
        assertNull(e[1].thinks)
        assertNull(e[1].rates)
        assertNull(e[1].resets)
        assertFalse(e[1].shares)
    }

    @Test fun readsTheUsage() {
        val u = UsageClient.parse("""{"days": 30, "total": 1.07, "daily": [0.0, 0.5, 0.57],
            "slots": {"chat": {"cost": 0.41, "free": false, "priced": false, "tokens": 1300000, "secs": 0, "pictures": 0},
                      "stt": {"cost": 0.0, "free": true, "tokens": 0, "secs": 3120, "pictures": 0}}}""")
        assertEquals(1.07, u.total, 1e-9)
        assertEquals(3, u.daily.size)
        assertEquals(1_300_000L, u.slots[ModelSlot.Chat]?.tokens)
        assertTrue(u.slots[ModelSlot.Stt]!!.free)
        assertNull(u.slots[ModelSlot.TextToImage])
        assertFalse(u.slots[ModelSlot.Chat]!!.priced)
        assertTrue(u.slots[ModelSlot.Stt]!!.priced)
        assertFalse(u.priced)
        assertFalse(u.allFree)
        assertTrue(u.copy(slots = u.slots.filterKeys { it == ModelSlot.Stt }).allFree)
    }
}
