package com.bleural.croissant.wake

/** The always-on wake word's settings, in one place. */
object WakeConfig {
    /** The bundled model, in assets. */
    const val MODEL_ASSET = "wake/hey-muffin.tflite"
    /**
     * The model's cutoff. For hey-muffin v2, 0.8 is about 0.4 false wakes an hour on the
     * training's party-noise check, and caught most phrasings in testing on the R1.
     */
    const val CUTOFF = 0.8f
    /** After a pause, how long the listener ignores the model while its memory of before fades. */
    const val RESTART_DEAF_MS = 1000
    /** Battery percentage it needs when not charging. */
    const val BATTERY_MIN = 20

    // The end of a wake's ask (Endpoint).
    /** The phrase's tail after the wake, not counted as the ask. */
    const val GRACE_MS = 300
    /** No speech this long after the wake: there was no ask, and it goes back quietly. */
    const val NO_SPEECH_MS = 3000
    /** Quiet this long after speech: the ask is over. */
    const val SILENCE_MS = 800
    /** The longest ask. */
    const val MAX_MS = 15_000
    /** Speech is this far over the room's floor... */
    const val SPEECH_OVER_FLOOR_DB = 10f
    /** ...and at least this loud. */
    const val SPEECH_MIN_DB = -55f
}
