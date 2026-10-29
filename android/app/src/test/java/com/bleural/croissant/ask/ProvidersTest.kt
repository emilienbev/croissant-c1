package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.net.ModelSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProvidersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val host = "100.100.100.7"

    @Test fun theServersPresetsLessLocal() {
        assertEquals(listOf("ollama", "lmstudio", "openai", "gemini", "groq", "openrouter", "mistral", "together", "deepseek"),
            Providers.PRESETS.keys.toList())
    }

    @Test fun aProviderOnTheMacIsAtTheServersHostNeverTheR1() {
        val ollama = Providers.resolve("ollama", ProviderSpec(), host)
        assertEquals("http://$host:11434", ollama.url)
        assertTrue(ollama.mac && ollama.ready(false))
        assertEquals("http://$host:1234/v1", Providers.resolve("lmstudio", ProviderSpec(), host).url)
        assertEquals("http://[fd7a::1]:11434", Providers.resolve("ollama", ProviderSpec(), "fd7a::1").url)
    }

    @Test fun beforeAServerIsSetTheMacsProvidersHaveNowhereToGo() {
        assertFalse(Providers.resolve("ollama", ProviderSpec(), "").ready(false))
        assertFalse(Providers.resolve("lmstudio", ProviderSpec(), "").ready(false))
    }

    @Test fun anAddressGivenIsKeptAsGiven() {
        val p = Providers.resolve("ollama", ProviderSpec(url = "http://10.0.0.2:11434/"), host)
        assertEquals("http://10.0.0.2:11434", p.url)
        assertFalse(p.mac)
        assertThrows(ProvidersError::class.java) { Providers.resolve("ollama", ProviderSpec(url = "10.0.0.2:11434"), host) }
    }

    @Test fun aHostedPresetNeedsItsKey() {
        val gemini = Providers.resolve("gemini", ProviderSpec(), host)
        assertEquals(Providers.GEMINI_URL, gemini.url)
        assertFalse(gemini.ready(false))
        assertTrue(gemini.ready(true))
        assertTrue(Providers.resolve("groq", ProviderSpec(), host).needsKey)
    }

    @Test fun aServerOfOnesOwnIsAnOpenAiUrlWithAKeyOptional() {
        val mine = Providers.resolve("mybox", ProviderSpec(Kind.OpenAi, "http://100.100.100.9:8000/v1", "My box"), host)
        assertEquals(Kind.OpenAi, mine.kind)
        assertEquals("My box", mine.title)
        assertTrue(mine.ready(false))
        assertThrows(ProvidersError::class.java) { Providers.resolve("mybox", ProviderSpec(Kind.OpenAi), host) }
        assertThrows(ProvidersError::class.java) { Providers.resolve("mybox", ProviderSpec(), host) }
        assertThrows(ProvidersError::class.java) { Providers.resolve("my box", ProviderSpec(Kind.OpenAi, "http://x/v1"), host) }
        // A second key for a preset names it.
        assertEquals("https://api.openai.com/v1", Providers.resolve("openai-work", ProviderSpec(preset = "openai"), host).url)
    }

    @Test fun entriesAndFind() {
        val ollama = Providers.resolve("ollama", ProviderSpec(), host)
        val gemini = Providers.resolve("gemini", ProviderSpec(), host)
        val listed = mapOf("ollama" to Listing(mapOf(ModelSlot.Chat to listOf("gemma4:26b-nvfp4", "qwen3.5:9b-mlx"))),
            "gemini" to Listing(mapOf(ModelSlot.Chat to listOf("gemini-2.5-flash"), ModelSlot.Stt to listOf("gemini-2.5-flash"))))
        assertEquals(listOf("ollama:gemma4:26b-nvfp4", "ollama:qwen3.5:9b-mlx", "gemini:gemini-2.5-flash"),
            Providers.entries(ModelSlot.Chat, listOf(ollama, gemini), listed::get).map { it.id })
        val e = Providers.find("ollama:qwen3.5:9b-mlx", listOf(ollama))!!
        assertEquals("qwen3.5:9b-mlx", e.model)
        assertEquals(ollama, e.provider)
        assertNull(Providers.find("openai:gpt-4o", listOf(ollama)))
        assertNull(Providers.find("ollama", listOf(ollama)))
        assertEquals("Llama-3.3-70B-Instruct-Turbo", Providers.short("meta-llama/Llama-3.3-70B-Instruct-Turbo"))
    }

    private fun kept(): AskProviders = AskProviders(ProvidersStore(File(tmp.root, "providers.json")),
        KeysStore(File(tmp.root, "keys.json"), SoftSealer()), ListingsStore(File(tmp.root, "listings.json")))

    @Test fun aKeyGoesOnlyToTheAddressItWasTypedFor() {
        val ask = kept()
        assertNull(ask.save("mybox", ProviderSpec(Kind.OpenAi, "http://100.100.100.9:8000/v1", "My box")))
        assertNull(ask.saveKey("mybox", "box-key-0000000000"))
        // Its name changed, its address not: the key stays.
        assertNull(ask.save("mybox", ProviderSpec(Kind.OpenAi, "http://100.100.100.9:8000/v1/", "Box")))
        assertTrue(ask.hasKey("mybox"))
        // Another address: the key goes.
        assertNull(ask.save("mybox", ProviderSpec(Kind.OpenAi, "http://203.0.113.5:8000/v1", "Box")))
        assertFalse(ask.hasKey("mybox"))
        // A preset given an address of its own, likewise.
        assertNull(ask.saveKey("openai", "sk-proj-fake-0000000000"))
        assertNull(ask.save("openai", ProviderSpec(url = "https://proxy.example/v1")))
        assertFalse(ask.hasKey("openai"))
    }

    @Test fun aKeyAddsItsProviderAndRemovingTakesAll() {
        val ask = kept()
        assertNull(ask.saveKey("gemini", "AIza00000000000000000000000000000000MNOP"))
        assertEquals(listOf("ollama", "gemini"), ask.ready(host).map { it.id }.sortedBy { it != "ollama" })
        assertEquals("MNOP", ask.hint("gemini"))
        assertNotNull(ask.saveKey("nobody", "key-0000000000"))
        assertNull(ask.remove("gemini"))
        assertFalse(ask.hasKey("gemini"))
        assertEquals(listOf("ollama"), ask.all(host).map { it.id })
    }

    @Test fun aProviderTriedIsKeptOnlyIfItLists() = runBlocking {
        val ask = kept()
        val key = "sk-proj-fake-0000000000000000wxyz"
        val refused = FakeHttp().apply { on("GET", "/v1/models", 401, fixture("openai_bad_key.json").replace("{KEY}", key)) }
        val e = runCatching { ask.tryOut("openai", ProviderSpec(), key, host, refused.client) }.exceptionOrNull()!!
        assertTrue(e.message, e is AskError && e.code == 401 && !e.message!!.contains(key))
        assertEquals(listOf("ollama"), ask.all(host).map { it.id })
        assertFalse(ask.hasKey("openai"))
        assertNull(ask.listing("openai"))
        // A key that cannot be is refused before anything is asked.
        assertThrows(ProvidersError::class.java) { runBlocking { ask.tryOut("openai", ProviderSpec(), "two words", host, refused.client) } }
        assertEquals(1, refused.asked.size)

        val listed = FakeHttp().apply { on("GET", "/v1/models", body = fixture("openai_models.json")) }
        val l = ask.tryOut("openai", ProviderSpec(), key, host, listed.client)
        assertEquals("Bearer $key", listed.asked.single().request.header("Authorization"))
        assertTrue(l.models[ModelSlot.Chat].orEmpty().isNotEmpty())
        assertTrue(ask.hasKey("openai"))
        assertEquals("wxyz", ask.hint("openai"))
        assertEquals(l, ask.listing("openai"))

        // Tried again with no key: the kept one, while the address stays; a new address goes without it, and is kept only if it lists.
        ask.tryOut("openai", ProviderSpec(), null, host, listed.client)
        assertEquals("Bearer $key", listed.asked.last().request.header("Authorization"))
        val moved = ProviderSpec(url = "https://proxy.example/v1")
        runCatching { ask.tryOut("openai", moved, null, host, refused.client) }
        assertNotEquals("Bearer $key", refused.asked.last().request.header("Authorization"))
        assertEquals(ProviderSpec(), ask.spec("openai"))
        assertTrue(ask.hasKey("openai"))
    }

    @Test fun theStoreKeepsOllamaFirstAndAlways() {
        val f = File(tmp.root, "providers.json")
        val store = ProvidersStore(f)
        assertEquals(listOf("ollama"), store.providers(host).map { it.id })
        assertNull(store.save("gemini", ProviderSpec()))
        assertNull(store.save("mybox", ProviderSpec(Kind.OpenAi, "http://100.100.100.9:8000/v1")))
        assertNotNull(store.save("bad", ProviderSpec()))
        val again = ProvidersStore(f)
        assertEquals("ollama", again.providers(host).first().id)
        assertEquals(setOf("ollama", "gemini", "mybox"), again.providers(host).map { it.id }.toSet())
        assertNull(again.remove("mybox"))
        assertNull(again.remove("ollama"))
        assertEquals(setOf("ollama", "gemini"), ProvidersStore(f).providers(host).map { it.id }.toSet())
        assertFalse("key" in f.readText())
    }
}
