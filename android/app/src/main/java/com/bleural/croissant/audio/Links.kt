package com.bleural.croissant.audio

import android.os.SystemClock

/**
 * Connect and disconnect are for a link that changed on its own. When a tap changed it
 * (a switch, a join, a forget, sign in or out), that tap already sounded, and one
 * interaction makes one sound. So the taps call [touched], and a change reported within
 * [QUIET_MS] after one is not sounded. Changes during the first [SETTLE_MS] of the
 * process are the monitor finding the state it started in, not changes.
 */
object Links {
    /** Long enough for an association or a tailscaled restart to finish. */
    private const val QUIET_MS = 30_000L
    private const val SETTLE_MS = 10_000L

    private val born = SystemClock.uptimeMillis()
    @Volatile private var touchedAt = -QUIET_MS
    private var wifi: Boolean? = null
    private var tailnet: Boolean? = null

    fun touched() { touchedAt = SystemClock.uptimeMillis() }

    /** Called on every status update, from the main thread. */
    fun report(wifiUp: Boolean, tailnetUp: Boolean) {
        val was = wifi to tailnet
        wifi = wifiUp; tailnet = tailnetUp
        val now = SystemClock.uptimeMillis()
        if (was.first == null || now - born < SETTLE_MS || now - touchedAt < QUIET_MS) return
        val up = (wifiUp && was.first == false) || (tailnetUp && was.second == false)
        val down = (!wifiUp && was.first == true) || (!tailnetUp && was.second == true)
        when {
            down -> Cues.play(Sound.LinkDown)
            up -> Cues.play(Sound.LinkUp)
        }
    }
}
