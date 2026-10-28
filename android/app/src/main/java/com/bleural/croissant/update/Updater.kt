package com.bleural.croissant.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.bleural.croissant.BuildConfig
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.Prefs
import com.bleural.croissant.tailscale.Root
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class UpdatePhase {
    /** Nothing newer is known: up to date once [UpdateUi.checkedAt] says it was looked for. */
    Idle,
    Checking,
    /** A newer release is out, not downloaded. */
    Out,
    /** Downloading, then checking what came down; never locked, it goes on behind every page and through sleep. */
    Downloading,
    /** Downloaded and checked: size, sha256, signer, version. */
    Ready,
    /** Locked: from the tap until the install has replaced this app. */
    Installing,
    /** Locked: the new version compiling its code, then restarting. */
    Finishing,
    /** The first start of the new version, until the page is put away. */
    Updated,
}

enum class UpdateProblem { Offline, NoReleases, Limited, OtherKey, Damaged, NoSpace, NotInstalled }

/** Why a download waits to try again. */
enum class Stall {
    /** The connection broke. */
    Dropped,
    /** GitHub answered with an error. */
    Server,
}

/** What the update page draws. */
data class UpdateUi(
    val installed: Version,
    val source: Source,
    /** The source is the build's own: resetting it does nothing. */
    val own: Boolean,
    val daily: Boolean,
    val onData: Boolean,
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** The newer release, or the one just installed while [UpdatePhase.Updated]. */
    val release: Release? = null,
    /** When releases were last looked at, 0 before the first time. */
    val checkedAt: Long = 0L,
    /** A look has answered since this process started: only then is "up to date" said. */
    val answered: Boolean = false,
    val problem: UpdateProblem? = null,
    /** With [UpdateProblem.NotInstalled]: why, when the system said. */
    val failure: InstallFailure? = null,
    /** With [UpdateProblem.NoSpace]: the free space installing needs. */
    val roomNeeded: Long = 0L,
    /** Bytes downloaded. */
    val done: Long = 0L,
    /** A download's time left at its recent pace; null until there is a pace to go by. */
    val leftMs: Long? = null,
    /** The whole file is down and being checked: its size, its sha256 and its signer. */
    val verifying: Boolean = false,
    /** A download stopped until Wi-Fi, or the eSIM's data where it is allowed, is back. */
    val waitingForNetwork: Boolean = false,
    /** A download's next try after a failed one; 0 when none is waiting. */
    val retryAt: Long = 0L,
    /** Why a download waits for [retryAt]. */
    val stall: Stall? = null,
    /** While locked: the step the install is on, and when it began (wall clock: it spans restarts). */
    val step: InstallStep? = null,
    val stepAt: Long = 0L,
    /** How long each step usually takes on this C1. */
    val times: InstallTimes = InstallTimes.USUAL,
    /** With [UpdatePhase.Updated]: the version it replaced. */
    val from: Version? = null,
) {
    /** Nothing may interrupt this: the side button, the wheel, the edges and power are all refused. */
    val locked: Boolean get() = phase == UpdatePhase.Installing || phase == UpdatePhase.Finishing
}

/** What a source has, for the source page. */
sealed interface Probe {
    data class Found(val version: Version) : Probe
    data object None : Probe
    data object Offline : Probe
}

/**
 * Updates from the GitHub releases of a source (Settings > update): looked for daily or on
 * demand, downloaded as a step of their own (behind every page and through sleep, resumed
 * after a drop or a restart, retried, held for Wi-Fi unless the eSIM's data is allowed,
 * checked when done), then installed by this app as Device Owner, which needs no prompt and
 * no permission. Only the install locks the device. It kills this process; a marker in prefs
 * carries the locked page and its steps across that and across the restart after the compile
 * (an installed APK runs interpreted until it is compiled), so the user sees one
 * page from the tap to "updated".
 */
