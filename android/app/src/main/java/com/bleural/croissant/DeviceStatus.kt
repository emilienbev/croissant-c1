package com.bleural.croissant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * The mobile network, as the eSIM page shows it. Null when no profile is
 * in use: the empty card presents a placeholder USIM on MCC 111, which is no country's,
 * and there is nothing to say about it.
 */
data class Cell(
    /** 0 to 4, the platform's own levelling of the signal. */
    val bars: Int,
    /** The mobile data switch, which is the app's to turn (Settings > eSIM). */
    val dataEnabled: Boolean,
    /** A cellular network the platform has validated: data is really carrying. */
    val dataUp: Boolean,
)

/** The device's state, as the control centre and Settings show it. */
data class DeviceStatus(
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    /** The connected network's name, or null when there is none or it is not readable. */
    val ssid: String? = null,
    val tailnetUp: Boolean = false,
    val tailnetIp: String? = null,
    /** The radio's state, for the control centre's tile. */
    val bluetoothOn: Boolean = false,
    /** 0-100, or -1 until the first battery broadcast. */
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    /** The mobile network, or null when no eSIM profile is in use. */
    val cell: Cell? = null,
)

/**
 * Wi-Fi from the connectivity callbacks, battery from the sticky broadcast, tailnet by
 * polling the tailscale0 interface (tailscaled is a root daemon; its socket is not ours
 * to read without root, but the interface it creates is visible to any process), the
 * mobile network from a [TelephonyCallback] and a cellular network callback.
 *
 * The modem costs nothing while there is no profile in use. [readCell] gets that far on
 * one binder call and stops, and the [TelephonyCallback] is registered only while it has
 * something to say: the poll, which runs for the tailnet anyway, is also what notices a
 * SIM arriving, so no broadcast is needed and the gate cannot get stuck shut.
 */
class DeviceStatusMonitor(private val ctx: Context) {

    companion object {
        private const val TAG = "DeviceStatus"
        private const val TAILNET_POLL_MS = 5_000L
        private const val TAILNET_SOON_MS = 500L
        private const val TAILNET_SOON_TIMES = 10
        /** No country has MCC 111: it is the empty eUICC's placeholder USIM. */
        private const val PLACEHOLDER_MCC = "111"
    }

    private val _state = MutableStateFlow(DeviceStatus())
    val state: StateFlow<DeviceStatus> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null

    private val cm get() = ctx.getSystemService(ConnectivityManager::class.java)
    private val wifi get() = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val telephony get() = ctx.getSystemService(TelephonyManager::class.java)

    // The modem's last word on each, kept because the state is read from all of them at
    // once: any one arriving recomputes what the bar shows.
    @Volatile private var signal: SignalStrength? = null
    /** The last state logged, so the line is written when the modem changes, not every poll. */
    @Volatile private var told: Cell? = null
    /** Whether [cellCallback] is registered; see [watchModem]. */
    @Volatile private var watching = false

    private val phone = Executors.newSingleThreadExecutor { r -> Thread(r, "cell").apply { isDaemon = true } }

