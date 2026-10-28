package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** models.dev's catalogue as the server's prices.py reads it, and a model's cost at its rates. */
class PricesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun catalogue() = Prices.read(Buffer().writeUtf8(fixture("models_dev.json")))

    @Test fun readAsItStreamsKeepingThePresetsProvidersOnly() {
        val by = catalogue()
        assertEquals(Prices.SOURCES, by.keys)
        assertEquals(setOf("openai", "google", "groq", "openrouter", "mistral", "togetherai", "deepseek"), Prices.SOURCES)
        assertEquals(setOf("gemini-2.5-flash", "gemini-1.5-flash", "gemma-3-27b-it"), by.getValue("google").keys)
        assertEquals(Price("Gemini 2.5 Flash", 0.3, 2.5, 0.075, 1.0, thinks = true, context = 1048576, maxOut = 65536, images = true),
            by.getValue("google")["gemini-2.5-flash"])
        val old = by.getValue("google").getValue("gemini-1.5-flash")
        assertTrue(old.deprecated)
        assertNull(old.context)            // a limit of 0 is unknown
        assertEquals(8192L, old.maxOut)
        assertFalse(old.images)
        // No name: its id.
        assertEquals("gemma-3-27b-it", by.getValue("google").getValue("gemma-3-27b-it").name)
        assertNull(by.getValue("openai").getValue("gpt-image-1").output)
        assertEquals(setOf("mistral-small-latest"), by.getValue("mistral").keys)
        // A preset the catalogue lacks has nothing.
        assertEquals(emptyMap<String, Price>(), by.getValue("togetherai"))
    }

    @Test fun aCostAtItsRates() {
        val p = Price("GPT-4o mini", input = 0.15, output = 0.6, cached = 0.08)
        // Half the prompt read from the cache, at its own rate.
        assertEquals((500_000 * 0.15 + 500_000 * 0.08 + 1_000_000 * 0.6) / 1e6, p.cost(Counts(input = 1e6, cached = 5e5, out = 1e6))!!, 1e-12)
        // No cached rate: the input's. Audio with no rate of its own: the input's, 32 tokens a second.
        val q = Price("Q", input = 1.0, output = 2.0)
        assertEquals((10.0 + 3600 * 32 * 1.0) / 1e6, q.cost(Counts(input = 10.0, cached = 10.0, secs = 3000.0, live = 600.0))!!, 1e-12)
        // A picture is 1,290 tokens written.
        assertEquals(1290 * 2.0 / 1e6, q.cost(Counts(pictures = 1.0))!!, 1e-12)
        // A count with no rate for it: no price at all.
        assertNull(Price("R", input = 1.0).cost(Counts(input = 10.0, out = 1.0)))
        assertEquals(0.0, Price("S").cost(Counts())!!, 0.0)
    }

    @Test fun fetchedWhenADayOldAndKept() = runBlocking {
        val http = FakeHttp().apply { on("GET", "/api.json", body = fixture("models_dev.json")) }
        val f = File(tmp.root, "prices.json")
        val said = mutableListOf<String>()
        val prices = Prices(f, log = { said += it })
        prices.refresh(http.client)
        assertEquals(1, http.asked.size)
        assertEquals("https://models.dev/api.json", http.asked[0].request.url.toString())
        assertEquals(catalogue(), prices.bySource)
        assertTrue(said.single().startsWith("prices from models.dev for 6 models"))
        // The kept copy is read back the same.
        assertEquals(prices.bySource, Prices(f).bySource)
        val openai = Providers.resolve("openai", ProviderSpec(), "")
        assertEquals(0.15, Prices(f).get(openai, "gpt-4o-mini")!!.input!!, 0.0)
        assertNull(prices.get(Providers.resolve("lmstudio", ProviderSpec(), "100.100.100.7"), "gpt-4o-mini"))
        // A day has not passed: not asked again.
        prices.refresh(http.client)
        assertEquals(1, http.asked.size)
        f.setLastModified(System.currentTimeMillis() - Prices.MAX_AGE_MS - 60_000)
        prices.refresh(http.client)
        assertEquals(2, http.asked.size)
    }

    @Test fun offlineTheKeptCopyStays() = runBlocking {
        val f = File(tmp.root, "prices.json")
        Prices(f).refresh(FakeHttp().apply { on("GET", "/api.json", body = fixture("models_dev.json")) }.client)
        f.setLastModified(System.currentTimeMillis() - Prices.MAX_AGE_MS - 60_000)
        val said = mutableListOf<String>()
        val prices = Prices(f, log = { said += it })
        prices.refresh(FakeHttp().apply { on("GET", "/api.json", code = 503, body = "{\"error\": \"down\"}") }.client)
        assertEquals(catalogue(), prices.bySource)
        assertEquals("could not fetch prices from models.dev: 503: down", said.single())
    }
}
