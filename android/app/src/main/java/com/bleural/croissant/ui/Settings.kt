package com.bleural.croissant.ui

import com.bleural.croissant.BatteryTracker

data class SettingsInfo(
    val ssid: String? = null,
    val serverLabel: String = "",
    val volume: Int = 0,
    val muted: Boolean = false,
    val brightness: Int = 0,
    val batteryTrack: Boolean = true,
    val batteryEvery: Int = BatteryTracker.DEFAULT_EVERY_MS,
)
