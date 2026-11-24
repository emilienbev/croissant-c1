package com.bleural.croissant.wake

/**
 * The always-on wake word as Settings > Ask shows it: off, listening, or paused by the battery
 * rule ([WakePower]) at [percent]. A hold or an answer in progress is not a pause: it is only
 * not listening for that moment.
 */
data class WakeStatus(val state: State = State.Off, val percent: Int = -1) {
    enum class State { Off, Listening, Paused }
}
