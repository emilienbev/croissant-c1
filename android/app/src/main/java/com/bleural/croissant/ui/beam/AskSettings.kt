package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.bleural.croissant.Core
import com.bleural.croissant.ask.Calls
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.AskCatalog
import com.bleural.croissant.data.BenchScore
import com.bleural.croissant.data.Prompt
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import java.util.Locale

/*
 * Settings > ask (a prompt's page and the whole prompt picked 2026-10-16), in the Settings hue: the
 * chat model, Ask's prompts, the test, the C1's providers and the page on a phone (Providers.kt),
 * then the speech, pictures and transform models. A prompt is a variant named freely, one picked
 * per chat model (Core, "Ask's prompt"); its page says which models get it, opens its text on a
 * phone or the keys and shows it whole as the model gets it.
 */

private val S: Ramp get() = rampOf(Feature.Settings)

/** The pages under Settings > ask, by their [Screen]. */
@Composable
internal fun AskPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.AskSettings -> AskSettingsPage(core, nav)
        is Screen.ModelPick -> ModelPickPage(screen.slot, screen.place, core, nav)
        Screen.ModelTest -> ModelTestPage(core, nav)
        is Screen.TestModel -> TestModelPage(screen.place, core, nav)
        Screen.TestPrompt -> TestPromptPage(core, nav)
        Screen.Testing -> TestingPage(core, nav)
        is Screen.TestGroup -> TestGroupPage(screen.group, core, nav)
        Screen.Prompts -> PromptsPage(core, nav)
        Screen.PromptAdd -> PromptAddPage(core, nav)
        is Screen.Prompt -> PromptPage(screen.name, core, nav)
        is Screen.PromptRename -> PromptRenamePage(screen.name, core, nav)
        // Typed here, on the keys: the row under the code edit opens on (Screen.OnPhone).
        is Screen.PromptEdit -> PromptEditPage(screen.name, core, nav)
        is Screen.PromptWhole -> PromptWholePage(screen.name, core, nav)
        is Screen.FixedPrompt -> FixedPromptPage(screen.call, core, nav)
        Screen.Providers -> ProvidersPage(core, nav)
        Screen.ProviderAdd -> ProviderAddPage(core, nav)
        is Screen.Provider -> ProviderPage(screen.id, core, nav)
        is Screen.ProviderKey -> ProviderKeyPage(screen.id, core, nav)
        is Screen.ProviderAddress -> ProviderAddressPage(screen.id, screen.title, core, nav)
        Screen.ProviderOwn -> ProviderOwnPage(core, nav)
        is Screen.OnPhone -> OnPhonePage(screen.prompt, core, nav)
        else -> Unit
    }
}

/** A chat model's name as the pickers say it: its entry's label in the providers' last lists, else its id less the provider. */
internal fun chatLabel(core: Core, id: String): String =
    core.modelList(ModelSlot.Chat).entries.firstOrNull { it.id == id }?.label ?: id.substringAfter(':')

/** A last score as a row says it. */
internal fun BenchScore.words() = "$passed/$total"

/**
 * Settings > ask: model (the chat model in use, from the C1's providers; a tap opens the picker,
 * which says so when they list none), prompts (the one it gets: its score is on the prompts page),
 * test, providers, on a phone; then, 24 under, the speech, pictures and transform models (pick,
 * 2026-10-16), each the Mac's own or one of the C1's providers'. One with nothing to pick from is
 * dim. The server's list is asked for again as the page opens; the page scrolls.
 */
