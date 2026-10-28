package com.bleural.croissant.served

import com.bleural.croissant.ask.AskError
import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.Listing
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.send.Response
import com.bleural.croissant.served.StoreConfig.Companion.GEMINI
import com.bleural.croissant.served.StoreConfig.Companion.OPENAI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Every endpoint of the page's JSON, on the real stores (ConfigServer's checks are ConfigServerTest's). */
class ConfigApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val config by lazy { StoreConfig(tmp.root) }
    private val api by lazy { ConfigApi(config, keys = true) }

    private fun call(method: String, path: String, body: Any? = null, a: ConfigApi = api): Pair<Int, JSONObject> {
        val r: Response = a.handle(method, path, body?.toString()?.toByteArray() ?: ByteArray(0))
        assertEquals(ConfigApi.JSON, r.type)
        return r.status to JSONObject(String(r.body))
    }

    private fun ok(method: String, path: String, body: Any? = null): JSONObject =
        call(method, path, body).also { (s, o) -> assertEquals(o.toString(), 200, s) }.second

    private fun refused(status: Int, method: String, path: String, body: Any? = null): String =
        call(method, path, body).also { (s, o) -> assertEquals(o.toString(), status, s) }.second.getString("error")

    private fun JSONObject.provider(id: String): JSONObject? = getJSONArray("providers").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    }

    @Test fun providersListOllamaAddedAndThePresetsNot() {
        val o = ok("GET", "providers")
        assertTrue(o.getBoolean("keys"))
        val ollama = o.provider("ollama")!!
        assertEquals("http://100.100.100.7:11434", ollama.getString("url"))
        assertTrue(ollama.isNull("address") && ollama.getBoolean("mac") && ollama.getBoolean("ready"))
        assertTrue(ollama.isNull("key") && ollama.isNull("listed"))
        // On the server's machine, by its name; the way, when the server says it.
        assertEquals("macbook", ollama.getString("on"))
        assertTrue(o.isNull("way"))
        assertEquals("wifi", call("GET", "providers", a = ConfigApi(config, keys = true, way = Way.Wifi)).second.getString("way"))
        val presets = o.getJSONArray("presets").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(listOf("lmstudio", "openai", "gemini", "groq", "openrouter", "mistral", "together", "deepseek"), presets)
    }

    @Test fun aProviderIsAddedOnceAndOnesOwnNeedsItsKindAndAddress() {
        assertTrue(ok("POST", "providers", JSONObject().put("id", "gemini")).provider("gemini")!!.let { !it.getBoolean("ready") })
        refused(400, "POST", "providers", JSONObject().put("id", "gemini"))
        refused(400, "POST", "providers", JSONObject().put("id", "mybox"))
        refused(400, "POST", "providers", JSONObject().put("id", "mybox").put("kind", "anthropic").put("url", "http://x/v1"))
        refused(400, "POST", "providers", JSONObject().put("id", "mybox").put("kind", "openai").put("url", "ftp://x"))
        refused(400, "POST", "providers", JSONObject().put("id", 7))
        refused(400, "POST", "providers", JSONObject())
        val mine = ok("POST", "providers", JSONObject().put("id", "mybox").put("kind", "openai").put("url", "http://100.100.100.9:8000/v1")
            .put("title", "My box")).provider("mybox")!!
        assertEquals("My box", mine.getString("title"))
        assertTrue(mine.getBoolean("ready"))
    }

    @Test fun anAddressIsSetAndUnsetAndANewOneDropsTheKey() {
        ok("PUT", "providers/gemini/key", JSONObject().put("key", GEMINI))
        val moved = ok("PUT", "providers/ollama/address", JSONObject().put("url", " http://10.0.0.2:11434 ")).provider("ollama")!!
        assertEquals("http://10.0.0.2:11434", moved.getString("address"))
        assertEquals("http://10.0.0.2:11434", moved.getString("url"))
        assertTrue(ok("PUT", "providers/ollama/address", JSONObject().put("url", "")).provider("ollama")!!.isNull("address"))
        refused(400, "PUT", "providers/ollama/address", JSONObject().put("url", "10.0.0.2"))
        refused(404, "PUT", "providers/groq/address", JSONObject().put("url", "http://x/v1"))
        assertTrue(config.hasKey("gemini"))
        assertFalse(ok("PUT", "providers/gemini/address", JSONObject().put("url", "https://proxy.example/v1")).provider("gemini")!!.getBoolean("hasKey"))
    }

    @Test fun aKeyIsSetAndOnlyItsLastFourComeBack() {
        val o = ok("PUT", "providers/gemini/key", JSONObject().put("key", GEMINI))
        val g = o.provider("gemini")!!
        assertEquals("MNOP", g.getString("key"))
        assertTrue(g.getBoolean("hasKey") && g.getBoolean("ready"))
        assertEquals(GEMINI, config.key("gemini"))
        assertFalse(o.toString().contains(GEMINI.takeLast(8)))
        val why = refused(400, "PUT", "providers/gemini/key", JSONObject().put("key", "$GEMINI​"))
        assertFalse(why.contains(GEMINI.takeLast(8)))
        refused(400, "PUT", "providers/gemini/key", JSONObject())
        refused(400, "PUT", "providers/nobody/key", JSONObject().put("key", OPENAI))
        assertEquals(GEMINI, config.key("gemini"))
    }

    @Test fun overPlainWifiAKeyIsRefusedAndNotKept() {
        val wifi = ConfigApi(config, keys = false)
        val (s, o) = call("PUT", "providers/gemini/key", JSONObject().put("key", GEMINI), wifi)
        assertEquals(403, s)
        assertEquals(ConfigApi.KEYS_WIFI, o.getString("error"))
        assertFalse(config.hasKey("gemini"))
        assertFalse(call("GET", "providers", a = wifi).second.getBoolean("keys"))
        // Prompts and addresses go over either.
        assertEquals(200, call("PUT", "providers/ollama/address", JSONObject().put("url", "http://10.0.0.2:11434"), wifi).first)
        assertEquals(403, call("POST", "providers/gemini/try", JSONObject().put("key", GEMINI), wifi).first)
        assertFalse(config.hasKey("gemini"))
        assertTrue("decided: keys over Wi-Fi too, with a warning", ConfigServer.KEYS_OVER_PLAIN_WIFI)
    }

    @Test fun aListingIsKeptAndItsErrorNeverCarriesTheKey() {
        ok("PUT", "providers/gemini/key", JSONObject().put("key", GEMINI))
        config.lister = { Result.success(Listing(mapOf(ModelSlot.Chat to listOf("gemini-2.5-flash", "gemini-2.5-pro"), ModelSlot.Stt to listOf("gemini-2.5-flash")), free = true)) }
        val g = ok("POST", "providers/gemini/list").provider("gemini")!!
        assertEquals(2, g.getJSONObject("listed").getInt("chat"))
        assertEquals(1, g.getJSONObject("listed").getInt("stt"))
        assertTrue(g.getBoolean("free"))
        // A provider that quotes the key back, past Lister's own redaction.
        config.lister = { Result.failure(AskError(400, "API key not valid: $GEMINI")) }
        val why = refused(502, "POST", "providers/gemini/list")
        assertTrue(why, why.startsWith("API key not valid"))
        assertFalse(why.contains(GEMINI.takeLast(8)))
        refused(404, "POST", "providers/groq/list")
    }

    @Test fun aProviderIsTriedBeforeAnythingIsKept() {
        // Refused: nothing kept, and the provider's words come back without the key.
        config.lister = { Result.failure(AskError(401, "401: Incorrect API key provided: $OPENAI")) }
        val why = refused(502, "POST", "providers/openai/try", JSONObject().put("key", OPENAI))
        assertTrue(why, why.contains("Incorrect API key"))
        assertFalse(why.contains(OPENAI.takeLast(8)))
        assertTrue(ok("GET", "providers").provider("openai") == null)
        assertFalse(config.hasKey("openai"))
        // One that cannot be is the caller's mistake.
        refused(400, "POST", "providers/mybox/try", JSONObject())
        refused(400, "POST", "providers/openai/try", JSONObject().put("key", "two words"))
        refused(400, "POST", "providers/mybox/try", JSONObject().put("kind", "anthropic"))
        // Listed: kept, its key by its last four, its models counted.
        config.lister = { Result.success(Listing(mapOf(ModelSlot.Chat to listOf("gpt-5-mini")))) }
        val o = ok("POST", "providers/openai/try", JSONObject().put("key", OPENAI)).provider("openai")!!
        assertEquals(OPENAI.takeLast(4), o.getString("key"))
        assertEquals(1, o.getJSONObject("listed").getInt("chat"))
        assertEquals(OPENAI, config.key("openai"))
        // One's own, by its kind and address; an added one with a new address, the rest as it was.
        val mine = ok("POST", "providers/mybox/try", JSONObject().put("kind", "openai").put("url", "http://100.100.100.9:8000/v1").put("title", "My box"))
        assertEquals("My box", mine.provider("mybox")!!.getString("title"))
        assertEquals(null, config.tried!!.third)
        ok("POST", "providers/mybox/try", JSONObject().put("url", "http://100.100.100.9:9000/v1"))
        assertEquals(ProviderSpec(Kind.OpenAi, "http://100.100.100.9:9000/v1", "My box"), config.tried!!.second)
    }

    @Test fun aProviderIsRemovedWithItsKeyAndOllamaGoesBackToItsPreset() {
        ok("PUT", "providers/gemini/key", JSONObject().put("key", GEMINI))
        assertTrue(ok("DELETE", "providers/gemini").provider("gemini") == null)
        assertFalse(config.hasKey("gemini"))
        refused(404, "DELETE", "providers/gemini")
        ok("PUT", "providers/ollama/address", JSONObject().put("url", "http://10.0.0.2:11434"))
        assertTrue(ok("DELETE", "providers/ollama").provider("ollama")!!.isNull("address"))
    }

    @Test fun promptsAreListedWithoutTextsAndOneWithItsText() {
        val o = ok("GET", "prompts")
        val a = o.getJSONArray("prompts")
        assertEquals(listOf("default", "gemma"), (0 until a.length()).map { a.getJSONObject(it).getString("name") })
        val d = a.getJSONObject(0)
        assertTrue(d.getBoolean("default") && !d.getBoolean("edited") && !d.has("text"))
        assertEquals("Be brief.".length, d.getInt("chars"))
        assertEquals("Be Gemma.", ok("GET", "prompts/gemma").getString("text"))
        refused(404, "GET", "prompts/nobody")
    }

    @Test fun aPromptIsAddedSavedRenamedResetAndDeletedByTheStoresRules() {
        ok("POST", "prompts", JSONObject().put("name", "long one").put("from", "gemma"))
        assertEquals("Be Gemma.", ok("GET", "prompts/long%20one").getString("text"))
        refused(400, "POST", "prompts", JSONObject().put("name", "Long One"))
        refused(400, "POST", "prompts", JSONObject().put("name", "x".repeat(25)))
        refused(404, "POST", "prompts", JSONObject().put("name", "other").put("from", "nobody"))

        val long = "Line one.\r\n" + "word ".repeat(4000)
        val saved = ok("PUT", "prompts/gemma", JSONObject().put("text", long))
        assertTrue(saved.getBoolean("edited"))
        assertEquals(long.replace("\r\n", "\n"), saved.getString("text"))
        refused(400, "PUT", "prompts/gemma", JSONObject())
        refused(404, "PUT", "prompts/nobody", JSONObject().put("text", "x"))

        val reset = ok("POST", "prompts/gemma/reset")
        assertEquals("Be Gemma.", reset.getString("text"))
        assertFalse(reset.getBoolean("edited"))

        config.pickPrompt("ollama:qwen3.5:9b-mlx", "long one")
        val renamed = ok("POST", "prompts/long%20one/rename", JSONObject().put("to", "a+b"))
        assertEquals("a+b", renamed.getJSONObject("picks").getString("ollama:qwen3.5:9b-mlx"))
        assertEquals("Be Gemma.", ok("GET", "prompts/a%2Bb").getString("text"))
        assertEquals("Be Gemma.", ok("GET", "prompts/a+b").getString("text"))
        refused(400, "POST", "prompts/default/rename", JSONObject().put("to", "main"))
        refused(400, "POST", "prompts/gemma/rename", JSONObject().put("to", "A+B"))

        refused(400, "DELETE", "prompts/default")
        assertFalse(ok("DELETE", "prompts/a+b").getJSONObject("picks").has("ollama:qwen3.5:9b-mlx"))
        refused(404, "DELETE", "prompts/a+b")
        assertEquals(listOf("default", "gemma"), config.prompts.variants.map { it.name })
    }

    @Test fun whatIsNotOursGetsNothing() {
        refused(404, "GET", "nothing")
        refused(404, "PATCH", "providers")
        refused(404, "GET", "prompts/%zz")
        // Bad JSON is said to be bad, never quoted back: Android's org.json would quote the whole body.
        val body = """{"key": "$GEMINI" """
        val (s, o) = api.handle("PUT", "providers/gemini/key", body.toByteArray()).let { it.status to JSONObject(String(it.body)) }
        assertEquals(400, s)
        assertEquals("that is not JSON", o.getString("error"))
        assertFalse(config.hasKey("gemini"))
        // org.json recurses per bracket: a deep body runs out of stack, and is still only refused.
        val deep = api.handle("PUT", "prompts/gemma", ("{\"text\":" + "[".repeat(250_000)).toByteArray())
        assertEquals(400, deep.status)
        refused(400, "PUT", "prompts/gemma", JSONObject().put("text", "x".repeat(ConfigFile.TEXT_MAX + 1)))
    }
}
