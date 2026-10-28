package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Ask's prompt variants on the device (data/Prompts.kt), against a small catalog of their seeds. */
class PromptsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "prompts.json")
    private fun catalog(seeds: Map<String, String> = mapOf("default" to "Be brief.", "gemma" to "Be Gemma.")) = AskCatalog(JSONObject()
        .put("tools", JSONArray()).put("prompts", JSONObject(seeds)).put("photo", JSONObject())
        .put("parts", JSONObject().put("tools", "").put("apps", "").put("clock", ""))
        .put("suggest", JSONObject().put("gemma4", "gemma")).put("cases", JSONArray()).toString())
    private fun store(c: AskCatalog = catalog()) = PromptsStore(file(), c)
    private fun PromptsStore.resolve(entry: String?, c: AskCatalog = catalog()) = prompts.resolve(entry, c.suggested(entry))

    private val qwen = "ollama:qwen3.5:9b-mlx"
    private val gemma = "ollama:gemma4:26b-nvfp4"

    @Test fun aMissingOrCorruptFileStartsFromTheSeeds() {
        val fresh = store().prompts
        assertEquals(listOf(Prompt("default", "Be brief.", "default"), Prompt("gemma", "Be Gemma.", "gemma")), fresh.variants)
        assertTrue(file().exists())
        assertFalse(File(tmp.root, "prompts.json.tmp").exists())
        file().writeText("{not json")
        assertEquals(fresh, store().prompts)
    }

    @Test fun anUneditedSeedFollowsTheCatalogAnEditedOneStaysUntilReset() {
        store().save("gemma", "Mine.")
        val moved = catalog(mapOf("default" to "Be briefer.", "gemma" to "Be Gemma 2."))
        val s = store(moved)
        assertEquals("Be briefer.", s.prompts["default"]?.text)
        assertEquals(Prompt("gemma", "Mine.", "gemma", edited = true), s.prompts["gemma"])
        s.reset("gemma")
        assertEquals(Prompt("gemma", "Be Gemma 2.", "gemma"), s.prompts["gemma"])
        // Saved back to the catalog's own text, it follows the catalog again.
        s.save("default", "Be briefer.")
        assertFalse(s.prompts["default"]!!.edited)
    }

    @Test fun aNewSeedArrivesOnceAndADeletedOneNeverReturns() {
        val s = store(catalog(mapOf("default" to "Be brief.")))
        assertNull(s.add("gemma"))
        assertEquals(listOf("default", "gemma"), s.prompts.variants.map { it.name })
        // The release that brings the gemma seed: the name is taken, so it is numbered.
        val next = store()
        assertEquals(Prompt("gemma 2", "Be Gemma.", "gemma"), next.prompts["gemma 2"])
        assertNull(next.delete("gemma 2"))
        assertNull(store().prompts["gemma 2"])
    }

    @Test fun aRenameCarriesThePicksAndTheSeed() {
        val s = store()
        s.pick(qwen, "gemma")
        assertNull(s.rename("gemma", "big"))
        assertEquals("big", s.resolve(qwen))
        // Gemma's suggestion is the variant seeded from it, whatever it is called now.
        assertEquals("big", s.resolve(gemma))
        assertEquals("the default keeps its name", s.rename("default", "base"))
        assertEquals(s.prompts, store().prompts)
    }

    @Test fun aDeleteDropsItsPicksAndTheDefaultStays() {
        val s = store()
        s.pick(qwen, "gemma")
        assertNull(s.delete("gemma"))
        assertFalse(qwen in s.prompts.picks)
        assertEquals("default", s.resolve(gemma))
        assertNotNull(s.delete("default"))
        assertNotNull(s.prompts["default"])
    }

    @Test fun namesAreOneLineUpTo24AndUniqueIgnoringCase() {
        val s = store()
        assertNotNull(s.add("  "))
        assertNotNull(s.add("a".repeat(25)))
        assertNull(s.add(" " + "a".repeat(24) + " "))
        assertNotNull(s.add("Gemma"))
        assertNotNull(s.add("two\nlines"))
        assertNull(s.rename("gemma", "Gemma"))
        assertEquals(listOf("default", "Gemma", "a".repeat(24)), s.prompts.variants.map { it.name })
    }

    @Test fun aHoldGetsItsPickElseItsSuggestionElseTheDefault() {
        val s = store()
        assertNull(s.add("terse", from = "default"))
        assertEquals("Be brief.", s.prompts["terse"]?.text)
        assertEquals("default", s.resolve(qwen))
        assertEquals("gemma", s.resolve(gemma))
        assertEquals("default", s.resolve(null))
        s.pick(qwen, "terse")
        s.pick(gemma, "nowhere")
        assertEquals("terse", s.resolve(qwen))
        assertEquals("gemma", s.resolve(gemma))
        s.pick(qwen, null)
        assertEquals("default", s.resolve(qwen))
    }

    @Test fun aChangeThatCannotBeWrittenIsNotMade() {
        val s = store()
        File(tmp.root, "prompts.json.tmp").mkdir()
        assertNotNull(s.add("terse"))
        s.save("default", "Changed.")
        assertNull(s.prompts["terse"])
        assertEquals("Be brief.", s.prompts["default"]?.text)
        assertEquals(s.prompts, store().prompts)
    }

    @Test fun savedTextHasPlainLineEndings() {
        val s = store()
        s.save("default", "one\r\ntwo\rthree")
        assertEquals("one\ntwo\nthree", store().prompts["default"]?.text)
    }
}
