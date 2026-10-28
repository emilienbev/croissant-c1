package com.bleural.croissant.ui.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.BenchGroup
import com.bleural.croissant.net.BenchCaseResult
import com.bleural.croissant.net.BenchRun
import com.bleural.croissant.net.BenchState
import com.bleural.croissant.net.BenchSummary
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull

/*
 * The model test (Settings > ask > test; the done page picked 2026-10-16): the chat model asked the
 * test's sentences with exactly what a hold on it sends, its prompt and its tools, and judged on
 * the tool it called each time, a feature's sentences and each app's together. One page: the model,
 * the prompt and what it tests, each for that run alone, and the knob; while it runs, how far and
 * each group as a step; done, the score first, a row a group opening its sentences. The test goes
 * on if the page is left; it is the server's to run.
 */

/**
 * What the next test runs on, for that run alone: no pick changes. [model] null is the chat
 * model in use, [prompt] null the one the test's model gets, [groups] null every group on
 * offer. Set again as the test page opens: from the run it shows, so the knob runs it again,
 * or to all three defaults.
 */
internal object NextTest {
    private var model by mutableStateOf<String?>(null)
    private var prompt by mutableStateOf<String?>(null)
    private var groups by mutableStateOf<Set<BenchGroup>?>(null)

    /** The test's model: the one picked for it, else the chat model in use. */
    fun model(core: Core): String? = model ?: core.chatModel

    /** The test's prompt: the one picked for it while it is still there, else the one its model gets. */
    fun prompt(core: Core): String = prompt?.takeIf { core.prompts[it] != null } ?: core.promptFor(model(core))

    /** [id] for the test; the chat model in use is no pick. */
    fun pickModel(core: Core, id: String) {
        model = id.takeIf { it != core.chatModel }
    }

    /** [name] for the test; the one its model gets anyway is no pick. */
    fun pickPrompt(core: Core, name: String) {
        prompt = name.takeIf { it != core.promptFor(model(core)) }
    }

    /** [g] on or off among [all], the groups on offer; all of them on is everything. */
    fun flip(g: BenchGroup, all: Collection<BenchGroup>) {
        val on = all.filter { on(it) }.toMutableSet()
        if (!on.remove(g)) on += g
        groups = on.takeIf { it.size < all.size }
    }

    fun on(g: BenchGroup): Boolean = groups?.contains(g) ?: true

    fun open(core: Core, st: BenchState) {
        val run = runOf(st)
        model = run?.entry?.takeIf { it != core.chatModel }
        prompt = run?.prompt?.takeIf { it != core.promptFor(run.entry) }
        groups = run?.groups
    }
}

private fun runOf(st: BenchState): BenchRun? = when (st) {
    is BenchState.Running -> st.run
    is BenchState.Done -> st.run
    is BenchState.Failed -> st.run
    BenchState.Idle -> null
}

private fun doneOf(st: BenchState): List<BenchCaseResult> = when (st) {
    is BenchState.Running -> st.done
    is BenchState.Done -> st.done
    is BenchState.Failed -> st.done
    BenchState.Idle -> emptyList()
}

/** A group of a run: how many sentences it sends and the results it has so far. */
private class Ran(val group: BenchGroup, val count: Int, val results: List<BenchCaseResult>) {
    val passed get() = results.count { it.ok }
    val missed get() = results.any { !it.ok }
    val finished get() = results.size >= count
    /** As the pages name it: a feature's tag, an app's name, lowercase. */
    val name get() = group.name.lowercase()
}

/** [run]'s groups in the test's order with [done]'s results; one no longer on offer (an app gone while it ran) keeps what it had. */
private fun ranOf(offer: Map<BenchGroup, List<BenchCase>>, run: BenchRun, done: List<BenchCaseResult>): List<Ran> {
    val by = done.groupBy { it.group }
    val sent = offer.filterKeys { run.groups == null || it in run.groups }
    return sent.map { (g, cases) -> Ran(g, cases.size, by[g].orEmpty()) } +
        by.filterKeys { it != null && it !in sent }.map { (g, rs) -> Ran(g!!, rs.size, rs) }
}

/** How long [n] sentences take, said before the start: about 1.6 s each, as qwen on the Mac. */
internal fun testLine(n: Int): String {
    val words = if (n == 1) "1 sentence" else "$n sentences"
    val ms = n * 1600L
    return when {
        n == 0 -> words
        ms < 45_000L -> "$words, under a minute"
        ms < 90_000L -> "$words, about a minute"
        else -> "$words, about ${(ms + 30_000L) / 60_000L} minutes"
    }
}

