package com.bleural.croissant.ui.beam

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bleural.croissant.ui.Glyphs

/**
 * How Beam draws each agent the bridge can run (acp-bridge/acp_bridge/agents/, by the same id):
 * its card on the Agents page and its place when pinned to the wheel. One line an agent; one the
 * bridge has that is not here is drawn as Agents itself. Generic glyphs, never a brand's logo.
 * The hues are light enough to carry black words, as every feature's (picked 2026-10-21,
 * provisional).
 */
internal class AgentLook(val hue: Color, val glyph: ImageVector) {
    /**
     * Its pages' ramp. A hue as white as the words (Hermes') would leave a focused row looking
     * like the rest, so there the words are [QUIET] and only the focused row is white.
     */
    val ramp: Ramp by lazy {
        val base = ramp(hue)
        if (!whiteish(hue)) base
        else Ramp(hue = INK, ground = base.ground, shelf = base.shelf, deep = base.deep, fill = hue, sub = base.sub, ink = QUIET, knob = hue)
    }
}

/** The words on a white agent's pages that are not focused: between the white and the labels' grey. */
private val QUIET = Color(0xFFC4C4C4)

private fun whiteish(c: Color): Boolean = c.red > 0.9f && c.green > 0.9f && c.blue > 0.9f

private val LOOKS = mapOf(
    // Claude's orange.
    "claude" to AgentLook(Color(0xFFE9844A), Glyphs.Asterisk),
    // A periwinkle, Codex's blue to violet.
    "codex" to AgentLook(Color(0xFFA7B5FF), Glyphs.Code),
    // White, Hermes' black and white.
    "hermes" to AgentLook(Color(0xFFF2F2F2), Glyphs.Feather),
    // Pi's own mint.
    "pi" to AgentLook(Color(0xFF4CF2B8), Glyphs.Pi),
)

private val DEFAULT by lazy { AgentLook(AGENTS.hue, Glyphs.Agents) }

internal fun agentLook(id: String): AgentLook = LOOKS[id] ?: DEFAULT
