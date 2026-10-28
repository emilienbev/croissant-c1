package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.Rates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Prices put to the C1's use, as the server's costs.py: free tiers, a month at your pace, the order, Settings > usage. */
class CostsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = at("2026-10-16T12:00:00Z")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val ollama = Providers.resolve("ollama", ProviderSpec(), "100.100.100.7")
    private val openai = Providers.resolve("openai", ProviderSpec(), "")
    private val gemini = Providers.resolve("gemini", ProviderSpec(), "")
    private val listed = mapOf(
        "ollama" to Listing(mapOf(ModelSlot.Chat to listOf("gemma4:26b-nvfp4", "qwen3.5:9b-mlx")),
            sizes = mapOf("gemma4:26b-nvfp4" to 17_000_000_000L, "qwen3.5:9b-mlx" to 6_000_000_000L), thinking = listOf("qwen3.5:9b-mlx")),
        "openai" to Listing(mapOf(ModelSlot.Chat to listOf("gpt-4o-mini", "gpt-9"))),
        "gemini" to Listing(mapOf(ModelSlot.Chat to listOf("gemini-2.5-flash")), free = true, resets = "America/Los_Angeles", shares = true),
    )

    private val ledger = Ledger(null) { now }
    private val costs by lazy {
        val prices = Prices(File(tmp.root, "prices.json"))
        runBlocking { prices.refresh(FakeHttp().apply { on("GET", "/api.json", body = fixture("models_dev.json")) }.client) }
        Costs(prices, ledger, { listOf(ollama, openai, gemini) }, { listed }, { now }, { ZoneId.of("Europe/London") })
    }

    private fun e(p: Provider, model: String) = Providers.entry(p, model)

    private fun add(iso: String, slot: ModelSlot, model: String, c: Counts) {
        now = at(iso)
        ledger.add(slot, model, c)
    }

    @Test fun ollamaAndAFreeTierCostNothingTheRestTheirRates() {
        assertTrue(costs.costsNothing(e(ollama, "qwen3.5:9b-mlx")))
        assertNull(costs.price(e(ollama, "qwen3.5:9b-mlx")))
        assertEquals(0.0, costs.cost(e(ollama, "qwen3.5:9b-mlx"), Counts(input = 1e6))!!, 0.0)
        assertTrue(costs.freeTier("gemini"))
        assertEquals(0.0, costs.cost(e(gemini, "gemini-2.5-flash"), Counts(input = 1e6, out = 1e6))!!, 0.0)
        assertFalse(costs.costsNothing(e(openai, "gpt-4o-mini")))
        assertEquals(0.75, costs.cost(e(openai, "gpt-4o-mini"), Counts(input = 1e6, out = 1e6))!!, 1e-12)
        assertNull(costs.cost(e(openai, "gpt-9"), Counts(input = 1e6)))
    }

    @Test fun aMonthAtYourPaceAfterAWeekOfUse() {
        assertNull(costs.pace(ModelSlot.Chat))
        add("2026-10-13T12:00:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 1000.0))
        now = at("2026-10-16T12:00:00Z")
        assertNull(costs.pace(ModelSlot.Chat))      // three days
        add("2026-10-06T12:00:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 1000.0, out = 100.0))
        add("2026-10-15T12:00:00Z", ModelSlot.Chat, "ollama:qwen3.5:9b-mlx", Counts(input = 1000.0))
        add("2026-10-15T13:00:00Z", ModelSlot.Stt, "openai:whisper-1", Counts(secs = 60.0))
        add("2026-10-15T14:00:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 999.0, live = 5.0))
        now = at("2026-10-16T12:00:00Z")
        // Ten days of use, whatever ran it, the live pass left out: three times that for a month.
        assertEquals(Counts(input = 9000.0, out = 300.0), costs.pace(ModelSlot.Chat))
        assertEquals(Counts(secs = 180.0), costs.pace(ModelSlot.Stt))
        val month = costs.list(ModelSlot.Chat, listOf(e(openai, "gpt-4o-mini")), null).entries.single().month!!
        assertEquals((9000 * 0.15 + 300 * 0.6) / 1e6, month, 1e-12)
    }

    @Test fun aFreeTiersRequestsTodayFromMidnightWhereItsDayStarts() {
        // Midnight in Los Angeles is 07:00 UTC.
        add("2026-10-16T06:00:00Z", ModelSlot.Chat, "gemini:gemini-2.5-flash", Counts(input = 1.0))
        add("2026-10-16T08:00:00Z", ModelSlot.Chat, "gemini:gemini-2.5-flash", Counts(input = 1.0))
        add("2026-10-16T09:00:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 1.0))
        add("2026-10-16T11:00:00Z", ModelSlot.Chat, "gemini:gemini-2.5-flash", Counts(input = 1.0))
        now = at("2026-10-16T12:00:00Z")
        assertEquals(mapOf("gemini:gemini-2.5-flash" to 2), costs.today())
        assertEquals(2, costs.list(ModelSlot.Chat, listOf(e(gemini, "gemini-2.5-flash")), null).entries.single().today)
    }

    @Test fun aSlotsModelsCheapestFirstWithinEachProviderWithWhatEachCosts() {
        val entries = listOf(e(ollama, "gemma4:26b-nvfp4"), e(ollama, "qwen3.5:9b-mlx"), e(openai, "gpt-9"), e(openai, "gpt-4o-mini"),
            e(gemini, "gemini-1.5-flash"), e(gemini, "gemini-2.5-flash"))
        val list = costs.list(ModelSlot.Chat, entries, null)
        assertNull(list.default)
        // Free and smaller first; priced; unknown last; deprecated left out.
        assertEquals(listOf("ollama:qwen3.5:9b-mlx", "ollama:gemma4:26b-nvfp4", "openai:gpt-4o-mini", "openai:gpt-9", "gemini:gemini-2.5-flash"),
            list.entries.map { it.id })
        val (qwen, gemma, mini, unknown, flash) = list.entries
        assertTrue(qwen.local && qwen.costsNothing)
        assertEquals(true, qwen.thinks)
        assertEquals(false, gemma.thinks)
        assertEquals(6_000_000_000L, qwen.bytes)
        assertNull(qwen.title)
        assertEquals("GPT-4o mini", mini.title)
        assertEquals(Rates(0.15, 0.6, 0.08), mini.rates)
        assertEquals(false, mini.thinks)
        assertNull(mini.month)
        assertNull(mini.unit)
        assertNull(unknown.title)
        assertNull(unknown.thinks)
        // Google's maker's name left out under "Google Gemini"; a free tier says when its day starts.
        assertEquals("2.5 Flash", flash.title)
        assertEquals("Google Gemini", flash.provider)
        assertTrue(flash.free && flash.shares)
        assertEquals("America/Los_Angeles", flash.resets)
        assertEquals(0, flash.today)
        assertNull(mini.today)
        // The default is kept, deprecated or not.
        val d = costs.list(ModelSlot.Chat, entries, e(gemini, "gemini-1.5-flash"))
        assertEquals("gemini:gemini-1.5-flash", d.default)
        assertTrue("gemini:gemini-1.5-flash" in d.entries.map { it.id })
        // An hour of speech: 3,600 s heard at 32 tokens and 12,000 written.
        assertEquals((3600 * 32 * 0.15 + 12000 * 0.6) / 1e6, costs.about(ModelSlot.Stt, e(openai, "gpt-4o-mini"), null, emptyMap()).unit!!, 1e-12)
    }

    @Test fun usageTheLastThirtyDaysFromMidnightHere() {
        // Here is London, an hour ahead of UTC: the window opens at 23:00 UTC on 16 September.
        add("2026-09-16T22:30:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 1e6))
        add("2026-09-16T23:30:00Z", ModelSlot.Chat, "openai:gpt-4o-mini", Counts(input = 1e6))
        add("2026-10-15T10:00:00Z", ModelSlot.Stt, "openai:whisper-1", Counts(secs = 60.0))
        add("2026-10-16T08:00:00Z", ModelSlot.Chat, "ollama:qwen3.5:9b-mlx", Counts(input = 4000.0, out = 40.0))
        add("2026-10-16T09:00:00Z", ModelSlot.Chat, "gone:model", Counts(input = 10.0))
        add("2026-10-16T09:30:00Z", ModelSlot.Chat, "ollama:qwen3.5:9b-mlx", Counts(input = 1.0))
        now = at("2026-10-16T12:00:00Z")
        val u = costs.usage()
        assertEquals(30, u.days)
        assertEquals(30, u.daily.size)
        assertEquals(0.15, u.total, 1e-12)
        assertEquals(0.15, u.daily[0], 1e-12)
        assertEquals(0.0, u.daily[29], 0.0)
        val chat = u.slots.getValue(ModelSlot.Chat)
        assertEquals(0.15, chat.cost, 1e-12)
        assertFalse(chat.free)
        assertFalse(chat.priced)       // the model of a provider gone
        assertEquals(1_000_000L + 4000 + 40 + 10 + 1, chat.tokens)
        val stt = u.slots.getValue(ModelSlot.Stt)
        assertEquals(60L, stt.secs)
        assertFalse(stt.priced)
        assertFalse(u.priced)
    }

    @Test fun theMakersNameOnlyWhenTheProvidersSaysIt() {
        assertEquals("2.5 Flash", Costs.short("Gemini 2.5 Flash", "Google Gemini"))
        assertEquals("GPT-4o", Costs.short("GPT-4o", "OpenAI"))
        assertEquals("Mistral Small", Costs.short("Mistral Small", "Groq"))
        assertEquals("Small", Costs.short("Mistral Small", "Mistral"))
    }
}
