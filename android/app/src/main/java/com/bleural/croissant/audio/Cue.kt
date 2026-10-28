package com.bleural.croissant.audio

/**
 * The vocabulary of the interface's sounds: each cue means one thing everywhere.
 * [Cues.cueFor] says which [Sound] plays which. A sound pack is one file per cue,
 * `assets/sounds/<pack>/<file>.ogg`, rendered by `sounds/earshot.py` with its level in it.
 */
enum class Cue(val file: String) {
    /** The wheel moved the highlight: the shortest and the quietest. */
    Step("step"),
    /** Home down to the apps. */
    ListIn("list_in"),
    /** The apps back up to Home. */
    ListOut("list_out"),
    /** A tap that did something and stayed on the page. */
    Tap("tap"),
    /** Into a screen. */
    Enter("enter"),
    /** A sheet comes up. */
    Open("open"),
    /** Back out of a screen or a sheet. */
    Back("back"),
    /** The control centre pulled down. */
    PanelIn("panel_in"),
    /** The control centre put away. */
    PanelOut("panel_out"),
    /** A switch turned on, a reminder ticked. */
    On("on"),
    /** A switch turned off, a reminder unticked. */
    Off("off"),
    /** A destructive thing confirmed. */
    Destroy("destroy"),
    /** Something failed. */
    Fail("fail"),
    /** A link came up. */
    LinkUp("link_up"),
    /** A link dropped. */
    LinkDown("link_down"),
    /** The camera shutter. */
    Shutter("shutter"),
    /** A hold is confirmed and listening starts. */
    Listen("listen"),
    /** Let go: listening ends. */
    Heard("heard"),
    /** An answer has arrived. */
    Arrived("arrived"),
}
