package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.atelier.AppSource
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.atelier.Use
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberAppGlyph

/**
 * An app's info, opened by a press and hold on its tool, in the app's own hue: its name the header
 * word; its glyph big at the top; "source" with the repo under it ("this computer" for one pushed
 * by the CLI); the commit it is pinned at (a GitHub app's only); "pin to wheel", or "unpin" once it
 * is (2026-10-17: a pinned app has its place on the side wheel, after Atelier, before Settings, in
 * the order pinned); "may use" and a glyph row for each use, nothing when it uses none; delete at
 * the foot, the sure pill ([SurePill]), red "sure?" for 3 s, a second tap deletes the app and its
 * state and steps back to Atelier. The wheel scrolls; up at the top is the pull back.
 */
@Composable
internal fun AtelierInfoPage(id: String, core: Core, nav: NavState) {
    val apps by core.atelier.apps.collectAsStateWithLifecycle()
    val pins by core.pins.collectAsStateWithLifecycle()
    // Kept while the page leaves after a delete, so it slides out as it was.
    val seen = remember { arrayOfNulls<InstalledApp>(1) }
    val app = apps.firstOrNull { it.id == id }?.also { seen[0] = it } ?: seen[0] ?: return
    val r = appRamp(app)
    val vector = rememberAppGlyph(app.manifest.glyph)
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    val src = app.source
    PageTop(app.manifest.name, r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            // The glyph 84 from y 74 at x 18; "source" on 209, the repo on 247, the commit's row from 266.
            Baselines(listOf(8f, 51f, 38f), bottom = 19f) {
                Box(Modifier.padding(start = 18.px).size(84.px)) {
                    if (vector != null) Icon(vector, null, Modifier.fillMaxSize(), r.hue)
                }
                Text("source", style = BeamType.Row, color = r.ink, maxLines = 1, modifier = Modifier.padding(start = 24.px, end = 24.px))
                Text(
                    if (src is AppSource.GitHub) src.repo else "this computer", style = BeamType.Value, color = r.sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.px, end = 24.px),
                )
            }
            if (src is AppSource.GitHub) ShelfRow("commit", r, value = shortSha(src.commit))
            val pinned = id in pins
            ShelfRow(if (pinned) "unpin" else "pin to wheel", r, glyph = Glyphs.Pin, onClick = {
                core.pin(id, !pinned)
                Cues.play(if (pinned) Sound.SwitchOff else Sound.SwitchOn)
            })
            val uses = Use.entries.filter { it in app.manifest.uses }
            if (uses.isNotEmpty()) {
                DotLabel("may use", r)
                uses.forEach { u -> ShelfRow(useWords(u), r, glyph = useGlyph(u)) }
            }
            // The tools it adds to Ask, off while it is disabled.
            if (app.manifest.tools.isNotEmpty()) {
                DotLabel(if (app.enabled) "adds to ask" else "adds to ask when on", r)
                app.manifest.tools.forEach { t -> ShelfRow(toolWords(t.name), r, glyph = Glyphs.Atelier, dim = !app.enabled) }
            }
            // The rows end beside the pill, never under it: three uses end on 596.
            Spacer(Modifier.height(24.px))
        }
        TopFade(r, scroll)
        // Off: kept on the board in grey, it does not open and offers Ask nothing; on again here.
        Box(Modifier.align(Alignment.BottomStart).padding(start = 24.px, bottom = (640f - PILL_TOP - 46f).px)) {
            Pill(if (app.enabled) "disable" else "enable", DIM, INK, onClick = {
                core.atelier.setEnabled(id, !app.enabled)
                Cues.play(if (app.enabled) Sound.SwitchOff else Sound.SwitchOn)
            })
        }
        SurePill("delete") { core.deleteAtelierApp(id); nav.pop(Sound.Destroy) }
    }
}
