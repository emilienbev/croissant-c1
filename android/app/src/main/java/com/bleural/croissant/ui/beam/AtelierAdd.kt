package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.atelier.Atelier
import com.bleural.croissant.atelier.RepoRef
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberQrScanner

/*
 * Adding an app, in coral: its GitHub repo typed as owner/repo, or its QR code scanned. Found, the
 * add page steps back to Atelier, where the install sheet asks (Atelier.kt).
 */

/** A repo as `find` takes it again: owner/repo, with its ref when it has one. */
private fun RepoRef.typed(): String = slug + (ref?.let { "@$it" } ?: "")

/**
 * Add an app: the kit's field under the header with the keys up from the start, in uri mode with
 * find in coral; one line on 194 under it: "github owner/repo" in sub while nothing is asked,
 * "looking on github" while it fetches, why not in danger when it fails (typing again takes it
 * away). The scan key, the QR glyph, rides above the keys and sits at the foot when they are away.
 * Found, the page steps back to Atelier and the sheet asks there; left any other way, the add flow
 * ends.
 */
@Composable
internal fun AtelierAddPage(core: Core, nav: NavState) {
    val r = ATELIER
    val adding by core.atelier.adding.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { requester.requestFocus() }
    // A code read on the scan page comes back as what it found: the field shows it.
    LaunchedEffect(adding) {
        val a = adding as? Atelier.Adding.Finding ?: return@LaunchedEffect
        if (RepoRef.parse(text) != a.repo) text = a.repo.typed()
    }
    // Found: back to Atelier, where the sheet asks (once this page is the one in front).
    val front = nav.current == Screen.AtelierAdd
    val isFound = adding is Atelier.Adding.Found
    LaunchedEffect(isFound, front) { if (isFound && front) nav.pop(Sound.SheetIn) }
    DisposableEffect(Unit) { onDispose { if (core.atelier.adding.value !is Atelier.Adding.Found) core.atelier.dismiss() } }
    fun find() {
        if (text.isBlank()) return
        focus.clearFocus()
        Cues.play(Sound.Commit)
        core.atelier.find(text.trim())
    }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop("add an app", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            Spacer(Modifier.height(22.px))
            Field(
                text, { typed -> text = typed; if (core.atelier.adding.value is Atelier.Adding.Failed) core.atelier.dismiss() }, r,
                Modifier.focusRequester(requester),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { find() }),
                action = "find",
            )
            val (line, colour) = when (val a = adding) {
                is Atelier.Adding.Finding -> "looking on github" to r.sub
                is Atelier.Adding.Failed -> a.why to r.danger
                else -> "github owner/repo" to r.sub
            }
            // The hint is a label; what came of find is words.
            Text(
                line, style = if (adding is Atelier.Adding.Idle || adding is Atelier.Adding.Found) BeamType.Label else BeamType.Body,
                color = colour, maxLines = 2,
                modifier = Modifier.padding(start = 24.px, end = 24.px).paddingFromBaseline(top = 46.px),
            )
            Spacer(Modifier.height(112.px))
        }
        QuietKey(Glyphs.Scan, r, onClick = { focus.clearFocus(); nav.push(Screen.AtelierScan) })
    }
}

/**
 * Scanning a repo's code: the viewfinder as the server's scan page draws it, "point at the repo's
 * qr code". A code of a github.com link or of owner/repo goes to find as typed, and the page steps
 * back to the field, which shows it and what came of it.
 */
@Composable
internal fun AtelierScanPage(core: Core, nav: NavState) {
    val scanner = rememberQrScanner({ RepoRef.parse(it) }) { repo ->
        core.atelier.find(repo.typed())
        nav.pop(Sound.Commit)
    }
    QrScanPage("add an app", ATELIER, scanner, "point at the repo's qr code", "that code is not a github repo", onBack = nav::pop)
}