/** What a missed sentence did, as its row says it: an error, no call, the right tool with the wrong args or a made-up date, else what it called. */
internal fun missWords(c: BenchCaseResult): String {
    val called = c.called.distinct()
    return when {
        c.error != null -> "error"
        called.isEmpty() -> "none"
        called == listOf(c.expect) && c.argsOk == false -> "wrong args"
        called == listOf(c.expect) && c.dated -> "a date made up"
        else -> called.joinToString(", ")
    }
}

/**
 * The test: opened, the rows are the run it shows, or the defaults. While it runs, [TestRunning];
 * else the rows and the knob, under the score once one has ended ([TestRows]).
 */
@Composable
internal fun ModelTestPage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    remember { NextTest.open(core, core.bench.state.value) }
    val state by core.bench.state.collectAsStateWithLifecycle()
    when (val st = state) {
        is BenchState.Running -> TestRunning(st, core, nav, r)
        else -> TestRows(st, core, nav, r)
    }
}

/**
 * Running: how many are done, big, of how many; then the groups as the update's steps, 42 apart
 * from 312: done a tick in sub with its score (a miss in danger), the one now a dot in the hue with
 * how far into it, to come a ring in dim with its count. No key: it ends by itself. The wheel
 * scrolls; up at the top steps back.
 */
@Composable
private fun TestRunning(st: BenchState.Running, core: Core, nav: NavState, r: Ramp) {
    val groups = ranOf(core.benchGroups(), st.run, st.done)
    val now = groups.indexOfFirst { !it.finished }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop("test", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            BigValue("${st.done.size}", "of ${st.total}", r, top = BIG_TOP)
            // The label's baseline is 8 above; the first step's 60 under it.
            Spacer(Modifier.height((60f - 8f - STEP_BASE).px))
            groups.forEachIndexed { i, g -> Step(g, if (i == now) 0 else if (g.finished) -1 else 1, r) }
            Spacer(Modifier.height(24.px))
        }
        TopFade(r, scroll)
    }
}

/** The knob's top on the page: centred on 574, 84 round. */
private const val KNOB_TOP = 532f

/** The big number's baseline under the content's top: 198 on the page. */
private const val BIG_TOP = 132f

/** A step's line: 42 tall, its baseline 31 down, the mark's middle 11 over it. */
private const val STEP_H = 42f
private const val STEP_BASE = 31f

/** One group as a step, [phase] -1 done, 0 under way, 1 to come. */
@Composable
private fun Step(g: Ran, phase: Int, r: Ramp) {
    val mid = STEP_BASE - 11f
    val (words, value, colour) = when (phase) {
        -1 -> Triple(r.sub, "${g.passed}/${g.results.size}", if (g.missed) r.danger else r.sub)
        0 -> Triple(r.ink, "${g.results.size} of ${g.count}", r.hue)
        else -> Triple(r.dim, "${g.count}", r.dim)
    }
    Box(Modifier.fillMaxWidth().height(STEP_H.px)) {
        when (phase) {
            -1 -> Icon(Glyphs.Check, null, Modifier.offset(x = 24.px, y = (mid - 14f).px).size(28.px), r.sub)
            0 -> Box(Modifier.offset(x = 30.px, y = (mid - 8f).px).size(16.px).background(r.hue, CircleShape))
            else -> Box(Modifier.offset(x = 30.px, y = (mid - 8f).px).size(16.px).border(2.px, r.dim, CircleShape))
        }
        Text(g.name, style = BeamType.Body, color = words, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 66.px, end = 160.px).paddingFromBaseline(top = STEP_BASE.px))
        Text(value, style = BeamType.Value, color = colour, maxLines = 1, softWrap = false,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.px).paddingFromBaseline(top = STEP_BASE.px))
    }
}

/**
 * The test's rows and its knob. Before any run: model (the test's, a tap opens the picker for it),
 * prompt (the one it gets, a tap picks another), testing (everything, or how many groups of how
 * many), then how many sentences and about how long in sub; the knob starts it, dim with nothing
 * chosen.
 *
 * Done, the score first: the score big, the prompt in the hue under it and the model in sub under
 * that, so the score is said once for both; a row a group with its score, a miss in danger, a tap
 * opening its sentences; median, slowest and cost; then the three rows, further down; the knob runs
 * it again. Failed or stopped: how far it got, why in danger, the groups that ran, the rows and the
 * knob. The wheel walks the groups and the rows.
 */