class Updater(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val device: StateFlow<DeviceStatus>,
) {
    private val ownSource = Source.parse(BuildConfig.UPDATE_REPO) ?: Source.parse("emilienbev/croissant-c1")!!
    private val installed = Version.parse(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** Device-protected, like the prefs: the app starts before the user's storage is unlocked. */
    private val dir: File get() = File(ctx.createDeviceProtectedStorageContext().filesDir, "update")
    private val onData = MutableStateFlow(false)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    /**
     * The networks a download may use, watched by the download itself while it runs: the
     * device status the pages read stops while the screen sleeps, and a download must not.
     */
    private val wifi = MutableStateFlow<Network?>(null)
    private val cell = MutableStateFlow<Network?>(null)
    private val pace = Pace()

    private val _ui = MutableStateFlow(UpdateUi(installed, ownSource, own = true, daily = true, onData = false))
    val ui: StateFlow<UpdateUi> = _ui

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** On every start, before the first frame: the source, the settings, and where an update was left. */
    fun start() {
        adoptInstallerSource()
        val source = Prefs.updateSource(ctx)?.let(Source::parse) ?: ownSource
        onData.value = Prefs.updateOnData(ctx)
        _ui.update {
            it.copy(source = source, own = source.same(ownSource), daily = Prefs.updateDaily(ctx), onData = onData.value,
                checkedAt = Prefs.updateCheckedAt(ctx))
        }
        scope.launch { InstallEvents.failed.collect { why -> if (_ui.value.phase == UpdatePhase.Installing) notInstalled(why) } }

        Prefs.updateInstalling(ctx)?.let(Marker::fromJson)?.let { resume(it); return }
        Prefs.updateUpdated(ctx)?.let(Updated::fromJson)?.let { u ->
            if (u.release.version == installed) {
                _ui.update { it.copy(phase = UpdatePhase.Updated, release = u.release, from = u.from) }
                return
            }
            Prefs.setUpdateUpdated(ctx, null)
        }
        val r = Prefs.updateRelease(ctx)?.let(Release::fromJson)?.takeIf { it.version > installed }
        if (r == null) {
            Prefs.setUpdateRelease(ctx, null)
            dir.deleteRecursively()
            return
        }
        when {
            apkOf(r).exists() -> _ui.update { it.copy(phase = UpdatePhase.Ready, release = r, done = r.apk.size, times = timesFor(r)) }
            // A download the user started carries on where it stopped.
            partOf(r).exists() -> download(r)
            else -> _ui.update { it.copy(phase = UpdatePhase.Out, release = r, times = timesFor(r)) }
        }
    }

    /** The daily look, when it is due: on a wake, a start, and Wi-Fi coming up. */
    fun maybeCheck() {
        val s = _ui.value
        if (!s.daily || s.locked || s.phase == UpdatePhase.Downloading) return
        if (System.currentTimeMillis() - s.checkedAt < DAY_MS) return
        check()
    }

    fun check() {
        val s = _ui.value
        if (s.locked || checkJob?.isActive == true) return
        checkJob = scope.launch {
            val shown = _ui.value.phase
            if (shown == UpdatePhase.Idle || shown == UpdatePhase.Out || shown == UpdatePhase.Updated) {
                _ui.update { it.copy(phase = UpdatePhase.Checking, problem = null) }
            }
            when (val l = latest(_ui.value.source)) {
                is Latest.Found -> {
                    val now = System.currentTimeMillis()
                    Prefs.setUpdateCheckedAt(ctx, now)
                    _ui.update { it.copy(checkedAt = now, answered = true) }
                    offer(l.release)
                }
                Latest.None -> settle(UpdateProblem.NoReleases, stamped = true)
                Latest.Limited -> settle(UpdateProblem.Limited)
                Latest.Offline -> settle(UpdateProblem.Offline)
            }
        }
    }

    /** Whether [source] has a release this app could take, for the source page. */
    suspend fun probe(source: Source): Probe = when (val l = latest(source)) {
        is Latest.Found -> l.release?.let { Probe.Found(it.version) } ?: Probe.None
        Latest.None -> Probe.None
        Latest.Limited, Latest.Offline -> Probe.Offline
    }

    fun download() {
        val s = _ui.value
        val r = s.release ?: return
        if (s.phase == UpdatePhase.Out) download(r)
    }

    /** The download stops and what it had is dropped. */
    fun cancel() {
        val r = _ui.value.release ?: return
        if (_ui.value.phase != UpdatePhase.Downloading) return
        downloadJob?.cancel()
        partOf(r).delete()
        _ui.update { it.copy(phase = UpdatePhase.Out, done = 0L, leftMs = null, verifying = false, waitingForNetwork = false, retryAt = 0L, stall = null) }
    }

    /**
     * Install the downloaded release, if the battery allows it ([installAllowed]); false when
     * it does not or nothing is ready. Everything is checked again first, since the file has
     * sat on disk: its sha256, its signer and version, and the room to install it.
     */
    fun install(): Boolean {
        val s = _ui.value
        val r = s.release ?: return false
        if (s.phase != UpdatePhase.Ready) return false
        val d = device.value
        if (!installAllowed(d.batteryPct, d.charging)) return false
        _ui.update {
            it.copy(phase = UpdatePhase.Installing, problem = null, failure = null, step = InstallStep.Checking,
                stepAt = System.currentTimeMillis(), times = timesFor(r))
        }
        scope.launch {
            val file = apkOf(r)
            val need = file.length() * 3
            val problem = withContext(Dispatchers.IO) {
                when {
                    !file.exists() || r.apk.sha256 == null || sha256(file) != r.apk.sha256 -> UpdateProblem.Damaged
                    !fits(file) -> UpdateProblem.OtherKey
                    dir.usableSpace < need -> UpdateProblem.NoSpace
                    else -> null
                }
            }
            if (problem != null) {
                if (problem != UpdateProblem.NoSpace) file.delete()
                _ui.update {
                    it.copy(phase = if (file.exists()) UpdatePhase.Ready else UpdatePhase.Out, problem = problem,
                        roomNeeded = if (problem == UpdateProblem.NoSpace) need else 0L, done = 0L, step = null)
                }
                return@launch
            }
            val at = System.currentTimeMillis()
            Prefs.setUpdateInstalling(ctx, Marker(r, at, from = installed).toJson())
            _ui.update { it.copy(step = InstallStep.Installing, stepAt = at) }
            // The page says the screen goes dark, and stays until the system takes the app away.
            delay(READ_MS)
            val committed = withContext(Dispatchers.IO) {
                runCatching { commit(file) }.onFailure { Log.w(TAG, "commit failed", it) }.isSuccess
            }
            if (!committed) { notInstalled(InstallFailure.Refused); return@launch }
            // The install kills this process; if it is still here this long after, it did not happen.
            delay(STUCK_MS)
            if (_ui.value.phase == UpdatePhase.Installing) notInstalled(InstallFailure.TooLong)
        }
        return true
    }

    fun setSource(input: String): Boolean {
        val source = Source.parse(input) ?: return false
        if (_ui.value.locked) return false
        Prefs.setUpdateSource(ctx, if (source.same(ownSource)) null else source.slug)
        downloadJob?.cancel()
        dir.deleteRecursively()
        Prefs.setUpdateRelease(ctx, null)
        Prefs.setUpdateCheckedAt(ctx, 0L)
        _ui.update {
            it.copy(source = source, own = source.same(ownSource), phase = UpdatePhase.Idle, release = null, checkedAt = 0L,
                answered = false, problem = null, done = 0L, leftMs = null, verifying = false, waitingForNetwork = false,
                retryAt = 0L, stall = null)
        }
        // A new source is looked at straight away, for the update page under the source page.
        checkJob?.cancel()
        check()
        return true
    }

    fun resetSource() = setSource(ownSource.slug)

    fun setDaily(on: Boolean) {
        Prefs.setUpdateDaily(ctx, on)
        _ui.update { it.copy(daily = on) }
        if (on) maybeCheck()
    }

    fun setOnData(on: Boolean) {
        Prefs.setUpdateOnData(ctx, on)
        onData.value = on
        _ui.update { it.copy(onData = on) }
    }

    /** "updated" put away. */
    fun dismissUpdated() {
        if (_ui.value.phase != UpdatePhase.Updated) return
        Prefs.setUpdateUpdated(ctx, null)
        _ui.update { it.copy(phase = UpdatePhase.Idle, release = null, from = null) }
        check()
    }

    // ---- checking ----------------------------------------------------------

    private sealed interface Latest {
        /** A release, or null when the latest one is not one this app can take (no `croissant-X.Y.Z.apk`). */
        data class Found(val release: Release?) : Latest
        data object None : Latest
        data object Limited : Latest
        data object Offline : Latest
    }

    private suspend fun latest(source: Source): Latest = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${source.slug}/releases/latest")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> Latest.Found(parseRelease(resp.body.string()))
                    404 -> Latest.None
                    403, 429 -> Latest.Limited
                    else -> Latest.Offline
                }
            }
        } catch (e: IOException) {
            Latest.Offline
        }
    }

    /** What the latest release means for what is on the page. */
    private fun offer(latest: Release?) {
        val s = _ui.value
        if (latest == null || latest.version <= installed) {
            downloadJob?.cancel()
            dir.deleteRecursively()
            Prefs.setUpdateRelease(ctx, null)
            _ui.update { it.copy(phase = UpdatePhase.Idle, release = null, problem = null, done = 0L) }
            return
        }
        // The one already downloading or downloaded stays.
        val same = s.release?.version == latest.version
        if (same && (s.phase == UpdatePhase.Downloading || s.phase == UpdatePhase.Ready)) return
        downloadJob?.cancel()
        dir.deleteRecursively()
        Prefs.setUpdateRelease(ctx, latest.toJson())
        _ui.update { it.copy(phase = UpdatePhase.Out, release = latest, problem = null, done = 0L, times = timesFor(latest)) }
    }

    /** A check that found nothing to offer: what was on the page stays, with why. */
    private fun settle(problem: UpdateProblem, stamped: Boolean = false) {
        val now = System.currentTimeMillis()
        if (stamped) Prefs.setUpdateCheckedAt(ctx, now)
        _ui.update {
            it.copy(
                phase = if (it.phase == UpdatePhase.Checking) (if (it.release != null) UpdatePhase.Out else UpdatePhase.Idle) else it.phase,
                problem = problem,
                checkedAt = if (stamped) now else it.checkedAt,
                answered = true,
            )
        }
    }

    // ---- downloading ---------------------------------------------------------

    private fun download(r: Release) {
        if (downloadJob?.isActive == true) return
        _ui.update {
            it.copy(phase = UpdatePhase.Downloading, release = r, problem = null, done = partOf(r).length(), leftMs = null,
                verifying = false, retryAt = 0L, stall = null, times = timesFor(r))
        }
        downloadJob = scope.launch {
            val watch = watchNetworks()
            try {
                var failures = 0
                var refetched = false
                while (true) {
                    val network = awaitNetwork()
                    _ui.update { it.copy(waitingForNetwork = false, retryAt = 0L, stall = null) }
                    val before = partOf(r).length()
                    val verdict = try {
                        val via = clientOn(network)
                        fetch(r, via)
                        _ui.update { it.copy(verifying = true, leftMs = null) }
                        verify(r, via)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.w(TAG, "download: $e")
                        _ui.update { it.copy(verifying = false, leftMs = null) }
                        // The network went: waiting for it says more than a retry would.
                        if (pick() == null) continue
                        // A try that got somewhere starts the waits again from the shortest.
                        if (partOf(r).length() > before) failures = 0
                        failures++
                        val wait = retryAfterMs(failures)
                        _ui.update { it.copy(retryAt = System.currentTimeMillis() + wait, stall = if (e is HttpStatus) Stall.Server else Stall.Dropped) }
                        delay(wait)
                        continue
                    }
                    _ui.update { it.copy(verifying = false) }
                    when (verdict) {
                        is Verdict.Good -> {
                            val checked = r.copy(apk = r.apk.copy(sha256 = verdict.sha256))
                            partOf(r).renameTo(apkOf(r))
                            Prefs.setUpdateRelease(ctx, checked.toJson())
                            _ui.update { it.copy(phase = UpdatePhase.Ready, release = checked, done = r.apk.size) }
                            return@launch
                        }
                        // A file that is not what was published: once more from the start, then say so.
                        Verdict.Damaged -> {
                            partOf(r).delete()
                            if (!refetched) { refetched = true; continue }
                            _ui.update { it.copy(phase = UpdatePhase.Out, problem = UpdateProblem.Damaged, done = 0L) }
                            return@launch
                        }
                        Verdict.OtherKey -> {
                            partOf(r).delete()
                            _ui.update { it.copy(phase = UpdatePhase.Out, problem = UpdateProblem.OtherKey, done = 0L) }
                            return@launch
                        }
                    }
                }
            } finally {
                watch.close()
            }
        }
    }

    /** Wi-Fi, or the eSIM's data when it is allowed. */
    private fun pick(): Network? = wifi.value ?: cell.value?.takeIf { onData.value }

    /** Back with a network a download may use, as soon as there is one. */
    private suspend fun awaitNetwork(): Network {
        pick()?.let { return it }
        val usable = combine(wifi, cell, onData) { w, c, o -> w ?: c.takeIf { o } }.filterNotNull()
        // The watch reports what is up a moment after it starts: only after that is it waiting.
        withTimeoutOrNull(NETWORK_GRACE_MS) { usable.first() }?.let { return it }
        _ui.update { it.copy(waitingForNetwork = true, leftMs = null) }
        return usable.first()
    }

    /** Wi-Fi and cellular networks with internet into [wifi] and [cell], until closed. */
    private fun watchNetworks(): AutoCloseable {
        fun watch(transport: Int, into: MutableStateFlow<Network?>): ConnectivityManager.NetworkCallback {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { into.value = network }
                override fun onLost(network: Network) { if (into.value == network) into.value = null }
            }
            val request = NetworkRequest.Builder().addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(request, callback)
            return callback
        }
        val callbacks = listOf(watch(NetworkCapabilities.TRANSPORT_WIFI, wifi), watch(NetworkCapabilities.TRANSPORT_CELLULAR, cell))
        return AutoCloseable {
            callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
            wifi.value = null
            cell.value = null
        }
    }

    /**
     * The client for a download over [network]. Android's own route as a rule (a VPN over Wi-Fi
     * included); bound to [network], its lookups too, only when that route is the eSIM's data
     * and download on esim is off, as when Wi-Fi is up without internet.
     */
    private fun clientOn(network: Network): OkHttpClient {
        val route = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        val viaData = route != null && route.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !route.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!viaData || onData.value) return client
        return client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .build()
    }

    /** An HTTP answer that is not the file. */
    private class HttpStatus(code: Int) : IOException("http $code")

    /** The APK into its `.part` file, from where the last attempt stopped when the server allows it. */
    private suspend fun fetch(r: Release, via: OkHttpClient) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = partOf(r)
        var have = if (part.exists()) part.length() else 0L
        if (have > r.apk.size) { part.delete(); have = 0L }
        if (have == r.apk.size) return@withContext
        val req = Request.Builder().url(r.apk.url).header("User-Agent", USER_AGENT)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = via.newCall(req)
        val stop = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            held {
                call.execute().use { resp ->
                    val append = when (resp.code) {
                        206 -> true
                        200 -> { have = 0L; false }
                        416 -> return@use
                        else -> throw HttpStatus(resp.code)
                    }
                    pace.reset()
                    pace.add(SystemClock.uptimeMillis(), have)
                    FileOutputStream(part, append).use { out ->
                        val input = resp.body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var shown = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val now = SystemClock.uptimeMillis()
                            if (now - shown > PROGRESS_MS) {
                                shown = now
                                pace.add(now, have)
                                val left = pace.leftMs(r.apk.size)
                                _ui.update { it.copy(done = have, leftMs = left) }
                            }
                        }
                        out.fd.sync()
                    }
                }
            }
        } finally {
            stop?.dispose()
        }
        _ui.update { it.copy(done = have) }
    }

    private sealed interface Verdict {
        data class Good(val sha256: String) : Verdict
        data object Damaged : Verdict
        data object OtherKey : Verdict
    }

    /** The whole file, its sha256 against what GitHub published, then whose it is. */
    private suspend fun verify(r: Release, via: OkHttpClient): Verdict = withContext(Dispatchers.IO) {
        val part = partOf(r)
        if (part.length() != r.apk.size) return@withContext Verdict.Damaged
        val want = r.apk.sha256 ?: r.shaUrl?.let { shaOf(text(it, via)) } ?: return@withContext Verdict.Damaged
        val got = sha256(part)
        when {
            got != want -> Verdict.Damaged
            !fits(part) -> Verdict.OtherKey
            else -> Verdict.Good(got)
        }
    }

    private fun text(url: String, via: OkHttpClient): String =
        via.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatus(resp.code)
            resp.body.string()
        }

    /**
     * This app, signed with this app's key, at a newer version. Checked before installing
     * because Android kills the app before it checks the signer itself.
     */
    private fun fits(apk: File): Boolean {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != ctx.packageName || archive.longVersionCode <= BuildConfig.VERSION_CODE) return false
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            ?: return false
        val theirs = archive.signingInfo?.apkContentsSigners ?: return false
        return theirs.isNotEmpty() && theirs.all { t -> mine.any { it == t } }
    }

    /** A wake lock and a Wi-Fi lock while the download runs, so a sleeping screen does not stall it. */
    private inline fun <T> held(block: () -> T): T {
        val wake = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "croissant:update")
        @Suppress("DEPRECATION")
        val radio = ctx.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "croissant:update")
        wake.acquire(HOLD_MS)
        radio.acquire()
        try {
            return block()
        } finally {
            if (radio.isHeld) radio.release()
            if (wake.isHeld) wake.release()
        }
    }

    private fun partOf(r: Release) = File(dir, r.apk.name + ".part")
    private fun apkOf(r: Release) = File(dir, r.apk.name)

    // ---- installing ----------------------------------------------------------

    /** As Device Owner: no prompt and no permission (AOSP 13 `canSilentlyInstallPackage`). */
    private fun commit(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            // Mutable: the installer fills the status in. Explicit: the receiver is this app's.
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallStatus::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(status.intentSender)
        }
    }

    /**
     * How long each step should take, from this C1's last install and compile when it has
     * timed one, and the file's size for the check.
     */
    private fun timesFor(r: Release) = InstallTimes(
        checkingMs = maxOf(1_000L, r.apk.size / CHECK_BYTES_PER_MS),
        installingMs = Prefs.updateInstallMs(ctx).takeIf { it > 0 } ?: InstallTimes.USUAL.installingMs,
        compilingMs = Prefs.updateCompileMs(ctx).takeIf { it > 0 } ?: InstallTimes.USUAL.compilingMs,
        restartingMs = InstallTimes.USUAL.restartingMs,
    )

    /** Where a start finds an install it was in the middle of. */
    private fun resume(m: Marker) {
        when {
            BuildConfig.VERSION_CODE >= m.release.version.code -> finish(m)
            m.failure != null -> notInstalled(m.failure)
            System.currentTimeMillis() - m.at > INSTALL_TIMEOUT_MS -> notInstalled(InstallFailure.TooLong)
            else -> {
                _ui.update {
                    it.copy(phase = UpdatePhase.Installing, release = m.release, step = InstallStep.Installing, stepAt = m.at,
                        times = timesFor(m.release))
                }
                scope.launch {
                    delay((m.at + INSTALL_TIMEOUT_MS - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (_ui.value.phase == UpdatePhase.Installing) notInstalled(InstallFailure.TooLong)
                }
            }
        }
    }

    /** The new version's first start: compile as the installer does, then restart into the compiled code. */
    private fun finish(m: Marker) {
        val r = m.release
        val start = System.currentTimeMillis()
        // The install itself, from the commit to here, for the next one's time left; not after a second start.
        (start - m.at).takeIf { it in 1_000L until INSTALL_MEASURE_MS }?.let { Prefs.setUpdateInstallMs(ctx, it) }
        _ui.update {
            it.copy(phase = UpdatePhase.Finishing, release = r, problem = null, failure = null, from = m.from,
                step = InstallStep.Compiling, stepAt = start, times = timesFor(r))
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                val t0 = SystemClock.elapsedRealtime()
                val out = Root.run("cmd package compile -m speed -f ${ctx.packageName}", timeoutS = 600)
                if (out == null) Log.w(TAG, "compile refused: runs interpreted until the nightly dexopt")
                else Prefs.setUpdateCompileMs(ctx, SystemClock.elapsedRealtime() - t0)
                dir.deleteRecursively()
            }
            Prefs.setUpdateRelease(ctx, null)
            Prefs.setUpdateUpdated(ctx, Updated(r, m.from).toJson())
            Prefs.setUpdateInstalling(ctx, null)
            _ui.update { it.copy(step = InstallStep.Restarting, stepAt = System.currentTimeMillis()) }
            delay(READ_MS)
            // HOME: killed, it comes straight back, on the compiled code.
            Process.killProcess(Process.myPid())
        }
    }

    private fun notInstalled(why: InstallFailure?) {
        Prefs.setUpdateInstalling(ctx, null)
        val r = _ui.value.release ?: Prefs.updateRelease(ctx)?.let(Release::fromJson)
        val ready = r != null && apkOf(r).exists()
        _ui.update {
            it.copy(phase = if (ready) UpdatePhase.Ready else if (r != null) UpdatePhase.Out else UpdatePhase.Idle,
                release = r, problem = UpdateProblem.NotInstalled, failure = why, step = null)
        }
    }

    /** The installer's answer to "where should this C1 get updates", left as a file beside the font. */
    private fun adoptInstallerSource() {
        val f = File(ctx.createDeviceProtectedStorageContext().filesDir, SOURCE_FILE)
        if (!f.exists()) return
        runCatching { Source.parse(f.readText()) }.getOrNull()?.let { s ->
            Prefs.setUpdateSource(ctx, if (s.same(ownSource)) null else s.slug)
        }
        f.delete()
    }

    companion object {
        private const val TAG = "Update"
        private val USER_AGENT = "croissant/${BuildConfig.VERSION_NAME}"
        /** The installer's file in the app's device-protected files (installer/croissant/stages.py). */
        const val SOURCE_FILE = "update-source"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val PROGRESS_MS = 250L
        private const val HOLD_MS = 30 * 60 * 1000L
        /** How long a download looks for its network before it says it is waiting. */
        private const val NETWORK_GRACE_MS = 1_000L
        /** A step's words stay this long before the screen goes dark under them. */
        private const val READ_MS = 1_000L
        /** The page's words fading in after a restart. */
        const val FADE_MS = 300L
        private const val STUCK_MS = 120_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
        /** An install that took longer than this to reach the new version's start was not timed on its own. */
        private const val INSTALL_MEASURE_MS = 60_000L
        /** The sha256 and signer check, bytes a millisecond on the first R1. */
        private const val CHECK_BYTES_PER_MS = 40_000L
    }
}

