package com.bleural.croissant.ui

/** What the four buttons ask of the service. */
enum class RecorderAction { Record, Pause, Resume, Stop }

/** "0:17:13": hours always shown, the tape's own way of counting. */
fun counterText(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