    // No listener for the data switch: TelephonyCallback.DataEnabledListener is a system
    // API. It is read with everything else instead, which the network coming up or going
    // away covers within a second of the toggle, and the poll below backs up.
    private inner class CellCallback : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) { signal = s; readCell() }
        // Only a nudge: the service state decides nothing the bar shows, but the signal
        // is worth reading again the moment it changes.
        override fun onServiceStateChanged(s: ServiceState) { readCell() }
    }

    private val cellCallback = CellCallback()

    // Only a nudge to read again: the capabilities are read from the manager itself, since
    // this callback did not deliver them on this build (2026-09-17). The poll backs it up.
    private val dataCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { readCell() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { readCell() }
        override fun onLost(network: Network) { readCell() }
    }

    /**
     * Whether a cellular network is up and the platform has validated it: data is really
     * carrying, not merely switched on. Asked of the manager rather than kept from a
     * callback, so one source answers and a missed callback cannot leave it stale.
     */
    @Suppress("DEPRECATION")
    private fun dataCarrying(): Boolean = cm.allNetworks.any { n ->
        cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }

    // The name is read on the callback's own thread, never on the main one: the
    // capabilities change fires once the network is up (onAvailable can be too early
    // for the name) and again whenever it changes.
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = _state.update { it.copy(wifiConnected = true) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = _state.update { it.copy(wifiConnected = true, ssid = currentSsid()) }
        override fun onLost(network: Network) = _state.update { it.copy(wifiConnected = false, ssid = null) }
    }

    /** The connected SSID, or null when there is none or it is not readable. */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val info = wifi.connectionInfo ?: return null
        val ssid = info.ssid?.removeSurrounding("\"") ?: return null
        return if (ssid.isEmpty() || ssid == "<unknown ssid>" || info.networkId == -1) null else ssid
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handle(i)
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the latest one.
        ctx.registerReceiver(receiver, filter)?.let(::handle)
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        _state.update { it.copy(wifiEnabled = wifi.isWifiEnabled, bluetoothOn = bt?.isEnabled == true) }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(),
            dataCallback
        )
        watchModem(readCell())
        poll = scope.launch {
            while (true) {
                readTailnet()
                // The data switch and the SIM have no callback of their own here, and this
                // is also what notices a SIM arriving or leaving: one binder call when
                // there is none, which is the whole background cost of the feature.
                watchModem(readCell())
                delay(TAILNET_POLL_MS)
            }
        }
    }

    /**
     * Looks at the tailnet now and every [TAILNET_SOON_MS] for a few seconds, rather than at
     * the next poll: a switch that turned it on or off shows what it did within a second or
     * two. `tailscale up` takes that long to bring the interface's address back.
     */
    fun recheckTailnet() {
        scope.launch { repeat(TAILNET_SOON_TIMES) { delay(TAILNET_SOON_MS); readTailnet() } }
    }

    private fun readTailnet() {
        val ip = tailnetAddress()
        _state.update { it.copy(tailnetIp = ip, tailnetUp = ip != null) }
    }

    fun stop() {
        poll?.cancel(); poll = null
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(dataCallback) }
        watchModem(false)
    }

    /**
     * Registers [cellCallback] while there is a profile in use, and drops it when there is
     * not. Called from the poll and from [stop], hence the lock.
     *
     * The last signal it reported is kept rather than cleared: the screen going off drops
     * the callback and turning it on takes it again, and clearing meant the bar was unlit
     * for the moment between the first read and the modem answering, on every wake
     * (2026-09-17). With no profile in use nothing reads it anyway.
     */
    @Synchronized
    private fun watchModem(want: Boolean) {
        if (want == watching) return
        val ok = runCatching {
            if (want) telephony.registerTelephonyCallback(phone, cellCallback)
            else telephony.unregisterTelephonyCallback(cellCallback)
        }.onFailure { Log.w(TAG, "no telephony callback", it) }.isSuccess
        if (ok) watching = want
    }

    /**
     * What the modem is doing, from whatever it has said so far, and whether there is a
     * profile in use at all, which is what [watchModem] gates on. Off the main thread: the
     * callbacks arrive on their own executor and the reads are binder calls. With no SIM it
     * is one of them and no further.
     */
    private fun readCell(): Boolean {
        val tm = telephony
        val inUse = tm.simState == TelephonyManager.SIM_STATE_READY && !tm.simOperator.startsWith(PLACEHOLDER_MCC)
        val cell = if (!inUse) null else Cell(
            bars = signal?.level?.coerceIn(0, 4) ?: 0,
            dataEnabled = dataEnabled(),
            dataUp = dataCarrying(),
        )
        if (cell != told) {
            Log.i(TAG, "cell $cell")
            told = cell
        }
        _state.update { it.copy(cell = cell) }
        return inUse
    }

    /** The mobile data switch. `isDataEnabled` needs a permission the owner grants us; the setting is the fallback. */
    private fun dataEnabled(): Boolean = runCatching { telephony.isDataEnabled }
        .getOrElse { Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0) == 1 }

    private fun handle(i: Intent) {
        when (i.action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                _state.update {
                    it.copy(batteryPct = if (level < 0) -1 else level * 100 / scale, charging = plugged)
                }
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val s = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                val enabled = s == WifiManager.WIFI_STATE_ENABLED
                _state.update { it.copy(wifiEnabled = enabled, ssid = if (enabled) it.ssid else null) }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                // On while it is on or coming up, so the tile answers the tap at once.
                val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                _state.update { it.copy(bluetoothOn = s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_TURNING_ON) }
            }
        }
    }

    /** The tailscale0 IPv4 address, or null if the interface is absent, down or unreadable. */
    private fun tailnetAddress(): String? = try {
        val nif = NetworkInterface.getByName("tailscale0")
        if (nif == null || !nif.isUp) null
        else nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "tailscale0 unreadable: $e")
        null
    }
}
