package com.bleural.croissant.setup

/**
 * The first setup: the steps a new R1 walks through once the
 * installer has made the app Device Owner, in this order. Hello teaches the button and the
 * wheel first, because the rest of the setup is run with them; the time comes last, once
 * the network has had a chance to correct the clock.
 */
enum class Step { Hello, Wifi, Tailscale, Server, Time }

/**
 * Where the setup is. [open]: it is on screen (at first start until finished, or reopened
 * from Settings). [skipped]: steps left for Settings, which stay skipped until done there
 * or in a later run. [finished]: the last step has been passed once; a finished device
 * never opens it by itself again.
 */
data class SetupUi(
    val open: Boolean = false,
    val step: Step = Step.Hello,
    val skipped: Set<Step> = emptySet(),
    val finished: Boolean = false,
)

/**
 * A press of the side button as the setup hears it. While the setup is open the button
 * does nothing else, the short press included: nothing sleeps (2026-09-29).
 */
enum class Gesture { HoldStart, HoldEnd, Press, Twice }
