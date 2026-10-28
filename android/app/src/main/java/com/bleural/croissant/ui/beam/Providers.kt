package com.bleural.croissant.ui.beam

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bleural.croissant.Core
import com.bleural.croissant.ask.AskError
import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.Provider
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.ask.Providers
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.served.Way
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The C1's own providers (picked 2026-10-16) and the page on a phone, in the Settings hue.
 * Providers: the ones added as the model picker draws its places (the laptop for one on the
 * server's machine, the cloud for one with a key), what it stands on under its name, then add a
 * provider, the presets on a page of their own. A provider's page: its address or its key, test and
 * its answer (its models by slot, or what the provider said), remove. A key is typed on Beam's
 * keys, shown while typed, tried before it is kept, and then only ever shown by its last four. No
 * hold uses them yet: the model picker still lists the server's models.
 */

private val S: Ramp get() = rampOf(Feature.Settings)

/** A provider's word, as its row and its header say it: a preset's name lowercase (Gemini without Google), one's own as it was named. */
internal fun providerWord(p: Provider): String = if (p.id in Providers.PRESETS) presetWord(p.id) else p.title

private fun presetWord(id: String): String = Providers.PRESETS[id]?.title?.lowercase()?.removePrefix("google ") ?: id

/** The providers as they are now, drawn again when one changes here or on a phone. */
@Composable
private fun rememberProviders(core: Core): List<Provider> {
    val changed = core.providersChanged
    val server = core.server
    return remember(changed, server) { core.providers() }
}

/** A URL's host, as a row says where something is. */
private fun hostOf(url: String): String = runCatching { URI(url).host?.removeSurrounding("[", "]") }.getOrNull() ?: url

/** Where a provider is, by its row: the server's machine by its name for one that follows it, else its host. */
private fun whereOf(p: Provider, core: Core): String = if (p.mac) core.providerPlace() ?: hostOf(p.url) else hostOf(p.url)

/** What it stands on (`A4_ADDED`'s line): its key by its last four (a free tier first), or where it is. */
private fun standsOn(p: Provider, core: Core): String = when {
    p.url.isEmpty() -> "no server yet"
    core.hasKey(p.id) -> (if (core.listing(p.id)?.free == true) "free tier, " else "") + (core.keyHint(p.id)?.let { "key …$it" } ?: "key kept")
    p.needsKey -> "no key yet"
    else -> "on " + whereOf(p, core)
}

// ---- providers ---------------------------------------------------------------------------

/** A provider's row (`a4Place`): 108 tall, its name (38) on 48, what it stands on 40 under. */
private const val PROVIDER_H = 108f

private class ProviderPens(val text: TextMeasurer, val laptop: Painter, val cloud: Painter, val server: Painter)

/**
 * Providers: a row a provider added, Ollama first, as the picker's places are drawn: the laptop for
 * one on the server's machine, the cloud for a hosted one, a server for one's own; its name at 92,
 * how many chat models it last listed and the chevron, what it stands on under its name; ringed
 * when the wheel is on it. Then, 24 under, add a provider. A tap opens it.
 */
@Composable
internal fun ProvidersPage(core: Core, nav: NavState) {
    val r = S
    val all = rememberProviders(core)
    val pens = ProviderPens(rememberTextMeasurer(cacheSize = 32), rememberVectorPainter(Glyphs.Laptop),
        rememberVectorPainter(Glyphs.Cloud), rememberVectorPainter(Glyphs.Server))
    val n = all.size
    val rows = rememberRows(n + 1, nav::pop, itemOf = { it + 1 })
    PageTop("providers", r) {
        ShelfList(rows, r) {
            item { Spacer(Modifier.height(10.px)) }
            items(n, key = { all[it].id }) { i ->
                val p = all[i]
                ProviderRow(p, standsOn(p, core), core.listing(p.id)?.models?.get(ModelSlot.Chat)?.size, rows.focus == i, pens, r) {
                    rows.focus = i
                    nav.push(Screen.Provider(p.id))
                }
            }
            item {
                Column {
                    Spacer(Modifier.height(SETTINGS_GAP))
                    ShelfRow("add a provider", r, focused = rows.focus == n, glyph = Glyphs.Plus, chevron = true,
                        onClick = { rows.focus = n; nav.push(Screen.ProviderAdd) })
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

@Composable
private fun ProviderRow(p: Provider, line: String, count: Int?, focused: Boolean, pens: ProviderPens, r: Ramp, onTap: () -> Unit) {
    val word = providerWord(p)
    val local = Providers.PRESETS[p.id]?.mac == true
    val own = p.id !in Providers.PRESETS
    Canvas(Modifier.fillMaxWidth().height(PROVIDER_H.px).tappable(onTap)) {
        pageScale {
            val base = 48f
            val t = pens.text
            if (focused) ring(r, 0f, PROVIDER_H - 6f)
            when {
                local -> glyph(pens.laptop, 28f, base - 36f, 44f, r.hue)
                own -> glyph(pens.server, 26f, base - 40f, 48f, r.hue)
                else -> glyph(pens.cloud, 22f, base - 49.6f, 54f, r.hue)
            }
            var right = 414f
            if (count != null) right -= text(t, 414f, base, count.toString(), BeamType.Value, r.sub, Anchor.End) + 20f
            chevron(436f, base - 13f, if (focused) r.hue else r.sub)
            text(t, 92f, base, fit(t, word, BeamType.Row, right - 92f), BeamType.Row, r.ink)
            text(t, 92f, base + 40f, fit(t, line, BeamType.Label, 444f - 92f), BeamType.Label, r.sub)
        }
    }
}

/**
 * Add a provider: the presets not added yet, in the server's order, then your own server. A hosted
 * one opens its key; LM Studio, which needs none, is added and opens on its page, where test asks
 * it; your own server asks its name, then its address.
 */
@Composable
internal fun ProviderAddPage(core: Core, nav: NavState) {
    val r = S
    val added = rememberProviders(core).map { it.id }.toSet()
    val presets = remember(added) { Providers.PRESETS.filterKeys { it !in added }.toList() }
    val n = presets.size
    var why by remember { mutableStateOf<String?>(null) }
    val rows = rememberRows(n + 1, nav::pop, itemOf = { it + 1 })
    PageTop("add a provider", r) {
        ShelfList(rows, r) {
            item {
                Column {
                    Spacer(Modifier.height(8.px))
                    why?.let { SettingsWords(it, r.danger) }
                }
            }
            items(n, key = { presets[it].first }) { i ->
                val (id, preset) = presets[i]
                ShelfRow(presetWord(id), r, focused = rows.focus == i, chevron = true, onClick = {
                    rows.focus = i
                    if (preset.needsKey) nav.push(Screen.ProviderKey(id))
                    else {
                        why = core.saveProvider(id)
                        if (why == null) nav.replace(Screen.Provider(id), Sound.Commit)
                    }
                })
            }
            item {
                ShelfRow("your own server", r, focused = rows.focus == n, chevron = true, onClick = { rows.focus = n; nav.push(Screen.ProviderOwn) })
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** A name typed for one's own server as an id the C1 takes: letters, digits and ._:- (the phone page's rule). */
internal fun providerId(name: String): String {
    val id = name.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9._:-]"), "").trim('-').take(80)
    return if (id.all { it == '.' }) "" else id
}

/**
 * Your own server: its name first, on the kit's field, next on the keys, then its address. A name
 * taken, or one with nothing an id can be made of, says so in danger.
 */
@Composable
internal fun ProviderOwnPage(core: Core, nav: NavState) {
    var name by remember { mutableStateOf("") }
    var why by remember { mutableStateOf<String?>(null) }
    fun next() {
        val n = name.trim()
        if (n.isEmpty()) return
        val id = providerId(n)
        why = when {
            id.isEmpty() -> "a name of letters or digits"
            core.providerSpec(id) != null || id in Providers.PRESETS -> "there is a $id already"
            else -> null
        }
        if (why == null) nav.push(Screen.ProviderAddress(id, n))
    }
    NamePage("your own server", name, { name = it; why = null }, "next", why ?: "then its address", why != null, nav::pop, ::next)
}

// ---- a provider ----------------------------------------------------------------------------

/** A test's outcome: [value] on its row (how fast, or what went wrong, in danger when [failed]), and [said] under the rows. */
private class Tested(val value: String, val failed: Boolean, val said: String? = null)

/**
 * What a failed test says: refused for a key or a request the provider turned down, with its
 * own words (less the status); no answer for nothing at the address, with where it looked and,
 * for Ollama, why that is likely; failed for the rest.
 */
private fun failure(e: Throwable, p: Provider, core: Core): Tested {
    val words = e.message.orEmpty().replace(Regex("^\\d{3}: "), "")
    if (e is AskError && e.code in 400..499) return Tested("refused", true, words)
    if (e is AskError) return Tested("failed", true, words)
    val port = runCatching { URI(p.url).port }.getOrDefault(-1).takeIf { it > 0 }
    val at = "nothing answers on ${whereOf(p, core)}" + (port?.let { " at port $it" } ?: "")
    val said = if (p.kind == Kind.Ollama) "$at: ollama listens to its own machine alone until OLLAMA_HOST is 0.0.0.0" else at
    return Tested("no answer", true, said)
}

/** Provider words (an error, a hint) under the rows: a value's 28, 38 a line, in sub. */
private val SAID = BeamType.Value.copy(lineHeight = 38f.pxSp)

/**
 * A provider: its name the header word; address (one on the server's machine, or one's own: the
 * server's name while it follows the server, else its host) and key (one that takes one: its last
 * four, or none), each opening its page; test, how fast it answered (a tap asks again), or refused,
 * no answer in danger with what the provider said under it. Otherwise its models by slot as it last
 * listed them under models, "free tier" at the label's end on a free key. Remove at the foot, the
 * sure pill ([SurePill]), red sure? then gone (not Ollama, which is always there).
 */
@Composable
internal fun ProviderPage(id: String, core: Core, nav: NavState) {
    val r = S
    val all = rememberProviders(core)
    // Kept while the page leaves after a remove, so it slides out as it was.
    val seen = remember { arrayOfNulls<Provider>(1) }
    val p = all.firstOrNull { it.id == id }?.also { seen[0] = it } ?: seen[0] ?: return
    val own = id !in Providers.PRESETS
    val takesAddress = own || Providers.PRESETS[id]?.mac == true || core.providerSpec(id)?.url != null
    val takesKey = own || p.needsKey
    val acts = listOfNotNull("address".takeIf { takesAddress }, "key".takeIf { takesKey }, "test")
    var tested by remember { mutableStateOf<Tested?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun test() {
        if (testing) return
        testing = true
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val l = core.listProvider(id)
            val ms = SystemClock.elapsedRealtime() - t0
            tested = l.fold({ Tested("$ms ms", false) }, { failure(it, p, core) })
            testing = false
            Cues.play(if (l.isSuccess) Sound.Commit else Sound.JoinFailed)
        }
    }
    val listing = core.listing(id)
    val slots = SLOTS.mapNotNull { look -> listing?.models?.get(look.slot)?.size?.takeIf { it > 0 }?.let { look.word to it } }
    val removable = id != "ollama"
    val rows = rememberRows(acts.size, nav::pop, itemOf = { it + 1 })
    PageTop(providerWord(p), r) {
        ShelfList(rows, r, Modifier.padding(bottom = (if (removable) 640f - PILL_TOP + 14f else 0f).px)) {
            item { Spacer(Modifier.height(8.px)) }
            items(acts.size, key = { acts[it] }) { i ->
                val focused = rows.focus == i
                when (acts[i]) {
                    "address" -> ShelfRow("address", r, focused = focused, glyph = Glyphs.Server, chevron = true,
                        value = if (p.url.isEmpty()) "none" else whereOf(p, core),
                        onClick = { rows.focus = i; nav.push(Screen.ProviderAddress(id)) })
                    "key" -> ShelfRow("key", r, focused = focused, glyph = Glyphs.Key, chevron = true,
                        value = if (core.hasKey(id)) core.keyHint(id)?.let { "…$it" } ?: "kept" else "none",
                        onClick = { rows.focus = i; nav.push(Screen.ProviderKey(id)) })
                    else -> ShelfRow("test", r, focused = focused, glyph = Glyphs.Play,
                        value = if (testing) "testing" else tested?.value,
                        valueColour = if (!testing && tested?.failed == true) r.danger else null,
                        onClick = { rows.focus = i; test() })
                }
            }
            item {
                val said = tested?.said?.takeIf { !testing }
                Column {
                    when {
                        said != null -> Text(said, style = SAID, color = r.sub,
                            modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = LABEL_ABOVE.px))
                        listing != null && slots.isEmpty() -> Text("no models it can use", style = SAID, color = r.sub,
                            modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = LABEL_ABOVE.px))
                        slots.isNotEmpty() -> {
                            DotLabel("models", r, end = if (listing?.free == true) "free tier" else null)
                            slots.forEach { (word, count) -> ShelfRow(word, r, value = count.toString()) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
        if (removable) SurePill("remove") { core.removeProvider(id); nav.pop(Sound.Destroy) }
    }
}

// ---- a key and an address ------------------------------------------------------------------

/**
 * A key typed (Settings.kt WifiJoinPage's shape): the provider the header word, key over the field,
 * the key shown while it is typed (hide and show under it), its start scrolled out of the field.
 * The knob (go on the keys) tries it, its models asked for, and keeps it only if that works, then
 * opens the provider's page (or steps back to it); refused, the field's rule in danger and what the
 * provider said under it, nothing kept. The quiet phone key, where Wi-Fi's scan key sits, opens the
 * page on a phone, for a key too long to type here.
 */
@Composable
internal fun ProviderKeyPage(id: String, core: Core, nav: NavState) {
    val r = S
    val all = rememberProviders(core)
    val adding = remember { core.providerSpec(id) == null }
    val word = all.firstOrNull { it.id == id }?.let(::providerWord) ?: presetWord(id)
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(true) }
    var said by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val scope = rememberCoroutineScope()
    fun go() {
        val k = key.trim()
        if (k.isEmpty() || testing) return
        testing = true
        said = null
        scope.launch {
            val tried = core.tryProvider(id, core.providerSpec(id) ?: ProviderSpec(), k)
            testing = false
            tried.onSuccess {
                if (adding) { nav.drop(Screen.ProviderAdd); nav.replace(Screen.Provider(id), Sound.Commit) } else nav.pop(Sound.Commit)
            }.onFailure { e ->
                // As its test says it: a provider not added yet is reached as its preset is.
                val p = all.firstOrNull { it.id == id } ?: runCatching { Providers.resolve(id, ProviderSpec(), core.server.host) }.getOrNull()
                said = (if (p != null) failure(e, p, core).said else e.message)?.ifEmpty { null } ?: "refused"
                Cues.play(Sound.JoinFailed)
            }
        }
    }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop(word, r) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                DotLabel("key", r, top = LABEL_FIRST)
                Field(
                    key, { key = it; said = null }, r, Modifier.focusRequester(requester), secret = !show,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }), refused = said != null,
                )
                val line = if (testing) "testing" else said
                if (line != null) Text(line, style = BeamType.Label, color = if (testing) r.sub else r.danger, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 38.px, bottom = 22.px))
                else Spacer(Modifier.height(26.px))
                Selector(listOf("hide", "show"), if (show) 1 else 0, r, Modifier.padding(start = 24.px)) { i ->
                    val next = i == 1
                    if (next != show) { show = next; Cues.play(if (show) Sound.SwitchOn else Sound.SwitchOff) }
                }
                Spacer(Modifier.height(24.px))
            }
            KnobKey(Glyphs.Check, r, onClick = { go() }, enabled = key.isNotBlank() && !testing)
            QuietKey(Glyphs.Phone, r, onClick = { nav.push(Screen.OnPhone()) }, slot = 1)
        }
    }
}

/** [typed] as an address a provider takes: http:// when it has no scheme; Ollama's port when it has none. */
private fun addressOf(typed: String, kind: Kind?): String? {
    val t = typed.trim().ifEmpty { return null }
    val url = if ("://" in t) t else "http://$t"
    if (kind != Kind.Ollama) return url
    val u = runCatching { URI(url) }.getOrNull() ?: return url
    return if (u.port == -1 && (u.path.isNullOrEmpty() || u.path == "/")) url.trimEnd('/') + ":" + Providers.OLLAMA_PORT else url
}

/**
 * A provider's address (a tap types another): the provider the header word, address over the field
 * holding the one given (empty while it follows the server's machine, the server's name in its
 * place), what it takes under it; the knob keeps it. [title]: a server of one's own being added
 * under that name, kept with its address and opened on its page. A new address drops the key kept
 * for the old one.
 */
@Composable
internal fun ProviderAddressPage(id: String, title: String?, core: Core, nav: NavState) {
    val r = S
    val all = rememberProviders(core)
    val spec = core.providerSpec(id)
    val p = all.firstOrNull { it.id == id }
    val own = id !in Providers.PRESETS
    val kind = if (own) Kind.OpenAi else Providers.PRESETS[id]?.kind
    var typed by remember { mutableStateOf(spec?.url.orEmpty()) }
    var why by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    fun keep() {
        val url = addressOf(typed, kind)
        if (own && url == null) { why = "give it its address"; return }
        why = if (title != null) core.saveProvider(id, ProviderSpec(Kind.OpenAi, url, title))
        else core.saveProvider(id, (spec ?: ProviderSpec()).copy(url = url))
        if (why != null) return
        if (title != null) {
            nav.drop(Screen.ProviderOwn)
            nav.drop(Screen.ProviderAdd)
            nav.replace(Screen.Provider(id), Sound.Commit)
        } else nav.pop(Sound.Commit)
    }
    val hint = when {
        own -> "openai-compatible: host:port/v1"
        p != null && core.hasKey(id) -> "a new address drops its key"
        else -> "empty, it follows the server"
    }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop(title ?: p?.let(::providerWord) ?: presetWord(id), r) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                DotLabel("address", r, top = LABEL_FIRST)
                Field(
                    typed, { typed = it; why = null }, r, Modifier.focusRequester(requester),
                    placeholder = if (own) "http://" else core.providerPlace(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { keep() }), refused = why != null,
                )
                Text(why ?: hint, style = BeamType.Label, color = if (why != null) r.danger else r.sub, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 38.px, bottom = 22.px))
            }
            KnobKey(Glyphs.Check, r, onClick = { keep() }, enabled = !own || typed.isNotBlank())
        }
    }
}

// ---- on a phone ----------------------------------------------------------------------------

/**
 * On a phone (the kit's QrCard): the page the C1 serves, up while this one is open. The code
 * carries a one-time token; the address it serves on and the token under it, to type on a computer;
 * what is done there. The tailnet's address when Tailscale is up; Wi-Fi offered beside it as the
 * kit's selector at the foot, its warning that what is typed crosses it unencrypted in the notes'
 * place (decided 2026-10-16). Once a phone opened it, the code goes: the card faint with a phone,
 * when it was opened, and that leaving closes it.
 *
 * [prompt]: from that prompt's edit, "edit" the header word, the phone's page opened on that
 * prompt, and type it here the row under the code; no selector there.
 */
@Composable
internal fun OnPhonePage(prompt: String?, core: Core, nav: NavState) {
    val r = S
    var wifi by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wifi) {
        why = null
        why = core.openPhonePage(if (wifi) Way.Wifi else null)
    }
    DisposableEffect(Unit) { onDispose { core.closePhonePage() } }
    val page = core.phonePage
    val phone = rememberVectorPainter(Glyphs.Phone)
    val ctx = LocalContext.current
    PageTop(if (prompt != null) "edit" else "on a phone", r) {
        StillPull(nav::pop, r) {
            Column(Modifier.padding(top = 8.px)) {
                when {
                    why != null || page == null -> Box(Modifier.fillMaxWidth().height(400.px), contentAlignment = Alignment.Center) {
                        Text(why ?: "starting", style = BeamType.Body, color = r.sub, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.px))
                    }
                    page.used -> {
                        val at = remember(page.usedAt) {
                            DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm")
                                .format(Instant.ofEpochMilli(page.usedAt).atZone(ZoneId.systemDefault()))
                        }
                        QrCard("", listOf("opened at $at"), if (prompt != null) emptyList() else listOf("the code is spent;", "leaving this page", "closes it"),
                            spent = phone, hue = r.hue)
                    }
                    else -> {
                        val bare = page.url.removePrefix("http://")
                        val notes = when {
                            prompt != null -> emptyList()
                            page.way == Way.Wifi && page.choice -> listOf("unencrypted on wi-fi:", "a private network only")
                            page.way == Way.Wifi -> listOf("tailscale is off; wi-fi is", "unencrypted: a private", "network only")
                            page.choice -> listOf("keys, prompts, export:", "the code works once")
                            else -> listOf("keys, prompts, export:", "the code works once,", "while this page is open")
                        }
                        val url = if (prompt != null) page.url + "#prompts/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") else page.url
                        QrCard(url, listOf(bare.substringBefore('/'), "/" + bare.substringAfter('/')), notes)
                    }
                }
                if (prompt != null) {
                    Spacer(Modifier.height(6.px))
                    ShelfRow("type it here", r, focused = true, glyph = Glyphs.Text, chevron = true,
                        onClick = { nav.replace(Screen.PromptEdit(prompt)) })
                    if (page?.way == Way.Wifi && !page.used) Text("unencrypted on wi-fi", style = BeamType.Body, color = r.sub,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().paddingFromBaseline(top = 38.px))
                }
            }
            if (prompt == null && page != null && !page.used && page.choice) {
                Selector(listOf("tailnet", "wi-fi"), if (page.way == Way.Wifi) 1 else 0, r,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 22.px)) { i ->
                    if ((i == 1) != wifi) { wifi = i == 1; Cues.play(Sound.Commit) }
                }
            }
        }
    }
}
