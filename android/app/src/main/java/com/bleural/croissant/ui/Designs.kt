package com.bleural.croissant.ui

import androidx.compose.runtime.Composable
import com.bleural.croissant.Core
import com.bleural.croissant.Look
import com.bleural.croissant.UiShell
import com.bleural.croissant.ui.beam.BeamUi

/**
 * Every design the device can wear, and each one's variants. The one list MainActivity
 * builds from and every design's own Settings picker reads, so a design added here is
 * offered by all of them.
 *
 * A design is a [UiShell] in its own package under `ui/`. A variant is a way of drawing
 * the same design (a palette, say): the design's builder takes the variant's id and gives
 * it whatever meaning it likes. Designs share what is under `ui/` and not a drawing: the
 * stack, the wheel, the motion (ui/Motion.kt), the glyphs, the list shaping, the camera rig,
 * the keyboard (ui/Keyboard.kt).
 */
object Designs {

    class Variant(val id: String, val name: String)

    class Design(val id: String, val name: String, val variants: List<Variant>, val build: (variant: String) -> UiShell)

    val all: List<Design> = listOf(
        // First, so it is what a new R1 wears.
        Design("beam", "Beam", listOf(Variant("night", "Night"))) { BeamUi(it) },
    )

    /** One entry per design and variant, for a picker: the look and the words for it. */
    val looks: List<Pair<Look, String>> = all.flatMap { d ->
        d.variants.map { v -> Look(d.id, v.id) to if (d.variants.size > 1) "${d.name} ${v.name}" else d.name }
    }

    /** The look [l] names, or the first design and its first variant for whatever it does not. */
    fun resolve(l: Look): Look {
        val d = all.find { it.id == l.design } ?: all.first()
        val v = d.variants.find { it.id == l.variant } ?: d.variants.first()
        return Look(d.id, v.id)
    }

    fun build(l: Look): UiShell {
        val r = resolve(l)
        return Keyless(all.first { it.id == r.design }.build(r.variant))
    }

    /**
     * A design under [NoSystemKeyboard], the whole of its content: whatever field it draws,
     * the system's keyboard never comes (ui/Keyboard.kt). The rest is the design's own.
     */
    private class Keyless(private val shell: UiShell) : UiShell by shell {
        @Composable
        override fun Content(core: Core) = NoSystemKeyboard { shell.Content(core) }
    }

    /** What a Settings row says about [l]. */
    fun label(l: Look): String = resolve(l).let { r -> looks.first { it.first == r }.second }
}
