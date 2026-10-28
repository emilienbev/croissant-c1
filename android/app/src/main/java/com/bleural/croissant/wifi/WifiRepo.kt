package com.bleural.croissant.wifi

import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.PowerManager
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

enum class Security { OPEN, PSK, SAE, WEP, EAP, UNKNOWN }

data class WifiNet(
    val ssid: String,
    val security: Security,
    /** 0..3 from the strongest BSS seen, or -1 when saved but not in range. */
    val level: Int,
    /** The configured network id when this SSID is saved. */
    val netId: Int?,
    val connected: Boolean,
) {
    val saved get() = netId != null
    val secured get() = security != Security.OPEN
    val joinable get() = security == Security.OPEN || security == Security.PSK || security == Security.SAE
}

data class WifiUi(
    val enabled: Boolean = false,
    val networks: List<WifiNet> = emptyList(),
    /** An SSID we asked to join and have not seen connect yet. */
    val connecting: String? = null,
    /** The last join that did not happen, on its row until the next tap. */
    val failed: JoinFailure? = null,
)

/** [wrongPassword]: the access point refused our key (a supplicant authentication failure); otherwise nothing answered in time. */
data class JoinFailure(val ssid: String, val wrongPassword: Boolean)

/**
 * The legacy WifiManager API, which Android 10+ closes to ordinary apps but leaves
 * open to Device Owner apps. No root anywhere here. The
 * list is scan results merged with the configured networks: connected first, then
 * saved, then by signal; saved networks out of range come last with no bars.
 */
@Suppress("DEPRECATION")
class WifiRepo(private val ctx: Context) {

