package com.bleural.croissant.ui

import com.bleural.croissant.Core

enum class PowerAction(val label: String, val confirm: Boolean) {
    Sleep("Sleep", false),
    Reboot("Restart", true),
    PowerOff("Power off", true),
    ExitKiosk("Exit kiosk", true),
}

/** What Power's four rows ask of the device. */
fun Core.power(a: PowerAction) = when (a) {
    PowerAction.Sleep -> sleep()
    PowerAction.Reboot -> reboot()
    PowerAction.PowerOff -> powerOff()
    PowerAction.ExitKiosk -> exitKiosk()
}
