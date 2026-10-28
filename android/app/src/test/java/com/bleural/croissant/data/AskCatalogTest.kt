package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ask's catalog (assets/ask/catalog.json): the built-in tools, the seed prompts, the parts of a prompt, the other calls' prompts, the test's cases. */
class AskCatalogTest {
    private val catalog = AskCatalog(File("src/main/assets/ask/catalog.json").readText())

    @Test fun theDevicesToolsTheServersTwoThenTapeAsk() {
        assertEquals(listOf("agenda_list", "agenda_add", "agenda_update", "agenda_delete", "timers_list", "timers_add", "timers_cancel",
            "image_generate", "web_search", "tape_ask"), catalog.tools.map { it.name })
        assertEquals(listOf("image_generate", "web_search"), catalog.tools.filter { it.server }.map { it.name })
        for (t in catalog.tools) {
            assertTrue(t.description.isNotBlank())
            assertEquals("object", t.parameters.getString("type"))
        }
    }

    @Test fun theSeedsDefaultFirstAndGemmaSuggestedForGemma() {
        assertEquals(listOf(AskCatalog.DEFAULT, "gemma"), catalog.prompts.keys.toList())
        assertEquals(catalog.prompts.keys, catalog.photo.keys)
        assertEquals("gemma", catalog.suggested("ollama:gemma4:26b-nvfp4"))
        assertEquals("gemma", catalog.suggested("gemini:gemma-4-26b-it"))
        assertEquals(AskCatalog.DEFAULT, catalog.suggested("ollama:qwen3.5:9b-mlx"))
        assertEquals(AskCatalog.DEFAULT, catalog.suggested(null))
        assertEquals(catalog.photo["gemma"], catalog.photoFor("ollama:gemma4:26b-nvfp4"))
        assertEquals(catalog.photo["default"], catalog.photoFor("ollama:qwen3.5:9b-mlx"))
    }

    @Test fun eachToolsOwnRulesAreInItsDescriptionNotInTheSeeds() {
        val add = catalog.tools.first { it.name == "agenda_add" }.description
        assertTrue("No day or time said: no `at`." in add)
        assertTrue("never a finish time" in catalog.tools.first { it.name == "timers_add" }.description)
        for (text in catalog.prompts.values) {
            assertFalse("agenda_add" in text)
            assertFalse("finish time" in text)
            assertFalse("You have tools" in text)
        }
        assertTrue(listOf("{date}", "{offset}", "{week}").all { it in catalog.parts.clock })
    }

    @Test fun theCasesSayWhatTheyExpect() {
        assertEquals(35, catalog.cases.size)
        assertEquals(6, catalog.cases.count { it.noAt })
        assertTrue(catalog.cases.all { it.expect == "" || it.expect == "*" || it.expect.matches(Regex("[a-z_]+")) })
    }

    @Test fun theCasesAreGroupedByFeatureEachTogetherInTheTestsOrder() {
        val features = listOf("agenda", "timers", "web", "pictures", "tapes", "questions")
        assertEquals(features.map { BenchGroup(it) }, catalog.cases.map { it.group }.distinct())
        // Together: a feature's cases never interrupted by another's.
        assertEquals(features.size, catalog.cases.zipWithNext().count { (a, b) -> a.group != b.group } + 1)
        assertEquals(mapOf("agenda" to 12, "timers" to 4, "web" to 8, "pictures" to 4, "tapes" to 1, "questions" to 6),
            catalog.cases.groupingBy { it.group.name }.eachCount())
        // A case that calls a tool is its tool's feature's.
        val feature = mapOf("agenda_" to "agenda", "timers_" to "timers", "web_search" to "web", "image_generate" to "pictures", "tape_ask" to "tapes")
        for (c in catalog.cases.filter { it.expect.isNotEmpty() && it.expect != "*" }) {
            assertEquals(c.say, feature.entries.first { c.expect.startsWith(it.key) }.value, c.group.name)
        }
        assertTrue(catalog.cases.all { it.group.app == null })
    }

    @Test fun theOtherCallsFixedPromptsAndTheTranslatorsLanguages() {
        val c = catalog.calls
        assertTrue(c.transform.startsWith("You are looking at a photo."))
        assertTrue(c.read.startsWith("You answer one question about a recording"))
        assertTrue(c.app.startsWith("You answer for a small app"))
        val rules = c.translateRules(Language.English, Language.Japanese)
        assertTrue("said aloud in English." in rules)
        assertTrue("into Japanese and nothing else" in rules)
        assertTrue("If it is already in Japanese," in rules)
        assertFalse('{' in rules)
    }

    @Test fun theTranslatorsLanguagesAreTheServersWordForWord() {
        // The languages a hold's `from` may name (stt.py's LANGUAGES): each of the C1's, by its code, the same word.
        val py = File("../../server/croissant_server/stt.py").readText()
        val block = py.substringAfter("LANGUAGES = {").substringBefore("}")
        val server = Regex("\"([a-z]+)\": \"([^\"]+)\"").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        for (l in Language.entries) assertEquals(l.code, server[l.code], l.word)
    }
}
