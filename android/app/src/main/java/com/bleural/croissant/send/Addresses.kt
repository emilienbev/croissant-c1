package com.bleural.croissant.send

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The two ways in, as the Send screen offers them.
 *
 * Read off the interface rather than through ConnectivityManager: the active network is
 * not a reliable answer here, and `wlan0` by name is. The tailnet address is not read
 * here at all, since `TailscaleRepo` already holds it.
 */
object Addresses {

    private const val TAG = "Addresses"

    /** wlan0's IPv4, or null when the device is not on Wi-Fi. */
    fun lan(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name == "wlan0" && it.isUp }
            ?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "no interfaces: $e"); null
    }
}
