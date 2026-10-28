package com.bleural.croissant

import androidx.compose.runtime.Composable

/**
 * The seam between the device and its look. Everything under `com.bleural.croissant.ui` sits
 * behind this; [MainActivity] hands `ui/Designs.kt`'s builder to [Core] and knows nothing
 * else about the UI, and [Core] talks to whichever design is on only through the calls below.
 *
 * To put a new design on the device: implement this in its own package under `ui/`, and
 * add it to `ui/Designs.kt`. Nothing in `Core` or under it needs to move.
 *
 * The calls are the whole of what the hardware and the exchange can ask of a UI. They are
 * not events the UI subscribes to: they are made from outside the composition (a key
 * arrives, the service changes phase, the screen is about to sleep), which is why they are
 * plain methods on a long-lived object rather than state.
 */
/**
 * Which design is on, and which of its variants. Two ids the device stores and never reads:
 * what they name is the UI side's business (`ui/Designs.kt`), and an id it no longer knows
 * falls back to its first design, so a stale pref can never leave the device without a face.
 */
data class Look(val design: String, val variant: String) {
    companion object {
        /** Nothing chosen yet: the first design, its first variant. */
        val DEFAULT = Look("", "")
    }
}

interface UiShell {

    /** The whole app, drawn. Called once, inside `setContent`; [core] is the device. */
    @Composable
    fun Content(core: Core)

    /**
     * A confirmed hold: show Ask, listening, over whatever was on screen. The hold has
     * already sounded its own cue, so bringing Ask up is silent.
     */
    fun cutToAsk()

    /** The double press: the camera, opened from anywhere, and closed again if it is up. */
    fun toggleCamera()

    /**
     * A short press while the camera's live preview is up. True if the UI took it as the
     * shutter, which means it was neither a sleep nor the first half of a double press.
     */
    fun shutterPress(): Boolean

    /** One detent of the scroll wheel: -1 up, +1 down. */
    fun wheel(delta: Int)

    /** An eSIM activation code arrived on a link: its page should come up, filled in. */
    fun openEsimAdd()

    /** The first start of a new version: the update page should come up, saying so. A design without one ignores it. */
    fun openUpdate() {}

    /**
     * Ask called a tool of the Atelier app [id]: its page should come up, where the app acts
     * (Core has started it). A design without Atelier ignores it.
     */
    fun openAtelierApp(id: String) {}

    /**
     * Sleep is imminent. Snap anything animating to its end, so waking shows a settled
     * screen and replays nothing.
     */
    fun settle()

    /**
     * The screen has just woken (and on the first start). Whatever this shows is on the
     * first frame seen: it lands before the panel is lit.
     */
    fun woke()
}