    companion object {
        private const val TAG = "WifiRepo"
        private const val SCAN_EVERY_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
    }

    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val power = ctx.applicationContext.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(WifiUi())
    val state: StateFlow<WifiUi> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanLoop: Job? = null
    private var connectTimeout: Job? = null
    /** The network the current join added, so a failed join does not leave it saved. */
    private var added: Pair<String, Int>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) {
                // Android 13 still sends this (SupplicantStateTracker), with the error extra on
                // the state change after an authentication failure; it is sticky, so the copy
                // handed over at registration is ignored.
                if (!isInitialStickyBroadcast && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) {
                    authFailed(i.getIntExtra("supplicantErrorReason", 0))
                }
                return
            }
            refresh()
        }
    }

    fun start() {
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        })
        refresh()
        scanLoop = scope.launch {
            while (true) {
                // A Wi-Fi page stays composed while the R1 sleeps; Android refuses the scan
                // then anyway (location is a foreground-only grant), so skip it.
                if (wifi.isWifiEnabled && power.isInteractive) wifi.startScan()
                delay(SCAN_EVERY_MS)
            }
        }
    }

    fun stop() {
        scanLoop?.cancel(); scanLoop = null
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    fun setEnabled(on: Boolean) {
        val ok = wifi.setWifiEnabled(on)
        Log.i(TAG, "setWifiEnabled($on) -> $ok")
        _state.update { it.copy(enabled = on, networks = if (on) it.networks else emptyList(), failed = null) }
    }

    /** Join: a saved network by id, otherwise add it with [password] (null for open). */
    /** A network read off a Wi-Fi QR code, joined as a tapped row would be, with the code's password. */
    fun join(qr: WifiQr) {
        val net = qr.asNet(_state.value.networks.firstOrNull { it.ssid == qr.ssid })
        if (!net.connected) connect(net, qr.password)
    }

    fun connect(net: WifiNet, password: String? = null) {
        val id = net.netId ?: run {
            val cfg = WifiConfiguration().apply {
                SSID = quote(net.ssid)
                when (net.security) {
                    Security.OPEN -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
                    Security.SAE -> { setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE); preSharedKey = quote(password ?: "") }
                    else -> { setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK); preSharedKey = quote(password ?: "") }
                }
            }
            wifi.addNetwork(cfg).also { Log.i(TAG, "addNetwork(${net.ssid}) -> $it") }
        }
        if (id < 0) return
        added = if (net.netId == null) net.ssid to id else null
        val en = wifi.enableNetwork(id, true)
        val re = wifi.reconnect()
        Log.i(TAG, "connect ${net.ssid}: enable=$en reconnect=$re")
        _state.update { it.copy(connecting = net.ssid, failed = null) }
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value.connecting == net.ssid) {
                Log.i(TAG, "join ${net.ssid} timed out")
                joinFailed(net.ssid, wrongPassword = false)
            }
        }
        refresh()
    }

    /** The supplicant reported an authentication failure; [reason] is the framework's (2 is a wrong key, 1 a handshake timeout). */
    private fun authFailed(reason: Int) {
        val ssid = _state.value.connecting
        Log.i(TAG, "authentication failed, reason $reason, joining ${ssid ?: "nothing"}")
        if (ssid == null) return
        connectTimeout?.cancel()
        joinFailed(ssid, wrongPassword = reason != 1)
    }

    /**
     * The join is over without a connection: the row says why until the next tap. A
     * network this join added is removed rather than kept with a key that never worked,
     * so the row goes back under Networks and a tap asks for the password again.
     */
    private fun joinFailed(ssid: String, wrongPassword: Boolean) {
        added?.let { (s, id) -> if (s == ssid) Log.i(TAG, "removeNetwork(${s}) after the failed join -> ${wifi.removeNetwork(id)}") }
        added = null
        _state.update { it.copy(connecting = null, failed = JoinFailure(ssid, wrongPassword)) }
        Cues.play(Sound.JoinFailed)
        refresh()
    }

    fun forget(net: WifiNet) {
        val id = net.netId ?: return
        Log.i(TAG, "removeNetwork(${net.ssid}) -> ${wifi.removeNetwork(id)}")
        refresh()
    }

    fun refresh() {
        val enabled = wifi.isWifiEnabled
        if (!enabled) { _state.update { it.copy(enabled = false, networks = emptyList()) }; return }

        // WifiInfo names the network from the first association frame, so both the id and
        // the SSID count only once the supplicant has completed; otherwise a join in
        // progress reads as connected (and a failure as "joining nothing", 2026-09-16).
        val info = wifi.connectionInfo?.takeIf { it.supplicantState == SupplicantState.COMPLETED }
        val connectedId = info?.networkId ?: -1
        val connectedSsid = info?.ssid?.let(::unquote)?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

        val saved: Map<String, WifiConfiguration> = try {
            wifi.configuredNetworks.orEmpty().associateBy { unquote(it.SSID ?: "") }
        } catch (e: SecurityException) { Log.w(TAG, "configuredNetworks: $e"); emptyMap() }

        val seen: List<ScanResult> = try { wifi.scanResults } catch (e: SecurityException) { Log.w(TAG, "scanResults: $e"); emptyList() }
        val strongest = seen.filter { it.SSID.isNotEmpty() }.groupBy { it.SSID }.mapValues { (_, rs) -> rs.maxBy { it.level } }

        val list = mutableListOf<WifiNet>()
        for ((ssid, r) in strongest) {
            val cfg = saved[ssid]
            list += WifiNet(
                ssid = ssid,
                security = security(r.capabilities),
                level = WifiManager.calculateSignalLevel(r.level, 4),
                netId = cfg?.networkId,
                connected = cfg != null && cfg.networkId == connectedId || ssid == connectedSsid,
            )
        }
        for ((ssid, cfg) in saved) if (ssid !in strongest && ssid.isNotEmpty()) {
            list += WifiNet(ssid, securityOf(cfg), -1, cfg.networkId, cfg.networkId == connectedId)
        }
        val sorted = list.sortedWith(compareByDescending<WifiNet> { it.connected }.thenByDescending { it.saved }.thenByDescending { it.level }.thenBy { it.ssid.lowercase() })
        _state.update { s ->
            val stillConnecting = s.connecting?.takeIf { c -> sorted.none { it.ssid == c && it.connected } }
            val stillFailed = s.failed?.takeIf { f -> sorted.none { it.ssid == f.ssid && it.connected } }
            s.copy(enabled = true, networks = sorted, connecting = stillConnecting, failed = stillFailed)
        }
    }

    private fun security(caps: String): Security = when {
        "SAE" in caps && "PSK" !in caps -> Security.SAE
        "PSK" in caps -> Security.PSK
        "WEP" in caps -> Security.WEP
        "EAP" in caps -> Security.EAP
        else -> Security.OPEN
    }

    private fun securityOf(cfg: WifiConfiguration): Security = when {
        cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> Security.SAE
        cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> Security.PSK
        cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP) -> Security.EAP
        cfg.wepKeys?.any { it != null } == true -> Security.WEP
        else -> Security.OPEN
    }

    private fun quote(s: String) = "\"" + s + "\""
    private fun unquote(s: String) = s.removeSurrounding("\"")
}
