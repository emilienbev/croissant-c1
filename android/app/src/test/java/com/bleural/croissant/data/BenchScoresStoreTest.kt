package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The model test's last scores on the device (data/BenchScores.kt). */
class BenchScoresStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "scores.json")
    private fun store() = BenchScoresStore(file())

    private val qwen = "ollama:qwen3.5:9b-mlx"
    private val gemma = "ollama:gemma4:26b-nvfp4"

    @Test fun aMissingOrCorruptFileStartsEmpty() {
        assertEquals(emptyList<BenchScore>(), store().scores)
        assertFalse(file().exists())
        file().writeText("{not json")
        assertEquals(emptyList<BenchScore>(), store().scores)
    }

    @Test fun theLastScorePerModelAndPromptIsKeptAndReadBack() {
        val s = store()
        assertNull(s.save(BenchScore(qwen, "default", 35, 37, 1000)))
        assertNull(s.save(BenchScore(gemma, "default", 36, 37, 2000)))
        assertNull(s.save(BenchScore(qwen, "gemma", 30, 37, 3000)))
        // The next run on qwen with default replaces its last; the others stay.
        assertNull(s.save(BenchScore(qwen, "default", 36, 37, 4000)))
        val want = listOf(BenchScore(gemma, "default", 36, 37, 2000), BenchScore(qwen, "gemma", 30, 37, 3000),
            BenchScore(qwen, "default", 36, 37, 4000))
        assertEquals(want, s.scores)
        assertEquals(want, store().scores)
        assertFalse(File(tmp.root, "scores.json.tmp").exists())
    }

    @Test fun aRenamedPromptTakesItsScoresADeletedOneDropsThem() {
        val s = store()
        s.save(BenchScore(qwen, "mine", 33, 35, 1000))
        s.save(BenchScore(gemma, "mine", 34, 35, 2000))
        s.save(BenchScore(qwen, "default", 35, 35, 3000))
        assertNull(s.rename("mine", "terse"))
        assertEquals(listOf("terse", "terse", "default"), store().scores.map { it.prompt })
        assertNull(s.drop("terse"))
        assertEquals(listOf(BenchScore(qwen, "default", 35, 35, 3000)), store().scores)
    }

    @Test fun aChangeThatCannotBeWrittenIsNotMade() {
        val s = store()
        val kept = BenchScore(qwen, "default", 35, 35, 1000)
        s.save(kept)
        File(tmp.root, "scores.json.tmp").mkdir()
        assertNotNull(s.save(BenchScore(qwen, "default", 1, 35, 2000)))
        assertNotNull(s.rename("default", "base"))
        assertEquals(listOf(kept), s.scores)
        assertEquals(listOf(kept), store().scores)
    }
}
