package com.bleural.croissant.wake

/**
 * The battery rule for the always-on wake word: it listens while charging or at
 * [WakeConfig.BATTERY_MIN] percent or more. Under that, unplugged, it pauses, and comes back
 * by itself on a plug-in or back at the line. The switch stays as the user set it.
 */
object WakePower {
    fun allowed(percent: Int, charging: Boolean): Boolean = charging || percent >= WakeConfig.BATTERY_MIN
}
