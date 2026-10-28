package com.bleural.croissant.served

import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.served.StoreConfig.Companion.GEMINI
import com.bleural.croissant.served.StoreConfig.Companion.OPENAI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** One C1's settings exported and imported on another, merged; and every file that is not ours refused whole. */
class ConfigFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var first: StoreConfig
    private lateinit var second: StoreConfig
    private val qwen = "ollama:qwen3.5:9b-mlx"
    private val gemma = "ollama:gemma4:26b-nvfp4"

    @Before fun twoR1s() {
        first = StoreConfig(File(tmp.root, "first").apply { mkdirs() })
        second = StoreConfig(File(tmp.root, "second").apply { mkdirs() })
        first.saveProvider("ollama", ProviderSpec(url = "http://10.0.0.2:11434"))
        first.saveKey("gemini", GEMINI)
        first.saveKey("openai", OPENAI)
        first.saveProvider("mybox", ProviderSpec(Kind.OpenAi, "http://100.100.100.9:8000/v1", "My box"))
        first.savePrompt("default", "Be very brief.")
        first.addPrompt("terse", null)
        first.savePrompt("terse", "One line.\nNo more.")
        first.pickPrompt(qwen, "terse")
        first.pickPrompt(gemma, "gemma")
        first.pickModel(ModelSlot.Chat, qwen)
        first.pickModel(ModelSlot.Stt, "gemini:gemini-2.5-flash")
    }

    private fun exported(c: StoreConfig = first, keys: Boolean = true) = JSONObject(ConfigFile.export(c, keys).toString())

    @Test fun exportedAndImportedTheSecondHoldsWhatTheFirstDoes() {
        val counted = ConfigFile.apply(second, ConfigFile.read(exported(), keys = true))
        assertEquals(4, counted.getInt("providers"))
        assertEquals(2, counted.getInt("keys"))
        assertEquals(setOf("ollama", "gemini", "openai", "mybox"), second.providers().map { it.id }.toSet())
        assertEquals("http://10.0.0.2:11434", second.providers().first { it.id == "ollama" }.url)
        assertEquals("My box", second.providers().first { it.id == "mybox" }.title)
        assertEquals(GEMINI, second.key("gemini"))
        assertEquals(OPENAI, second.key("openai"))
        assertEquals(first.prompts, second.prompts)
        assertEquals(first.modelPicks, second.modelPicks)
        // And back again changes nothing.
        ConfigFile.apply(first, ConfigFile.read(exported(second), keys = true))
        assertEquals(second.prompts, first.prompts)
    }

    @Test fun importMergesTheFilesWinningAndDeletesNothing() {
        second.addPrompt("mine", null)
        second.savePrompt("mine", "Kept.")
        second.saveProvider("groq", ProviderSpec())
        second.saveKey("gemini", "AIzaSyD-another-key-00000000000000000000")
        second.pickModel(ModelSlot.TextToImage, "openai:gpt-image-1")
        ConfigFile.apply(second, ConfigFile.read(exported(), keys = true))
        assertEquals("Kept.", second.prompts["mine"]?.text)
        assertTrue(second.providers().any { it.id == "groq" })
        assertEquals(GEMINI, second.key("gemini"))
        assertEquals("Be very brief.", second.prompts["default"]?.text)
        assertEquals("openai:gpt-image-1", second.modelPicks[ModelSlot.TextToImage])
        assertEquals(qwen, second.modelPicks[ModelSlot.Chat])
    }

    @Test fun aNameInAnotherCaseIsTheSamePromptAndAnUneditedSeedFollowsThisCatalog() {
        second.store.rename("gemma", "Gemma")
        val file = exported()
        ConfigFile.apply(second, ConfigFile.read(file, keys = true))
        assertEquals(listOf("default", "Gemma", "terse"), second.prompts.variants.map { it.name })
        assertEquals("Gemma", second.prompts.picks[gemma])
        assertFalse(second.prompts["Gemma"]!!.edited)
    }

    @Test fun overPlainWifiTheFileCarriesNoKeyAndOneWithKeysIsRefused() {
        val bare = exported(keys = false)
        assertFalse(bare.has("keys"))
        assertFalse(bare.toString().contains(GEMINI.takeLast(8)) || bare.toString().contains(OPENAI.takeLast(8)))
        val e = assertThrows(Refused::class.java) { ConfigFile.read(exported(), keys = false) }
        assertEquals(403, e.status)
        assertEquals(ConfigApi.KEYS_WIFI, e.message)
        ConfigFile.apply(second, ConfigFile.read(bare, keys = false))
        assertEquals(first.prompts, second.prompts)
        assertFalse(second.hasKey("gemini"))
    }

    @Test fun aFileThatIsNotOursIsRefusedWholeAndChangesNothing() {
        val good = exported()
        val bad = listOf<(JSONObject) -> Unit>(
            { it.remove("format") },
            { it.put("format", "something-else") },
            { it.put("version", 2) },
            { it.put("version", "1") },
            { it.put("extra", true) },
            { it.put("providers", JSONArray()) },
            { it.getJSONObject("providers").put("mybox", JSONObject().put("kind", "anthropic").put("url", "http://x/v1")) },
            { it.getJSONObject("providers").put("other", JSONObject()) },
            { it.getJSONObject("providers").put("ollama", JSONObject().put("url", "10.0.0.2")) },
            { it.getJSONObject("providers").put("ollama", JSONObject().put("port", 1)) },
            { it.getJSONObject("keys").put("gemini", "two words") },
            { it.getJSONObject("keys").put("gemini", 12) },
            { it.getJSONObject("keys").put("groq", "gsk_fake_00000000000000") },
            { it.getJSONArray("prompts").put(JSONObject().put("name", "TERSE").put("text", "again")) },
            { it.getJSONArray("prompts").put(JSONObject().put("name", "x".repeat(25)).put("text", "")) },
            { it.getJSONArray("prompts").put(JSONObject().put("name", "notext")) },
            { it.getJSONArray("prompts").put(JSONObject().put("name", "p").put("text", "").put("edited", "yes")) },
            { it.getJSONArray("prompts").put("default") },
            { it.getJSONObject("picks").put(qwen, "nobody") },
            { it.getJSONObject("models").put("music", "x:y") },
            { it.getJSONObject("models").put("chat", "") },
            { it.getJSONObject("models").put("chat", "x:" + "y".repeat(ConfigFile.LINE_MAX)) },
            { f -> repeat(ConfigFile.PROMPTS_MAX) { i -> f.getJSONArray("prompts").put(JSONObject().put("name", "p$i").put("text", "")) } },
            { f -> repeat(ConfigFile.PROVIDERS_MAX) { i -> f.getJSONObject("providers").put("box$i", JSONObject().put("kind", "openai").put("url", "http://x/v1")) } },
            { f -> repeat(ConfigFile.PICKS_MAX) { i -> f.getJSONObject("picks").put("ollama:m$i", "terse") } },
            { it.getJSONArray("prompts").put(JSONObject().put("name", "long").put("text", "x".repeat(ConfigFile.TEXT_MAX + 1))) },
            { it.getJSONObject("providers").put("mybox", JSONObject().put("kind", "openai").put("url", "http://x/v1").put("title", "t".repeat(ConfigFile.LINE_MAX + 1))) },
            { it.getJSONObject("providers").put("mybox", JSONObject().put("kind", "openai").put("url", "http://x/" + "v".repeat(ConfigFile.URL_MAX))) },
            { it.getJSONObject("picks").put("ollama:" + "m".repeat(ConfigFile.LINE_MAX), "terse") },
        )
        val before = listOf(File(tmp.root, "second").listFiles()!!.sortedBy { it.name }.map { it.name to it.readText() }, second.modelPicks)
        for ((i, spoil) in bad.withIndex()) {
            val f = JSONObject(good.toString()).also(spoil)
            val e = assertThrows("case $i", Refused::class.java) { ConfigFile.read(f, keys = true) }
            assertEquals("case $i", 400, e.status)
            assertFalse("case $i: ${e.message}", e.message.orEmpty().contains(GEMINI.takeLast(8)))
        }
        assertEquals(before, listOf(File(tmp.root, "second").listFiles()!!.sortedBy { it.name }.map { it.name to it.readText() }, second.modelPicks))
        assertNull(second.spec("mybox"))
    }
}
