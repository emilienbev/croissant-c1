package com.bleural.croissant.net

import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.BenchGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a model test runs: every case on offer, or the groups chosen. */
class BenchTest {
    private val agenda = BenchGroup("agenda")
    private val timers = BenchGroup("timers")
    // An app called like a feature is a group of its own.
    private val app = BenchGroup("agenda", app = "agenda")
    private val all = listOf(BenchCase("Remind me to call mum", "agenda_add", agenda), BenchCase("Timer for 30 seconds", "timers_add", timers),
        BenchCase("What is on my agenda this week", "agenda_list", agenda), BenchCase("put oat milk on the list", "agenda_put", app))

    @Test fun aRunOnEveryGroupIsAFullRunOneOnSomeIsNot() {
        val (full, cases) = BenchRun.of("ollama:qwen3.5:9b-mlx", "default", all, null)
        assertEquals(BenchRun("ollama:qwen3.5:9b-mlx", "default", null), full)
        assertEquals(all, cases)
        // Every group chosen by hand is everything.
        assertEquals(BenchRun(null, "gemma", null) to all, BenchRun.of(null, "gemma", all, setOf(agenda, timers, app)))
        val (some, sent) = BenchRun.of(null, "default", all, setOf(agenda))
        assertEquals(setOf(agenda), some.groups)
        assertEquals(listOf("Remind me to call mum", "What is on my agenda this week"), sent.map { it.say })
    }
}
