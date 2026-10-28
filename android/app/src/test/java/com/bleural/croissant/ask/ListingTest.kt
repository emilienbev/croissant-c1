package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Each provider's listing from its recorded answers (test/resources/ask/), sorted as the server's catalog.py sorts it. */
class ListingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val host = "100.100.100.7"
    private val geminiKey = "AIzaSyD-fake-0123456789abcdefghijKLMNOP"
    private fun provider(id: String, spec: ProviderSpec = ProviderSpec()) = Providers.resolve(id, spec, host)

    @Test fun ollamaChatIsCompletionWithTools() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/api/tags", body = fixture("ollama_tags.json"))
        val shown = JSONObject(fixture("ollama_show.json"))
        http.on("POST", "/api/show") { FakeHttp.Answer(body = shown.getJSONObject(it.json().getString("model")).toString()) }
        val l = Lister.list(provider("ollama"), null, http.client)
        assertEquals(listOf("gemma4:26b-nvfp4", "qwen3.5:9b-mlx"), l.models[ModelSlot.Chat])
        assertEquals(emptyList<String>(), l.models[ModelSlot.Stt])
        assertEquals(listOf("gemma4:26b-nvfp4", "qwen3.5:9b-mlx"), l.thinking)
        assertEquals(6604887040L, l.sizes["qwen3.5:9b-mlx"])
        assertEquals(4, l.sizes.size)
        assertTrue(http.asked.all { it.request.url.host == host && it.request.url.port == 11434 })
    }

    @Test fun openAiByName() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/v1/models", body = fixture("openai_models.json"))
        val key = "sk-proj-fake-0000000000000000000000000000"
        val l = Lister.list(provider("openai"), key, http.client)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini", "o3"), l.models[ModelSlot.Chat])
        assertEquals(listOf("gpt-4o-transcribe", "whisper-1"), l.models[ModelSlot.Stt])
        assertEquals(listOf("dall-e-2", "dall-e-3", "gpt-image-1"), l.models[ModelSlot.TextToImage])
        assertEquals(listOf("dall-e-2", "gpt-image-1"), l.models[ModelSlot.ImageToImage])
        assertFalse(l.free)
        val asked = http.asked.single()
        assertEquals("Bearer $key", asked.request.header("Authorization"))
        assertFalse(key in asked.request.url.toString())
    }

    @Test fun aServerOfOnesOwnWithNoKeySendsNone() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/v1/models", body = """{"data":[{"id":"qwen/qwen3-8b","object":"model","owned_by":"organization_owner"},{"id":"qwen2.5-7b-instruct","object":"model","owned_by":"organization_owner"},{"id":"text-embedding-nomic-embed-text-v1.5","object":"model","owned_by":"organization_owner"}]}""")
        val l = Lister.list(provider("lmstudio"), null, http.client)
        // An `-instruct` chat model is chat; only OpenAI's completions model is not.
        assertEquals(listOf("qwen/qwen3-8b", "qwen2.5-7b-instruct"), l.models[ModelSlot.Chat])
        assertEquals(emptySet<ModelSlot>(), Lister.openAi("gpt-3.5-turbo-instruct", JSONObject()))
        assertEquals("Bearer none", http.asked.single().request.header("Authorization"))
    }

    @Test fun openRouterByModalitiesAndParameters() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/api/v1/models", body = fixture("openrouter_models.json"))
        val l = Lister.list(provider("openrouter"), "sk-or-fake", http.client)
        assertEquals(listOf("openai/gpt-4o"), l.models[ModelSlot.Chat])
        assertTrue(l.models.filterKeys { it != ModelSlot.Chat }.values.all { it.isEmpty() })
    }

    @Test fun togetherByTypeFromABareList() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/v1/models", body = fixture("together_models.json"))
        val l = Lister.list(provider("together"), "fake", http.client)
        assertEquals(listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo"), l.models[ModelSlot.Chat])
        assertEquals(listOf("black-forest-labs/FLUX.1-schnell"), l.models[ModelSlot.TextToImage])
        assertEquals(listOf("openai/whisper-large-v3"), l.models[ModelSlot.Stt])
    }

    @Test fun mistralByCapabilitiesThoughItHasAType() = runBlocking {
        val http = FakeHttp()
        http.on("GET", "/v1/models", body = fixture("mistral_models.json"))
        val l = Lister.list(provider("mistral"), "fake", http.client)
        assertEquals(listOf("mistral-large-latest", "voxtral-mini-latest"), l.models[ModelSlot.Chat])
        assertEquals(listOf("voxtral-mini-latest"), l.models[ModelSlot.Stt])
    }

    private fun gemini(free: Boolean = true): FakeHttp = FakeHttp().apply {
        on("GET", "/v1beta/models") { a ->
            FakeHttp.Answer(body = fixture(if (a.request.url.queryParameter("pageToken") == null) "gemini_models_1.json" else "gemini_models_2.json"))
        }
        on("POST", "/models/gemini-2.0-flash-exp:countTokens", 404, fixture("gemini_closed.json"))
        on("POST", ":countTokens", body = fixture("gemini_count.json"))
        if (free) on("POST", "/models/gemini-3.1-pro-preview:generateContent", 429, fixture("gemini_free_tier.json"))
        else on("POST", "/models/gemini-3.1-pro-preview:generateContent", body = fixture("gemini_hi.json"))
    }

    @Test fun geminiBySupportedActionsAndName() = runBlocking {
        val http = gemini()
        val l = Lister.list(provider("gemini"), geminiKey, http.client)
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3.1-pro-preview",
            "gemini-3.1-pro-preview-customtools", "gemma-3-27b-it"), l.models[ModelSlot.Chat])
        assertEquals(l.models[ModelSlot.Chat]!!.filter { it.startsWith("gemini") }, l.models[ModelSlot.Stt])
        assertEquals(listOf("gemini-2.5-flash-image", "imagen-4.0-generate-001"), l.models[ModelSlot.TextToImage])
        assertEquals(listOf("gemini-2.5-flash-image"), l.models[ModelSlot.ImageToImage])
        // Its free tier, from the first Pro model's refusal.
        assertTrue(l.free && l.shares)
        assertEquals("America/Los_Angeles", l.resets)
        // The key in its header, never in a URL.
        assertTrue(http.asked.all { it.request.header("x-goog-api-key") == geminiKey && geminiKey !in it.request.url.toString() })
        // The next page asked for by its token, encoded.
        val second = http.asked.first { it.request.url.queryParameter("pageToken") != null }.request.url
        assertEquals("CgVnZW1+aW5p/LTI=", second.queryParameter("pageToken"))
        assertTrue(second.toString(), "CgVnZW1%2BaW5p" in second.toString())
        // Only the models that could serve a slot were counted.
        assertFalse(http.asked.any { "text-embedding" in it.path || "aqa" in it.path })
    }

    @Test fun geminiPaidKeyAnswered() = runBlocking {
        val http = gemini(free = false)
        assertFalse(Lister.list(provider("gemini"), geminiKey, http.client).free)
    }

    @Test fun aRefusedKeyIsSaidWithoutTheKey() {
        val http = FakeHttp()
        http.on("GET", "/v1beta/models", 400, fixture("gemini_bad_key.json"))
        val e = assertThrows(AskError::class.java) { runBlocking { Lister.list(provider("gemini"), geminiKey, http.client) } }
        assertEquals(400, e.code)
        assertEquals("400: API key not valid. Please pass a valid API key.", e.message)

        val key = "sk-proj-fake-echoed-0000000000000000"
        val echo = FakeHttp().apply { on("GET", "/v1/models", 401, fixture("openai_bad_key.json").replace("{KEY}", key)) }
        val e2 = assertThrows(AskError::class.java) { runBlocking { Lister.list(provider("openai"), key, echo.client) } }
        assertFalse(key in e2.message.orEmpty() || key in e2.body)
        assertTrue("[key]" in e2.message.orEmpty())
    }

    @Test fun aKeyAHeaderCannotCarryIsSaidWithoutIt() {
        val bad = "$geminiKey\u200B"
        val http = FakeHttp()
        val e = assertThrows(AskError::class.java) { runBlocking { Lister.list(provider("gemini"), bad, http.client) } }
        assertFalse(geminiKey in e.message.orEmpty() || geminiKey in e.toString())
        assertTrue(http.asked.isEmpty())
        val e2 = assertThrows(AskError::class.java) { runBlocking { Lister.list(provider("openai"), "\uFEFF" + geminiKey, http.client) } }
        assertFalse(geminiKey in e2.message.orEmpty())
    }

    // The free-tier probe, as the server's tests/test_catalog.py has it.

    private val chat = listOf("gemini-3-flash", "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools", "gemini-2.5-pro")
    private val free = """{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0","status":"RESOURCE_EXHAUSTED"}}"""
    private fun err(code: Int, message: String = "") = """{"error":{"code":$code,"message":"$message","status":""}}"""
    private fun probed(http: FakeHttp) = http.asked.map { it.path.substringAfterLast('/').substringBefore(':') }
    private fun probe(http: FakeHttp, budget: Long = Lister.PROBE_MS) =
        runBlocking { Lister.geminiFree(provider("gemini"), geminiKey, http.client, chat, budget) }

    @Test fun thePlainNameBeforeItsVariantsNewestFirst() {
        val http = FakeHttp().apply { on("POST", ":generateContent", body = fixture("gemini_hi.json")) }
        assertFalse(probe(http))
        assertEquals(listOf("gemini-3.1-pro-preview"), probed(http))
    }

    @Test fun closedAndFailingPassToTheNext() {
        val http = FakeHttp().apply {
            on("POST", "/gemini-3.1-pro-preview:generateContent", 404, err(404))
            on("POST", "/gemini-2.5-pro:generateContent", 504, err(504))
            on("POST", ":generateContent", body = fixture("gemini_hi.json"))
        }
        assertFalse(probe(http))
        assertEquals(listOf("gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-3.1-pro-preview-customtools"), probed(http))
    }

    @Test fun aFailingOneThenTheFreeTier() {
        val http = FakeHttp().apply {
            on("POST", "/gemini-3.1-pro-preview:generateContent", 503, err(503))
            on("POST", "/gemini-2.5-pro:generateContent", 429, free)
        }
        assertTrue(probe(http))
    }

    @Test fun slowCannotTellWithinTheBudget() {
        val http = FakeHttp().apply {
            on("POST", "/gemini-3.1-pro-preview:generateContent") { FakeHttp.Answer(hang = true) }
            on("POST", "/gemini-2.5-pro:generateContent", 429, free)
        }
        val t0 = System.nanoTime()
        assertFalse(probe(http, budget = 50))
        assertTrue((System.nanoTime() - t0) / 1e6 < 2000)
        assertEquals(listOf("gemini-3.1-pro-preview"), probed(http))
    }

    @Test fun aCallFailingCannotTell() {
        // As a call cancelled when the budget runs out, its I/O error ahead of the timeout (seen on a slow CI runner).
        val http = FakeHttp().apply { on("POST", "/gemini-3.1-pro-preview:generateContent") { throw IOException("Canceled") } }
        assertFalse(probe(http))
    }

    @Test fun otherRefusalsSayPaid() {
        val http = FakeHttp().apply { on("POST", "/gemini-3.1-pro-preview:generateContent", 429, err(429, "Resource exhausted")) }
        assertFalse(probe(http))
        assertEquals(listOf("gemini-3.1-pro-preview"), probed(http))
    }

    @Test fun noProModelNoProbe() {
        val http = FakeHttp()
        assertFalse(runBlocking { Lister.geminiFree(provider("gemini"), geminiKey, http.client, listOf("gemini-3-flash")) })
        assertTrue(http.asked.isEmpty())
    }

    @Test fun twinsOfAPlainNameGo() {
        assertEquals(listOf("gemini-3.1-flash-lite", "gpt-4o", "gpt-4o-mini"),
            Lister.twinless(listOf("gemini-3.1-flash-lite", "gemini-3.1-flash-lite-preview", "gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-mini")))
        // A snapshot with no plain name beside it stays.
        assertEquals(listOf("gpt-4-0613"), Lister.twinless(listOf("gpt-4-0613")))
    }

    @Test fun listingsAreKeptWithTheirVersionAndTime() {
        val f = File(tmp.root, "listings.json")
        var now = 1_000L
        val store = ListingsStore(f) { now }
        val l = Listing(mapOf(ModelSlot.Chat to listOf("gemini-2.5-flash")), free = true, sizes = mapOf("x" to 5L),
            thinking = listOf("gemini-2.5-flash"), resets = "America/Los_Angeles", shares = true)
        store.keep("gemini", l)
        val again = ListingsStore(f) { now }
        assertEquals(l.copy(at = 1_000L), again.listed["gemini"]!!.copy(models = again.listed["gemini"]!!.models.filterValues { it.isNotEmpty() }))
        now = 1_000L + 86_400_001L
        assertTrue(again.stale("gemini", 86_400_000L))
        assertTrue(again.stale("openai", 86_400_000L))
        // A file from another version is asked again rather than read.
        f.writeText(f.readText().replace("\"version\": ${ListingsStore.VERSION}", "\"version\": 0"))
        assertTrue(ListingsStore(f).listed.isEmpty())
        store.drop("gemini")
        assertTrue(ListingsStore(f).listed.isEmpty())
    }
}