@Composable
private fun TestRows(st: BenchState, core: Core, nav: NavState, r: Ramp) {
    LaunchedEffect(Unit) { if (core.models == null) core.refreshModels() }
    val model = NextTest.model(core)
    val prompt = NextTest.prompt(core)
    val offer = core.benchGroups()
    val chosen = offer.keys.filter { NextTest.on(it) }
    val n = chosen.sumOf { offer.getValue(it).size }
    val testing = if (chosen.size == offer.size) "everything" else "${chosen.size} of ${offer.size}"
    val chat = core.modelList(ModelSlot.Chat).entries.isNotEmpty()
    val run = runOf(st)
    val ran = if (run == null) emptyList() else ranOf(offer, run, doneOf(st)).filter { it.results.isNotEmpty() }
    val g = ran.size
    val summary = (st as? BenchState.Done)?.summary
    // Lazy item 0 is the head; the groups follow it, then the summary when done, then the rows.
    val first = 1 + g + if (summary != null) 1 else 0
    val itemOf = { i: Int -> if (i < g) i + 1 else first + i - g }
    val rows = rememberRows(g + 3, nav::pop, itemOf = itemOf, topOf = { if (it == 0) 0 else itemOf(it) })
    PageTop("test", r) {
        // Once a run has rows to scroll, they end over the knob, so the one the wheel is on is never under it.
        ShelfList(rows, r, Modifier.padding(bottom = (if (run != null) 640f - KNOB_TOP else 0f).px)) {
            item {
                when (st) {
                    is BenchState.Done -> Column {
                        BigValue("${st.summary.passed}/${st.summary.total}", st.run.prompt, r, top = BIG_TOP)
                        Under(st.run.entry?.let { chatLabel(core, it) } ?: st.summary.model, r.sub)
                    }
                    is BenchState.Failed -> Column {
                        BigValue("${st.done.size}", "of ${st.total}", r, top = BIG_TOP)
                        Under(st.why, r.danger)
                    }
                    else -> Spacer(Modifier.height(8.px))
                }
            }
            items(g) { i ->
                val a = ran[i]
                ShelfRow(
                    a.name, r, focused = rows.focus == i, value = "${a.passed}/${a.results.size}",
                    valueColour = if (a.missed) r.danger else null, chevron = true,
                    onClick = { rows.focus = i; nav.push(Screen.TestGroup(a.group)) },
                )
            }
            if (summary != null) item { Summary(summary, r) }
            item {
                Column {
                    if (run != null) Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow("model", r, focused = rows.focus == g, glyph = Glyphs.Chat, value = model?.let { chatLabel(core, it) },
                        chevron = chat, dim = !chat, onClick = { rows.focus = g; if (chat) nav.push(Screen.TestModel()) })
                }
            }
            item {
                ShelfRow("prompt", r, focused = rows.focus == g + 1, glyph = Glyphs.Text, value = prompt, chevron = true,
                    onClick = { rows.focus = g + 1; nav.push(Screen.TestPrompt) })
            }
            item {
                ShelfRow("testing", r, focused = rows.focus == g + 2, glyph = Glyphs.Check, value = testing, chevron = true,
                    onClick = { rows.focus = g + 2; nav.push(Screen.Testing) })
            }
            item {
                if (run == null) Text(testLine(n), style = BeamType.Body, color = r.sub, maxLines = 1,
                    modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 56.px))
                Spacer(Modifier.height(24.px))
            }
        }
        if (run != null) {
            TopFade(r.ground) { if (rows.list.firstVisibleItemIndex > 0) Float.MAX_VALUE else rows.list.firstVisibleItemScrollOffset.toFloat() }
            KeyShade(r)
        }
        KnobKey(if (run == null) Glyphs.Play else Glyphs.Restart, r, enabled = n > 0, onClick = {
            core.startBench(model, prompt, chosen.toSet().takeIf { it.size < offer.size })
            Cues.play(Sound.Commit)
        })
    }
}

