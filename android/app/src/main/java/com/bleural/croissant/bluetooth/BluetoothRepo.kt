package com.bleural.croissant.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BtDevice(val name: String, val address: String, val connected: Boolean)

data class BluetoothUi(
    val enabled: Boolean = false,
    /** The paired devices, connected ones first, by name. */
    val devices: List<BtDevice> = emptyList(),
)

/**
 * The radio and the paired list, for the control centre's tile and the Bluetooth page.
 * Enabling and disabling are the deprecated `BluetoothAdapter` calls, which Android 13
 * still lets a Device Owner make (every other app gets false back). The connected
 * state comes from the audio profiles, A2DP and headset, which is what a paired thing
 * would be on this device. Nothing pairs a new device yet: the system's pairing dialog
 * cannot show under lock task, and no other way has been tried.
 */
@SuppressLint("MissingPermission")
class BluetoothRepo(private val ctx: Context) {

    companion object {
        private const val TAG = "BluetoothRepo"
    }

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val _state = MutableStateFlow(BluetoothUi(enabled = adapter?.isEnabled == true))
    val state: StateFlow<BluetoothUi> = _state

    private val proxies = HashMap<Int, BluetoothProfile>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; refresh() }
        override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
    }

    fun start() {
        val a = adapter ?: return
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        })
        for (p in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) a.getProfileProxy(ctx, listener, p)
        refresh()
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
        for ((p, proxy) in proxies) adapter?.closeProfileProxy(p, proxy)
        proxies.clear()
    }

    /** Works without [start]: the control centre's tile flips the radio from anywhere. */
    @Suppress("DEPRECATION")
    fun setEnabled(on: Boolean) {
        val a = adapter ?: return
        val ok = if (on) a.enable() else a.disable()
        Log.i(TAG, "${if (on) "enable" else "disable"}() -> $ok")
        _state.update { it.copy(enabled = on, devices = if (on) it.devices else emptyList()) }
    }

    fun refresh() {
        val a = adapter ?: return
        val enabled = a.isEnabled
        if (!enabled) { _state.update { it.copy(enabled = false, devices = emptyList()) }; return }
        val connected = proxies.values.flatMap { runCatching { it.connectedDevices }.getOrDefault(emptyList()) }.map { it.address }.toSet()
        val bonded = runCatching { a.bondedDevices.orEmpty() }.onFailure { Log.w(TAG, "bondedDevices: $it") }.getOrDefault(emptySet())
        val list = bonded.map { d -> BtDevice(d.name?.takeIf { it.isNotBlank() } ?: d.address, d.address, d.address in connected) }
            .sortedWith(compareByDescending<BtDevice> { it.connected }.thenBy { it.name.lowercase() })
        _state.update { it.copy(enabled = true, devices = list) }
    }
}
