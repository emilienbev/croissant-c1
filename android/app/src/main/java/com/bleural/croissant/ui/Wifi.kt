package com.bleural.croissant.ui

import com.bleural.croissant.wifi.WifiUi

/**
 * The list under the toggle row: "Connected" over the joined network, "Saved" over
 * the saved ones out of range, "Networks" over the rest, each label only when its
 * group has a row, in the repo's order within a group. Nothing when Wi-Fi is off.
 */
fun wifiLines(ui: WifiUi): List<Any> {
    if (!ui.enabled) return emptyList()
    val connected = ui.networks.filter { it.connected }
    val saved = ui.networks.filter { !it.connected && it.saved && it.level < 0 }
    val rest = ui.networks.filter { !it.connected && !(it.saved && it.level < 0) }
    return buildList {
        if (connected.isNotEmpty()) { add("Connected"); addAll(connected) }
        if (saved.isNotEmpty()) { add("Saved"); addAll(saved) }
        if (rest.isNotEmpty()) { add("Networks"); addAll(rest) }
    }
}