@Composable
internal fun AskSettingsPage(core: Core, nav: NavState) {
    val r = S
    LaunchedEffect(Unit) { core.refreshModels() }
    val entry = core.chatModel
    val prompt = core.promptFor(entry)
    val slots = SLOTS.filter { it.slot != ModelSlot.Chat }
    val lists = slots.map { core.modelList(it.slot) }
    // Lazy item 0 is the 8 px over the rows, so row i is item i + 1.
    val rows = rememberRows(ASK_ROWS + slots.size, nav::pop, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    PageTop("ask", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(8.px)) }
            item {
                ShelfRow("model", r, focused = rows.focus == 0, glyph = Glyphs.Chat, value = entry?.let { chatLabel(core, it) },
                    chevron = true, onClick = { rows.focus = 0; nav.push(Screen.ModelPick(ModelSlot.Chat)) })
            }
            item {
                ShelfRow("prompts", r, focused = rows.focus == 1, glyph = Glyphs.Text, value = prompt, chevron = true,
                    onClick = { rows.focus = 1; nav.push(Screen.Prompts) })
            }
            item {
                ShelfRow("test", r, focused = rows.focus == 2, glyph = Glyphs.Play, chevron = true,
                    onClick = { rows.focus = 2; nav.push(Screen.ModelTest) })
            }
            item {
                ShelfRow("providers", r, focused = rows.focus == 3, glyph = Glyphs.Cloud, chevron = true,
                    onClick = { rows.focus = 3; nav.push(Screen.Providers) })
            }
            item {
                ShelfRow("on a phone", r, focused = rows.focus == 4, glyph = Glyphs.Phone, chevron = true,
                    onClick = { rows.focus = 4; nav.push(Screen.OnPhone()) })
            }
            items(slots.size) { i ->
                val (slot, word, glyph) = slots[i]
                val on = lists[i].entries.isNotEmpty()
                Column {
                    if (i == 0) Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow(
                        word, r, focused = rows.focus == ASK_ROWS + i, glyph = glyph, value = lists[i].resolve(core.modelPicks[slot])?.label,
                        chevron = on, dim = !on, onClick = { rows.focus = ASK_ROWS + i; if (on) nav.push(Screen.ModelPick(slot)) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** Settings > ask's rows over the model picks: model, prompts, test, providers, on a phone. */
private const val ASK_ROWS = 5

/**
 * Who a page is for, in sub on the first label's line (y 98), its rows 32 under it (y 130):
 * the prompts' chat model (the test's prompt picker's too).
 */
@Composable
internal fun ForLine(text: String, r: Ramp) {
    Text(text, style = BeamType.Body, color = r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.px).paddingFromBaseline(top = 32.px, bottom = 32.px))
}

/**
 * Prompts: "for" the chat model in use under the header word, then a row a prompt, default first,
 * the one it gets ticked and each one's last score on it as the value (none where it was never
 * tested); 24 under, add a prompt; then under fixed, a row each call besides Ask's, opening its
 * prompt read only. A tap opens a prompt's page.
 */
@Composable
internal fun PromptsPage(core: Core, nav: NavState) {
    val r = S
    val entry = core.chatModel
    val prompts = core.prompts.variants
    val current = core.promptFor(entry)
    val n = prompts.size
    // Lazy item 0 is the line, so row i is item i + 1 (add a prompt, row n, item n + 1; the fixed ones after it).
    val rows = rememberRows(n + 1 + FIXED.size, nav::pop, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    PageTop("prompts", r) {
        ShelfList(rows, r) {
            item { ForLine(entry?.let { "for " + chatLabel(core, it) } ?: "no chat model yet", r) }
            items(n, key = { prompts[it].name }) { i ->
                val p = prompts[i]
                ShelfRow(
                    p.name, r, focused = rows.focus == i, mark = if (p.name == current) Glyphs.Check else null,
                    value = core.benchScore(entry, p.name)?.words(), onClick = { rows.focus = i; nav.push(Screen.Prompt(p.name)) },
                )
            }
            item {
                Column {
                    Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow("add a prompt", r, focused = rows.focus == n, glyph = Glyphs.Plus, onClick = { rows.focus = n; nav.push(Screen.PromptAdd) })
                }
            }
            items(FIXED.size, key = { "fixed " + FIXED[it] }) { j ->
                val i = n + 1 + j
                Column {
                    if (j == 0) DotLabel("fixed", r)
                    ShelfRow(FIXED[j], r, focused = rows.focus == i, chevron = true, onClick = { rows.focus = i; nav.push(Screen.FixedPrompt(FIXED[j])) })
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/**
 * A name typed: the kit's field under [word] with the keys up from the start, [action] on them
 * (done when null); [line] under it on 194, a label in sub, or why not in danger when [refused].
 * The wheel scrolls the page once the keys are away; a tick up at the top steps back.
 */
@Composable
internal fun NamePage(word: String, name: String, onName: (String) -> Unit, action: String?, line: String?, refused: Boolean,
                     onBack: () -> Unit, onAct: () -> Unit) {
    val r = S
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val (scroll, pull) = rememberWordsScroll(onBack)
    PageTop(word, r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            Spacer(Modifier.height(22.px))
            Field(
                name, onName, r, Modifier.focusRequester(requester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAct() }), action = action,
            )
            if (line != null) Text(
                line, style = if (refused) BeamType.Body else BeamType.Label, color = if (refused) r.danger else r.sub, maxLines = 2,
                modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 46.px),
            )
            Spacer(Modifier.height(24.px))
        }
    }
}

/**
 * Add a prompt: its name, add on the keys; under it, what it starts as, a copy of
 * the prompt the chat model in use gets. Added, this page becomes the new prompt's.
 */
@Composable
internal fun PromptAddPage(core: Core, nav: NavState) {
    val from = core.promptFor(core.chatModel)
    var name by remember { mutableStateOf("") }
    var why by remember { mutableStateOf<String?>(null) }
    fun add() {
        if (name.isBlank()) return
        val n = name.trim()
        why = core.addPrompt(n, from)
        if (why == null) nav.replace(Screen.Prompt(n), Sound.Commit)
    }
    NamePage("add a prompt", name, { name = it; why = null }, "add", why ?: "starts as a copy of $from", why != null, nav::pop, ::add)
}

/**
 * Rename, from a prompt's page, never the default's: the field holding its name.
 * Done keeps it and steps back to the prompt's page, now under its new name.
 */
@Composable
internal fun PromptRenamePage(name: String, core: Core, nav: NavState) {
    var typed by remember { mutableStateOf(name) }
    var why by remember { mutableStateOf<String?>(null) }
    fun done() {
        val n = typed.trim()
        if (n == name) { nav.pop(); return }
        why = core.renamePrompt(name, n)
        if (why == null) { nav.swapUnder(Screen.Prompt(n)); nav.pop(Sound.Commit) }
    }
    NamePage("rename", typed, { typed = it; why = null }, null, why, true, nav::pop, ::done)
}

/** The calls besides Ask's, each with a fixed prompt (catalog.json's calls), as the prompts page names them. */
private val FIXED = listOf("transform", "translator", "tapes", "apps")

/** The Translator's languages in its prompt: {a} the one spoken, {b} the one it goes into. */
private val LANGUAGE_SLOT = Regex("\\{([ab])\\}")

/**
 * A fixed prompt: [call]'s prompt as the C1 sends it, read only, as the whole prompt's page draws
 * (28, 38 a line, from 104): its words in ink, what the C1 fills in (the Translator's languages,
 * its pair now) in sub; then, 52 under, in sub, what the C1 sends after it. The wheel scrolls it; a
 * tick up at the top steps back.
 */
@Composable
internal fun FixedPromptPage(call: String, core: Core, nav: NavState) {
    val r = S
    val calls = core.askCatalog.calls
    val pair = core.translatorPair
    val text = remember(call, pair) {
        when (call) {
            "transform" -> AnnotatedString(calls.transform)
            "translator" -> buildAnnotatedString {
                var at = 0
                for (m in LANGUAGE_SLOT.findAll(calls.translate)) {
                    append(calls.translate.substring(at, m.range.first))
                    withStyle(SpanStyle(r.sub)) { append(if (m.groupValues[1] == "a") pair.from.word else pair.to.word) }
                    at = m.range.last + 1
                }
                append(calls.translate.substring(at))
            }
            "tapes" -> AnnotatedString(calls.read)
            else -> AnnotatedString(calls.app)
        }
    }
    val after = when (call) {
        "transform" -> "Then the photo, and what was said."
        "tapes" -> "Then the tape's transcript, each line with its time, and the question."
        "apps" -> "Then the app's request, %,d characters at most.".format(Locale.ENGLISH, Calls.ASK_MAX_CHARS)
        else -> null
    }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop(call, r) {
        Baselines(listOfNotNull(38f, after?.let { 52f }), Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll), bottom = 40f) {
            Text(text, style = WHOLE, color = r.ink, modifier = Modifier.padding(start = 24.px, end = 24.px))
            if (after != null) Text(after, style = WHOLE, color = r.sub, modifier = Modifier.padding(start = 24.px, end = 24.px))
        }
        TopFade(r, scroll)
    }
}

/** Any run of spaces and line ends, as one space in a prompt's first lines. */
private val SPACES = Regex("\\s+")

/**
 * A prompt: its name the header word, as an app's info; its text's first three lines, the last cut
 * with an ellipsis; "used by" and a switch per chat model the device knows (the one in use first,
 * then the server's list, then any other picked for it). On, the model gets this prompt from its
 * next hold; off, it goes back to what it gets by default, so a switch that off would leave on this
 * same prompt is on and dim (the default's; gemma's on a Gemma model). Then edit (on its code, a
 * phone or a computer typing a long prompt best), the whole prompt, reset (a seed once edited: the
 * app's text back, following the app again) and rename (not the default); delete at the foot, the
 * sure pill ([SurePill]), red "sure?" for 3 s, a second tap deletes it and steps back (not the
 * default). The wheel walks the switches and the rows, from the first switch that can act.
 */
@Composable
internal fun PromptPage(name: String, core: Core, nav: NavState) {
    val r = S
    // Kept while the page leaves after a delete, so it slides out as it was.
    val seen = remember { arrayOfNulls<Prompt>(1) }
    val p = core.prompts[name]?.also { seen[0] = it } ?: seen[0] ?: return
    val default = name == AskCatalog.DEFAULT
    val listed = core.modelList(ModelSlot.Chat).entries.map { it.id }
    val models = (listOfNotNull(core.chatModel) + listed + core.prompts.picks.filterValues { it == name }.keys).distinct()
    val on = models.map { core.promptFor(it) == name }
    val dim = models.mapIndexed { i, m -> on[i] && core.promptByDefault(m) == name }
    val acts = listOfNotNull("edit", "whole prompt", "reset".takeIf { p.seed != null && p.edited }, "rename".takeIf { !default })
    val m = models.size
    // Lazy item 0 is the first lines and the label, so row i is item i + 1; the rows under the switches start with their gap.
    val rows = rememberRows(m + acts.size, nav::pop, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    LaunchedEffect(Unit) { rows.focus = dim.indexOfFirst { !it }.takeIf { it >= 0 } ?: m }
    val text = remember(p.text) { p.text.replace(SPACES, " ").trim() }
    PageTop(name, r) {
        // Over the pill, the rows end on 556: the one the wheel is on is never under it.
        ShelfList(rows, r, Modifier.padding(bottom = (if (default) 0f else 640f - PILL_TOP + 14f).px)) {
            item {
                Column {
                    if (text.isNotEmpty()) TextShelf(text, r, top = 46f, maxLines = 3)
                    if (m > 0) DotLabel("used by", r, top = if (text.isNotEmpty()) LABEL_ABOVE else LABEL_FIRST)
                }
            }
            items(m, key = { "model " + models[it] }) { i ->
                val id = models[i]
                ShelfRow(chatLabel(core, id), r, focused = rows.focus == i, dim = dim[i], trailing = { Toggle(on[i], r) }, onClick = {
                    rows.focus = i
                    if (!dim[i]) {
                        // Picked only where its default is another: back to its default is no pick.
                        core.pickPrompt(id, if (on[i] || core.promptByDefault(id) == name) null else name)
                        Cues.play(if (on[i]) Sound.SwitchOff else Sound.SwitchOn)
                    }
                })
            }
            items(acts.size, key = { acts[it] }) { i ->
                val act = acts[i]
                Column {
                    if (i == 0) Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow(act, r, focused = rows.focus == m + i, chevron = act != "reset", onClick = {
                        rows.focus = m + i
                        when (act) {
                            "edit" -> nav.push(Screen.OnPhone(name))
                            "whole prompt" -> nav.push(Screen.PromptWhole(name))
                            "reset" -> { core.resetPrompt(name); Cues.play(Sound.Commit) }
                            else -> nav.push(Screen.PromptRename(name))
                        }
                    })
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
        if (!default) SurePill("delete") { core.deletePrompt(name); nav.pop(Sound.Destroy) }
    }
}

/**
 * Edit: the prompt's text as running words on the ground in Body ink, on Beam's keys, up from the
 * start with the caret in the hue at its end; the words move up so the line typed sits over the
 * keys. Saved when the keys are put away and when the page goes, not on every key (each save
 * rewrites prompts.json). The wheel moves the caret while the keys are up, else scrolls; a tick up
 * at the top steps back.
 */
@Composable
internal fun PromptEditPage(name: String, core: Core, nav: NavState) {
    val r = S
    val state = remember { core.prompts[name]?.text.orEmpty().let { TextFieldState(it, TextRange(it.length)) } }
    val saved = remember { arrayOf(state.text.toString()) }
    val save = {
        val t = state.text.toString()
        if (t != saved[0]) { core.savePrompt(name, t); saved[0] = t }
    }
    DisposableEffect(Unit) { onDispose { save() } }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val options = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop("edit", r) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                HueSelection(r) {
                    OwnTextField(
                        state,
                        textStyle = BeamType.Body.copy(color = r.ink),
                        cursorBrush = SolidColor(r.hue),
                        keyboardOptions = options,
                        // It fills the page, so a tap anywhere under the header types.
                        modifier = Modifier.fillMaxWidth().heightIn(min = h - 24.px).padding(start = 24.px, end = 24.px, top = 4.px)
                            .focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) Cues.play(Sound.FieldFocus) else save() },
                    )
                }
                Spacer(Modifier.height(24.px))
            }
        }
        TopFade(r, scroll)
    }
}

/** The whole prompt's words: a value's 28, 38 a line. */
private val WHOLE = BeamType.Value.copy(lineHeight = 38f.pxSp)

/** One line of the prompt as sent, styled, and its first baseline's distance under the last one's. */
private class WholeLine(val text: AnnotatedString, val gap: Float)

/** A tool's line in the device's part: a dash, its name and a colon. */
private val TOOL_LINE = Regex("^- ([^\\s:]+):")

/**
 * The whole prompt: what the next hold on the chat model in use would send with this prompt, the
 * clock now, read-only. The prompt's own words in ink; what the device adds in sub (its tools'
 * line, a line a tool with the tool's name in the hue, the apps' line and their tools, the clock
 * last). The wheel scrolls it; a tick up at the top steps back.
 */
@Composable
internal fun PromptWholePage(name: String, core: Core, nav: NavState) {
    val r = S
    val lines = remember(name) { wholeLines(core.promptPreview(core.chatModel, name), core.prompts[name]?.text.orEmpty(), r) }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop("whole prompt", r) {
        Baselines(lines.map { it.gap }, Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll), bottom = 40f) {
            lines.forEach { Text(it.text, style = WHOLE, modifier = Modifier.padding(start = 24.px, end = 24.px)) }
        }
        TopFade(r, scroll)
    }
}

/**
 * [whole] cut into its lines, [own] (the prompt's text, which it starts with) in ink and the rest
 * in sub, a tool's name in the hue. As drawn: the first baseline 38 under the header's foot (104),
 * 38 a line; a blank line in the prompt's text adds 14, the device's part starts 30 further
 * down, a blank line there adds 18, a tool's line 8 under the line before it and 12 under a
 * tool's.
 */
private fun wholeLines(whole: String, own: String, r: Ramp): List<WholeLine> {
    val mine = own.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
    val split = if (mine.isNotEmpty() && whole.startsWith(mine)) mine.length else 0
    val out = mutableListOf<WholeLine>()
    var extra = 0f
    fun add(text: AnnotatedString, more: Float) {
        out += WholeLine(text, 38f + if (out.isEmpty()) 0f else extra + more)
        extra = 0f
    }
    whole.substring(0, split).split('\n').forEach { l ->
        if (l.isBlank()) extra += 14f else add(AnnotatedString(l, SpanStyle(r.ink)), 0f)
    }
    if (out.isNotEmpty()) extra = 30f
    var afterTool = false
    whole.substring(split).trimStart('\n').split('\n').forEach { l ->
        if (l.isBlank()) { extra += 18f; return@forEach }
        val tool = TOOL_LINE.find(l)
        add(added(l, tool, r.sub, r.hue), if (afterTool) 12f else if (tool != null) 8f else 0f)
        afterTool = tool != null
    }
    return out
}

/** A line the device adds, in [colour]; a tool's with its name and colon Medium in [hue]. */
private fun added(l: String, tool: MatchResult?, colour: Color, hue: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(colour)) {
        if (tool == null) { append(l); return@withStyle }
        append("- ")
        withStyle(SpanStyle(hue, fontWeight = FontWeight.Medium)) { append(tool.groupValues[1] + ":") }
        append(l.substring(tool.range.last + 1))
    }
}
