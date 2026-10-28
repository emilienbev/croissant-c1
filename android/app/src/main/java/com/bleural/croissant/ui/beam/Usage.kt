package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import com.bleural.croissant.Core
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.Usage
import com.bleural.croissant.net.UsageSlot
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.NavState
import java.util.Locale

/**
 * Usage: the last 30 days as the server counted and priced them. The total large in the hue with
 * "last 30 days", a bar a day, then a row per job the server ran (chat, speech, pictures,
 * transform), what it took under its name and what it cost at its end, free where this Mac or a
 * free tier did it, and no cost where part of it had no known price (none shown, as on the cards).
 * The server is asked each time the page opens; with nothing counted yet, the page says so.
 */
@Composable
internal fun UsagePage(core: Core, nav: NavState) {
    val r = rampOf(Feature.Settings)
    LaunchedEffect(Unit) { core.refreshUsage() }
    val usage = core.usage
    val jobs = usage?.let { u -> SLOTS.mapNotNull { look -> u.slots[look.slot]?.let { look to it } } }.orEmpty()
    val rows = rememberRows(jobs.size, nav::pop, itemOf = { it + 1 }, topOf = { if (it == 0) 0 else it + 1 })
    val text = rememberTextMeasurer(cacheSize = 32)
    PageTop("usage", r) {
        ShelfList(rows, r) {
            item {
                if (usage == null || jobs.isEmpty()) {
                    SettingsWords(if (usage == null) "The server does not answer." else "Nothing used yet.", r.sub)
                } else Canvas(Modifier.fillMaxWidth().height(206.px)) {
                    pageScale {
                        usageValue(usage)?.let { text(text, 16f, 98f, it, BeamType.Huge, r.hue) }
                        text(text, 456f, 98f, "last ${usage.days} days", BeamType.Label, r.sub, Anchor.End)
                        days(usage, r)
                    }
                }
            }
            items(jobs.size) { i ->
                val (look, s) = jobs[i]
                val g = rememberVectorPainter(look.glyph)
                val focused = rows.focus == i
                Canvas(Modifier.fillMaxWidth().height(104.px)) {
                    pageScale {
                        glyph(g, 24f, 16f, 40f, r.hue)
                        text(text, 84f, 49f, look.word, if (focused) BeamType.RowFocused else BeamType.Row, if (focused) r.hue else r.ink)
                        cost(s.free, s.priced, s.cost)?.let { text(text, 456f, 49f, it, BeamType.Value, if (focused) r.hue else r.sub, Anchor.End) }
                        text(text, 84f, 86f, took(look.slot, s), BeamType.Label, r.sub)
                    }
                }
            }
            item { Spacer(Modifier.height(24.px)) }
        }
    }
}

/** A bar a day, oldest at the left: 8 wide every 14.4, to 60 tall at the dearest day; a day with nothing a faint stub. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.days(u: Usage, r: Ramp) {
    val max = u.daily.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    u.daily.forEachIndexed { i, v ->
        val h = if (v > 0) 8f + (v / max).toFloat() * 52f else 4f
        drawRoundRect(if (v > 0) r.hue else FAINT, Offset(24f + i * 14.4f, 186f - h), Size(8f, h), CornerRadius(4f))
    }
}

/** What a job took, under its name: tokens for chat, minutes of speech, pictures made. */
private fun took(slot: ModelSlot, s: UsageSlot): String = when (slot) {
    ModelSlot.Chat -> "${count(s.tokens)} tokens"
    ModelSlot.Stt -> if (s.secs < 60) "${s.secs} s" else "${s.secs / 60} min"
    else -> "${s.pictures} made"
}

/** 1.3M, 412K, 950. */
private fun count(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> "${n / 1000}K"
    else -> n.toString()
}

/** What a use cost: free when it all was, nothing when part of it had no price, else dollars. */
private fun cost(free: Boolean, priced: Boolean, v: Double): String? = when {
    free -> "free"
    !priced -> null
    else -> dollars(v)
}

/** The 30 days' total, as the page and the Settings row say it. */
internal fun usageValue(u: Usage?): String? =
    if (u == null || u.slots.isEmpty()) null else cost(u.allFree, u.priced, u.total)
