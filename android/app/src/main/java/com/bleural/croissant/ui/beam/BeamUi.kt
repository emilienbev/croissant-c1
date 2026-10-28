package com.bleural.croissant.ui.beam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.SoundEffect
import com.bleural.croissant.Core
import com.bleural.croissant.UiShell
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.CroissantTheme
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.RoomState
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.ShutterKey
import com.bleural.croissant.ui.Wheel

/**
 * Beam as a [UiShell]: the stock rabbitOS look, a pure black ground, white lowercase
 * words and one neon per feature. Its structure: Home, the menu of one panel per feature, and
 * the features opened from it. One variant, `night` (ui/Designs.kt).
 *
 * It owns the back stack and the room's springs, because the side button writes to both
 * from outside the composition.
 */
class BeamUi(val variant: String) : UiShell {

    private val nav = NavState()
    /**
     * Home, the panels and the open feature: the same springs as every design's room
     * (ui/Motion.kt). Its places are laid out again on Home (Shell.kt), and leaving Home lands
     * on Ask, under the recents (2026-10-17).
     */
    private val room = RoomState(PLACES, leavesToEntry = true)

    /** Home's day and its croissant (HomeDay.kt), here because a wake resets them. */
    private val home = HomeState()

    @Composable
    override fun Content(core: Core) {
        CompositionLocalProvider(LocalSoundEffect provides Silent) {
            // BeamType leaves most line heights unset, so they come from the theme's text style.
            CroissantTheme { BeamShell(core, nav, room, home) }
        }
    }

    override fun cutToAsk() {
        nav.home()
        room.cutToAsk()
    }

    override fun toggleCamera() {
        if (room.openTarget > 0.5f && room.last == Feature.Camera) {
            room.closePanel()
            Cues.play(Sound.ScreenOut)
        } else {
            nav.home()
            room.goTo(room.placeOf(Feature.Camera))
            room.leaveHome()
            room.openPanel()
            Cues.play(Sound.ScreenIn)
        }
    }

    /** Only an open feature can be the camera's live preview, and only that registers a shutter. */
    override fun shutterPress(): Boolean = room.openTarget > 0.5f && ShutterKey.press()

    override fun wheel(delta: Int) = Wheel.tick(delta)

    override fun openEsimAdd() {
        if (nav.current is Screen.EsimAdd) nav.replace(Screen.EsimAdd()) else nav.push(Screen.EsimAdd())
    }

    /** The first start of a new version: its page, saying so, over Home. */
    override fun openUpdate() {
        if (nav.current != Screen.Update) nav.push(Screen.Update)
    }

    /** Ask called one of the app's tools: its page over whatever is up, unless it is already. */
    override fun openAtelierApp(id: String) {
        if ((nav.current as? Screen.AtelierApp)?.id != id) nav.push(Screen.AtelierApp(id))
    }

    override fun settle() = room.snap()

    /** A wake puts Home's day away. */
    override fun woke() = home.snapShut()
}

/** Taps sound through what they do, never as the system click. */
private val Silent = object : SoundEffect {
    override fun playClickSound() {}
}