/** The install under way: the release, when it was committed, the version it replaces, and why it failed if the installer said. */
internal data class Marker(val release: Release, val at: Long, val from: Version?, val failure: InstallFailure? = null) {
    fun toJson(): String = JSONObject().put("release", release.toJson()).put("at", at)
        .put("from", from?.toString() ?: "").put("failure", failure?.name ?: "").toString()

    companion object {
        fun fromJson(s: String): Marker? = runCatching {
            val o = JSONObject(s)
            Marker(
                Release.fromJson(o.getString("release")) ?: return null,
                o.getLong("at"),
                Version.parse(o.optString("from")),
                o.optString("failure").takeIf { it.isNotEmpty() }?.let { f -> InstallFailure.entries.firstOrNull { it.name == f } },
            )
        }.getOrNull()

        /** From the receiver, in whichever process is alive: the next start, or this one, says it did not install, and why. */
        fun fail(ctx: Context, why: InstallFailure) {
            val m = Prefs.updateInstalling(ctx)?.let(::fromJson) ?: return
            Prefs.setUpdateInstalling(ctx, m.copy(failure = why).toJson())
        }
    }
}

/** The release just installed and the version it replaced, for the first start's "updated from". */
internal data class Updated(val release: Release, val from: Version?) {
    fun toJson(): String = JSONObject().put("release", release.toJson()).put("from", from?.toString() ?: "").toString()

    companion object {
        fun fromJson(s: String): Updated? = runCatching {
            val o = JSONObject(s)
            Updated(Release.fromJson(o.getString("release")) ?: return null, Version.parse(o.optString("from")))
        }.getOrNull()
    }
}

/** The installer's result. Success needs nothing: the new version's start takes over. */
class InstallStatus : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return
        Log.w("Update", "install: status $status, ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        val why = when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailure.NoRoom
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallFailure.Interrupted
            else -> InstallFailure.Refused
        }
        Marker.fail(c, why)
        InstallEvents.failed.tryEmit(why)
    }
}

/** Failures the installer reports to a process that is still alive. */
object InstallEvents {
    val failed = MutableSharedFlow<InstallFailure>(extraBufferCapacity = 1)
}