/** The line under the big value's label, 40 under its baseline (8 already under it), in [colour]; the groups 24 under it. */
@Composable
private fun Under(text: String, colour: Color) {
    Text(text, style = BeamType.Value, color = colour, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 32.px, bottom = 24.px))
}

/** The test's times and cost, as rows, after a gap. */
@Composable
private fun Summary(s: BenchSummary, r: Ramp) {
    Column {
        Spacer(Modifier.height(SETTINGS_GAP))
        ShelfRow("median", r, value = seconds(s.medianMs))
        ShelfRow("slowest", r, value = seconds(s.p90Ms))
        ShelfRow("cost", r, value = when {
            s.free -> "free"
            s.cost == null -> "unknown"
            s.cost < 0.01 -> "under 1 cent"
            else -> "$" + "%.2f".format(s.cost)
        })
    }
}

private fun seconds(ms: Long) = "%.1f s".format(ms / 1000.0)

/**
 * Testing: a switch a feature with its sentences' count 14 before it, then "apps" and a switch an
 * enabled app. Off, its sentences are left out of the next test; all on is everything. The wheel
 * walks the switches; up from the first steps back.
 */
@Composable
internal fun TestingPage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    val offer = core.benchGroups()
    val own = offer.keys.filter { it.app == null }
    val apps = offer.keys.filter { it.app != null }
    val all = own + apps
    val k = own.size
    // Lazy item 0 is the 8 over the rows, so feature i is item i + 1; the apps' label is item k + 1.
    val rows = rememberRows(all.size, nav::pop, itemOf = { if (it < k) it + 1 else it + 2 },
        topOf = { if (it < k) it + 1 else if (it == k) k + 1 else it + 2 })
    @Composable
    fun Switch(i: Int) {
        val g = all[i]
        val on = NextTest.on(g)
        ShelfRow(g.name.lowercase(), r, focused = rows.focus == i, value = "${offer.getValue(g).size}", trailing = { Toggle(on, r) }, onClick = {
            rows.focus = i
            NextTest.flip(g, all)
            Cues.play(if (on) Sound.SwitchOff else Sound.SwitchOn)
        })
    }
    PageTop("testing", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(8.px)) }
            items(k) { Switch(it) }
            if (apps.isNotEmpty()) item { DotLabel("apps", r) }
            items(apps.size) { Switch(k + it) }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/**
 * The test's prompt: who the scores are for under the header word, then a row a prompt, the one the
 * test runs ticked, each one's last score on the test's model as its value. A tap takes it for the
 * test, and no pick changes.
 */
@Composable
internal fun TestPromptPage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    val model = NextTest.model(core)
    val prompts = core.prompts.variants
    val current = NextTest.prompt(core)
    // Lazy item 0 is the line, so row i is item i + 1.
    val rows = rememberRows(prompts.size, nav::pop, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    LaunchedEffect(Unit) { rows.focus = prompts.indexOfFirst { it.name == current }.coerceAtLeast(0) }
    PageTop("prompt", r) {
        ShelfList(rows, r) {
            item { ForLine(model?.let { "for " + chatLabel(core, it) } ?: "no chat model yet", r) }
            items(prompts.size, key = { prompts[it].name }) { i ->
                val p = prompts[i]
                ShelfRow(
                    p.name, r, focused = rows.focus == i, mark = if (p.name == current) Glyphs.Check else null,
                    value = core.benchScore(model, p.name)?.words(),
                    onClick = { rows.focus = i; NextTest.pickPrompt(core, p.name); nav.pop(Sound.Commit) },
                )
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/**
 * A group opened: its name the header word; its sentences, the misses first in danger with what was
 * called (none; a wrong tool by name; wrong args), the rest ticked, since each called what it
 * should. The one the wheel is on shows whole.
 */
@Composable
internal fun TestGroupPage(group: BenchGroup, core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    val state by core.bench.state.collectAsStateWithLifecycle()
    val results = doneOf(state).filter { it.group == group }.sortedBy { it.ok }
    val rows = rememberRows(results.size, nav::pop, itemOf = { it + 1 })
    PageTop(group.name, r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(8.px)) }
            items(results.size) { i ->
                val c = results[i]
                ShelfRow(
                    c.say, r, focused = rows.focus == i, wrap = true, danger = !c.ok,
                    value = if (c.ok) null else missWords(c), valueColour = if (c.ok) null else r.danger,
                    mark = if (c.ok) Glyphs.Check else null, onClick = { rows.focus = i },
                )
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}
