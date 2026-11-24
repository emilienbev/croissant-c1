package com.bleural.croissant.wake

/** The always-on wake word's settings, in one place. */
object WakeConfig {
    /** The bundled model, in assets. */
    const val MODEL_ASSET = "wake/hey-muffin.tflite"
    /**
     * The model's cutoff. For hey-muffin v3, 0.98 is about 0.2 false wakes an hour on the
     * training's party-noise check, and caught more phrasings and distances than v2 did at 0.8.
     */
    const val CUTOFF = 0.98f
    /** After a pause, how long the listener ignores the model while its memory of before fades. */
    const val RESTART_DEAF_MS = 1000
    /** Battery percentage it needs when not charging. */
    const val BATTERY_MIN = 20

    // The end of a wake's ask (Endpoint).
    /** The phrase's tail after the wake, not counted as the ask. */
    const val GRACE_MS = 300
    /** No speech this long after the wake: there was no ask, and it goes back quietly. */
    const val NO_SPEECH_MS = 3000
    /** No speech this long after speech: the ask is over. */
    const val SILENCE_MS = 1500
    /** The longest ask. */
    const val MAX_MS = 15_000
    /** The speech detector's model (Silero VAD), in assets. */
    const val VAD_ASSET = "wake/silero_vad.onnx"
    /** Silero's speech probability from which a window counts as speech; 0.5 is its own default. */
    const val VAD_THRESHOLD = 0.5f
}
