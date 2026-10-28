package com.bleural.croissant.net

import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.BenchGroup

/** One sentence of the model test as it was judged (ask/Test.kt): what the model called, and whether that was right. */
data class BenchCaseResult(
    val i: Int, val say: String, val expect: String, val called: List<String>, val ok: Boolean,
    val dated: Boolean, val answer: String, val ms: Long, val error: String?,
    /** The case named arguments: whether the call passed them; null when it named none. */
    val argsOk: Boolean? = null,
    /** The group its case is in, by [i]; null for one the test did not send. */
    val group: BenchGroup? = null,
)

/**
 * What a test runs on, for that run alone (no pick changes): the chat model [entry]
 * (`provider:model`, null for the default), the variant [prompt], and the [groups]
 * chosen, null for every case on offer.
 */
data class BenchRun(val entry: String?, val prompt: String, val groups: Set<BenchGroup>?) {
    companion object {
        /**
         * A run on [groups] (null: all) of [all], the cases on offer: the run, its groups null
         * when it sends every case (only such a run keeps a score), and the cases it sends, in
         * order.
         */
        fun of(entry: String?, prompt: String, all: List<BenchCase>, groups: Set<BenchGroup>?): Pair<BenchRun, List<BenchCase>> {
            val cases = if (groups == null) all else all.filter { it.group in groups }
            return BenchRun(entry, prompt, groups.takeIf { cases.size < all.size }) to cases
        }
    }
}

/** The test's end: the score, the times, the tokens and what it cost ([cost] null when the price is unknown). */
data class BenchSummary(
    val passed: Int, val total: Int, val medianMs: Long, val p90Ms: Long,
    val tokensIn: Long, val tokensOut: Long, val cost: Double?, val free: Boolean, val model: String,
)

sealed interface BenchState {
    data object Idle : BenchState
    data class Running(val done: List<BenchCaseResult>, val total: Int, val run: BenchRun) : BenchState
    data class Done(val done: List<BenchCaseResult>, val summary: BenchSummary, val run: BenchRun) : BenchState
    data class Failed(val done: List<BenchCaseResult>, val total: Int, val why: String, val run: BenchRun) : BenchState
}
