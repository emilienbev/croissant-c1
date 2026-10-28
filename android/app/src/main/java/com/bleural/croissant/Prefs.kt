package com.bleural.croissant

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import com.bleural.croissant.data.LanguagePair

/** The one settings file. Server: name (for the user only), host and port. */
object Prefs {
    private const val FILE = "croissant"
    private const val KEY_URL = "server_url"        // pre-Server-screen builds; migrated once
    private const val KEY_NAME = "server_name"
    private const val KEY_HOST = "server_host"
    private const val KEY_PORT = "server_port"
    private const val KEY_MOVED = "moved_to_device_protected"
    private const val KEY_VOLUME = "volume"
    private const val KEY_MUTED = "muted"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_BATTERY_TRACK = "battery_track"
    private const val KEY_BATTERY_EVERY = "battery_every_ms"
    private const val KEY_REST = "rest_ms"
    private const val KEY_SLEEP = "sleep_ms"
    private const val KEY_DESIGN = "design"
    private const val KEY_VARIANT = "design_variant"
    private const val KEY_METRONOME = "metronome"
    private const val KEY_PRACTICE_DAY = "practice_day"
    private const val KEY_PRACTICE_MS = "practice_ms"
    private const val KEY_PRACTICE_LOG = "practice_log"
    private const val KEY_TUNER = "tuner"
    private const val KEY_TRANSLATOR = "translator"
    private const val KEY_WEATHER_PLACE = "weather_place"
    private const val KEY_WEATHER = "weather"
    private const val KEY_WEATHER_AT = "weather_at"
    private const val KEY_FORECASTS = "weather_forecasts"
    private const val KEY_PLACES = "weather_places"
    private const val KEY_PIN = "weather_pin"
    private const val KEY_HERE_NAME = "weather_here_name"
    private const val KEY_SETUP_FINISHED = "setup_finished"
    private const val KEY_SETUP_SKIPPED = "setup_skipped"
    private const val KEY_ACP_BRIDGES = "acp_bridges"
    private const val KEY_ACP_ACTIVE = "acp_bridge_active"
    private const val KEY_MODEL = "model_"   // + a slot's key: the model picked there
    private const val KEY_LIVE_WORDS = "live_words"
    private const val KEY_CHAT_DEFAULT = "chat_default"
    private const val KEY_UPDATE_SOURCE = "update_source"
    private const val KEY_UPDATE_DAILY = "update_daily"
    private const val KEY_UPDATE_ON_DATA = "update_on_data"
    private const val KEY_UPDATE_CHECKED = "update_checked_at"
    private const val KEY_UPDATE_RELEASE = "update_release"
    private const val KEY_UPDATE_INSTALLING = "update_installing"
    private const val KEY_UPDATE_UPDATED = "update_updated"
    private const val KEY_UPDATE_INSTALL_MS = "update_install_ms"
    private const val KEY_UPDATE_COMPILE_MS = "update_compile_ms"
    private const val KEY_WHEEL_RECENTS = "wheel_recents"
    private const val KEY_WHEEL_PINS = "wheel_pins"
    private const val KEY_WHEEL_AGENTS = "wheel_agents"
    private const val KEY_SOUND_PACK = "sound_pack"

    /**
     * The emulator reaches the Mac on the host-loopback alias. An R1 has no server until its
     * setup finds one; until 2026-09-29 every R1 defaulted to the
     * first Mac it was built against, which already has it saved, so nothing changes there.
     */
    const val HOST_EMULATOR = "10.0.2.2"
    const val DEFAULT_PORT = 8765

    data class Server(val name: String, val host: String, val port: Int) {
        val url get() = "ws://$host:$port/session"
        /** The same server over plain HTTP, where generated pictures are fetched. */
        val httpUrl get() = "http://$host:$port"
        /** What the Settings row shows: the name if there is one, else host:port; nothing while there is no server. */
        val label get() = if (host.isBlank()) "" else name.ifBlank { "$host:$port" }
        /** A server has been set: until then a hold has nowhere to go. */
        val set get() = host.isNotBlank()
    }

    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    fun defaultServer() = Server("", if (isEmulator) HOST_EMULATOR else "", DEFAULT_PORT)

    /**
     * Device-protected storage. The app is direct-boot aware: it is the HOME and comes up
     * under the boot animation, before Android unlocks user storage (which only happens
     * once the animation ends). Credential-encrypted prefs would throw until then. Builds
     * before 2026-09-14 wrote this file to CE storage; it is moved once, the first time
     * the app reads prefs while unlocked.
     */
    private fun sp(ctx: Context): SharedPreferences {
        val dp = ctx.createDeviceProtectedStorageContext()
        val p = dp.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_MOVED, false)) return p
        if (!ctx.getSystemService(UserManager::class.java).isUserUnlocked) return p
        dp.moveSharedPreferencesFrom(ctx, FILE)
        return dp.getSharedPreferences(FILE, Context.MODE_PRIVATE).also {
            it.edit().putBoolean(KEY_MOVED, true).apply()
        }
    }

    fun server(ctx: Context): Server {
        val p = sp(ctx)
        val d = defaultServer()
        if (!p.contains(KEY_HOST) && p.contains(KEY_URL)) {
            // One-time migration from the old single URL.
            val old = p.getString(KEY_URL, "") ?: ""
            val hostPort = old.substringAfter("//").substringBefore("/")
            val host = hostPort.substringBefore(":").ifBlank { d.host }
            val port = hostPort.substringAfter(":", "").toIntOrNull() ?: d.port
            p.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).remove(KEY_URL).apply()
        }
        return Server(
            name = p.getString(KEY_NAME, "") ?: "",
            host = p.getString(KEY_HOST, d.host) ?: d.host,
            port = p.getInt(KEY_PORT, d.port),
        )
    }

    fun setServer(ctx: Context, s: Server) {
        sp(ctx).edit().putString(KEY_NAME, s.name.trim()).putString(KEY_HOST, s.host.trim()).putInt(KEY_PORT, s.port).apply()
    }

    fun serverUrl(ctx: Context): String = server(ctx).url

    /** Settings > Device, 0..100. Brightness shows the device's own level until one is saved. */
    fun volume(ctx: Context): Int = sp(ctx).getInt(KEY_VOLUME, DeviceLevels.DEFAULT_VOLUME)
    fun setVolume(ctx: Context, pct: Int) { sp(ctx).edit().putInt(KEY_VOLUME, pct.coerceIn(0, 100)).apply() }
    /** The control centre's mute: every sound of the app off except a ringing timer. */
    fun muted(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_MUTED, false)
    fun setMuted(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean(KEY_MUTED, on).apply() }
    fun brightness(ctx: Context): Int = sp(ctx).getInt(KEY_BRIGHTNESS, -1).takeIf { it >= 0 } ?: DeviceLevels.currentBrightness(ctx)
    fun setBrightness(ctx: Context, pct: Int) { sp(ctx).edit().putInt(KEY_BRIGHTNESS, pct.coerceIn(0, 100)).apply() }


    /** Settings > Device > Battery: whether the log runs (on by default) and how often a sample is taken. */
    fun batteryTrack(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_BATTERY_TRACK, true)
    fun setBatteryTrack(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean(KEY_BATTERY_TRACK, on).apply() }
    fun look(ctx: Context): Look {
        val p = sp(ctx)
        return Look(p.getString(KEY_DESIGN, "") ?: "", p.getString(KEY_VARIANT, "") ?: "")
    }
    fun setLook(ctx: Context, l: Look) { sp(ctx).edit().putString(KEY_DESIGN, l.design).putString(KEY_VARIANT, l.variant).apply() }

    /** The interface's sound pack (audio/Cues.kt): null until one is picked. */
    fun soundPack(ctx: Context): String? = sp(ctx).getString(KEY_SOUND_PACK, null)?.takeIf { it.isNotEmpty() }
    fun setSoundPack(ctx: Context, pack: String) { sp(ctx).edit().putString(KEY_SOUND_PACK, pack).apply() }

    fun batteryEvery(ctx: Context): Int = sp(ctx).getInt(KEY_BATTERY_EVERY, BatteryTracker.DEFAULT_EVERY_MS)
    fun setBatteryEvery(ctx: Context, ms: Int) { sp(ctx).edit().putInt(KEY_BATTERY_EVERY, ms).apply() }

    /** Settings > Device > Battery: Rest and Sleep, in ms or [Idle.NEVER] (IdleWatch). */
    fun rest(ctx: Context): Int = sp(ctx).getInt(KEY_REST, Idle.REST_DEFAULT)
    fun setRest(ctx: Context, ms: Int) { sp(ctx).edit().putInt(KEY_REST, ms).apply() }
    fun sleep(ctx: Context): Int = sp(ctx).getInt(KEY_SLEEP, Idle.SLEEP_DEFAULT)
    fun setSleep(ctx: Context, ms: Int) { sp(ctx).edit().putInt(KEY_SLEEP, ms).apply() }

    /** The metronome's settings as the metronome writes them ("96,4,4,Quarter,Click"); it reads them back itself. */
    fun metronome(ctx: Context): String? = sp(ctx).getString(KEY_METRONOME, null)
    fun setMetronome(ctx: Context, s: String) { sp(ctx).edit().putString(KEY_METRONOME, s).apply() }

    /**
     * Practice: every day played and its milliseconds, as the metronome writes them
     * ("2026-09-21 1500000;2026-09-22 900000"). The first read folds in the single day the
     * app kept before the log (practice_day and practice_ms) and drops those keys.
     */
    fun practiceLog(ctx: Context): String {
        val p = sp(ctx)
        p.getString(KEY_PRACTICE_LOG, null)?.let { return it }
        val day = p.getString(KEY_PRACTICE_DAY, null)
        val log = if (day.isNullOrEmpty()) "" else "$day ${p.getLong(KEY_PRACTICE_MS, 0L)}"
        p.edit().putString(KEY_PRACTICE_LOG, log).remove(KEY_PRACTICE_DAY).remove(KEY_PRACTICE_MS).apply()
        return log
    }
    fun setPracticeLog(ctx: Context, s: String) { sp(ctx).edit().putString(KEY_PRACTICE_LOG, s).apply() }

    /** The tuner's instrument and each instrument's tuning, as the tuner writes them. */
    fun tuner(ctx: Context): String? = sp(ctx).getString(KEY_TUNER, null)
    fun setTuner(ctx: Context, s: String) { sp(ctx).edit().putString(KEY_TUNER, s).apply() }

    /**
     * A paired acp-bridge (acp-bridge/), from its pairing QR: the computer's addresses (home
     * network, tailnet), its port and its token, which is what tells two bridges apart; [name]
     * is the computer's own, once it has answered.
     */
    data class AcpBridge(val hosts: List<String> = emptyList(), val port: Int = ACP_BRIDGE_PORT, val token: String = "", val name: String = "") {
        val set get() = hosts.isNotEmpty() && token.isNotEmpty()
    }
    const val ACP_BRIDGE_PORT = 8775

    /** Every bridge paired, in the order paired. */
    fun acpBridges(ctx: Context): List<AcpBridge> {
        val a = runCatching { JSONArray(sp(ctx).getString(KEY_ACP_BRIDGES, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val hosts = o.optJSONArray("hosts")?.let { h -> (0 until h.length()).map { h.getString(it) } }.orEmpty()
            AcpBridge(hosts, o.optInt("port", ACP_BRIDGE_PORT), o.optString("token"), o.optString("name")).takeIf { it.set }
        }
    }
    fun setAcpBridges(ctx: Context, bridges: List<AcpBridge>) {
        val a = JSONArray()
        bridges.forEach { b -> a.put(JSONObject().put("hosts", JSONArray(b.hosts)).put("port", b.port).put("token", b.token).put("name", b.name)) }
        sp(ctx).edit().putString(KEY_ACP_BRIDGES, a.toString()).apply()
    }

    /** The bridge in use, by its token: one at a time, never two at once. */
    fun acpActive(ctx: Context): String? = sp(ctx).getString(KEY_ACP_ACTIVE, null)?.takeIf { it.isNotEmpty() }
    fun setAcpActive(ctx: Context, token: String?) {
        sp(ctx).edit().apply { if (token == null) remove(KEY_ACP_ACTIVE) else putString(KEY_ACP_ACTIVE, token) }.apply()
    }

    /**
     * The model picked per slot on the server's list (net/Models.kt), by the slot's wire key
     * ("chat", "stt"...): an entry's id. A slot not here takes the server's default.
     */
    fun modelPicks(ctx: Context): Map<String, String> = sp(ctx).all.mapNotNull { (k, v) ->
        if (k.startsWith(KEY_MODEL) && v is String && v.isNotEmpty()) k.removePrefix(KEY_MODEL) to v else null
    }.toMap()
    fun setModelPick(ctx: Context, slot: String, id: String?) {
        sp(ctx).edit().apply { if (id.isNullOrEmpty()) remove(KEY_MODEL + slot) else putString(KEY_MODEL + slot, id) }.apply()
    }

    /**
     * The server's chat default as it last said it (`GET /providers`), an entry's id: what a
     * hold that picks no chat model gets, before the server has been asked since the start.
     */
    fun chatDefault(ctx: Context): String? = sp(ctx).getString(KEY_CHAT_DEFAULT, null)?.takeIf { it.isNotEmpty() }
    fun setChatDefault(ctx: Context, id: String?) {
        sp(ctx).edit().apply { if (id.isNullOrEmpty()) remove(KEY_CHAT_DEFAULT) else putString(KEY_CHAT_DEFAULT, id) }.apply()
    }

    /** Settings > update > source: `owner/repo`, or null for the build's own (`BuildConfig.UPDATE_REPO`). */
    fun updateSource(ctx: Context): String? = sp(ctx).getString(KEY_UPDATE_SOURCE, null)?.takeIf { it.isNotEmpty() }
    fun setUpdateSource(ctx: Context, slug: String?) {
        sp(ctx).edit().apply { if (slug.isNullOrEmpty()) remove(KEY_UPDATE_SOURCE) else putString(KEY_UPDATE_SOURCE, slug) }.apply()
    }

    /** Settings > update > check daily: on unless turned off. */
    fun updateDaily(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_UPDATE_DAILY, true)
    fun setUpdateDaily(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_UPDATE_DAILY, on).apply()

    /** Settings > update > download on esim: off, so an update comes over Wi-Fi unless asked. */
    fun updateOnData(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_UPDATE_ON_DATA, false)
    fun setUpdateOnData(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_UPDATE_ON_DATA, on).apply()

    fun updateCheckedAt(ctx: Context): Long = sp(ctx).getLong(KEY_UPDATE_CHECKED, 0L)
    fun setUpdateCheckedAt(ctx: Context, at: Long) = sp(ctx).edit().putLong(KEY_UPDATE_CHECKED, at).apply()

    /** The newer release last found, as `Release.toJson`, so a download or a downloaded one outlives a restart. */
    fun updateRelease(ctx: Context): String? = sp(ctx).getString(KEY_UPDATE_RELEASE, null)
    fun setUpdateRelease(ctx: Context, json: String?) {
        sp(ctx).edit().apply { if (json == null) remove(KEY_UPDATE_RELEASE) else putString(KEY_UPDATE_RELEASE, json) }.apply()
    }

    /**
     * The install under way (update/Updater.kt `Marker`): written before the commit and
     * written synchronously, since the process is killed moments later.
     */
    fun updateInstalling(ctx: Context): String? = sp(ctx).getString(KEY_UPDATE_INSTALLING, null)
    fun setUpdateInstalling(ctx: Context, json: String?) {
        sp(ctx).edit().apply { if (json == null) remove(KEY_UPDATE_INSTALLING) else putString(KEY_UPDATE_INSTALLING, json) }.commit()
    }

    /** The release just installed and the version it replaced, for the page that says so after the last restart; cleared when it is put away. */
    fun updateUpdated(ctx: Context): String? = sp(ctx).getString(KEY_UPDATE_UPDATED, null)
    fun setUpdateUpdated(ctx: Context, json: String?) {
        sp(ctx).edit().apply { if (json == null) remove(KEY_UPDATE_UPDATED) else putString(KEY_UPDATE_UPDATED, json) }.commit()
    }

    /**
     * How long this C1's last install took, from the commit to the new version's start, and
     * its last compile after it; 0 before the first. The locked page times its steps by them.
     */
    fun updateInstallMs(ctx: Context): Long = sp(ctx).getLong(KEY_UPDATE_INSTALL_MS, 0L)
    fun setUpdateInstallMs(ctx: Context, ms: Long) = sp(ctx).edit().putLong(KEY_UPDATE_INSTALL_MS, ms).apply()
    fun updateCompileMs(ctx: Context): Long = sp(ctx).getLong(KEY_UPDATE_COMPILE_MS, 0L)
    fun setUpdateCompileMs(ctx: Context, ms: Long) = sp(ctx).edit().putLong(KEY_UPDATE_COMPILE_MS, ms).commit()

    /** The words while the button is held, turned on or off by hand; null leaves it to the speech model. */
    fun liveWords(ctx: Context): Boolean? = sp(ctx).let { if (it.contains(KEY_LIVE_WORDS)) it.getBoolean(KEY_LIVE_WORDS, true) else null }
    fun setLiveWords(ctx: Context, on: Boolean?) {
        sp(ctx).edit().apply { if (on == null) remove(KEY_LIVE_WORDS) else putBoolean(KEY_LIVE_WORDS, on) }.apply()
    }

    /** The Translator's pair ("en,fr"), English to French until one is picked. */
    fun translatorPair(ctx: Context): LanguagePair = LanguagePair.parse(sp(ctx).getString(KEY_TRANSLATOR, null))
    fun setTranslatorPair(ctx: Context, p: LanguagePair) { sp(ctx).edit().putString(KEY_TRANSLATOR, p.toString()).apply() }

    /** Where the weather is for, as the weather source writes it ("51.5072,-0.1276,1759140000000": the fix's time last). */
    fun weatherPlace(ctx: Context): String? = sp(ctx).getString(KEY_WEATHER_PLACE, null)
    fun setWeatherPlace(ctx: Context, s: String) { sp(ctx).edit().putString(KEY_WEATHER_PLACE, s).apply() }

    /**
     * The last forecast of every place as Open-Meteo sent it, and when it came, by place id
     * (`{"here": {"at": ms, "f": {...}}}`), so Home and Weather have it straight after a restart.
     * Replaces here's lone forecast (`weather`, `weather_at`), which is dropped.
     */
    fun forecasts(ctx: Context): String? = sp(ctx).getString(KEY_FORECASTS, null)
    fun setForecasts(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_FORECASTS, json).remove(KEY_WEATHER).remove(KEY_WEATHER_AT).apply()
    }

    /** The places added to Weather (a JSON array, in the order added), and the one pinned to Home, if any. */
    fun weatherPlaces(ctx: Context): String? = sp(ctx).getString(KEY_PLACES, null)
    fun weatherPin(ctx: Context): String? = sp(ctx).getString(KEY_PIN, null)
    fun setWeatherPlaces(ctx: Context, json: String, pin: String?) {
        sp(ctx).edit().putString(KEY_PLACES, json).apply { if (pin == null) remove(KEY_PIN) else putString(KEY_PIN, pin) }.apply()
    }

    /** Here's locality, when the device could name it. */
    fun hereName(ctx: Context): String? = sp(ctx).getString(KEY_HERE_NAME, null)
    fun setHereName(ctx: Context, name: String) { sp(ctx).edit().putString(KEY_HERE_NAME, name).apply() }

    /** The side wheel's recents, newest first, and its pinned apps, in pin order (WheelKeep, 2026-10-17). */
    fun wheelRecents(ctx: Context): List<String> = WheelKeep.decode(sp(ctx).getString(KEY_WHEEL_RECENTS, null))
    fun setWheelRecents(ctx: Context, keys: List<String>) { sp(ctx).edit().putString(KEY_WHEEL_RECENTS, WheelKeep.encode(keys)).apply() }
    fun wheelPins(ctx: Context): List<String> = WheelKeep.decode(sp(ctx).getString(KEY_WHEEL_PINS, null))
    fun setWheelPins(ctx: Context, ids: List<String>) { sp(ctx).edit().putString(KEY_WHEEL_PINS, WheelKeep.encode(ids)).apply() }
    /** The agents pinned to the side wheel, by the bridge's id for each, in the order pinned. */
    fun wheelAgents(ctx: Context): List<String> = WheelKeep.decode(sp(ctx).getString(KEY_WHEEL_AGENTS, null))
    fun setWheelAgents(ctx: Context, ids: List<String>) { sp(ctx).edit().putString(KEY_WHEEL_AGENTS, WheelKeep.encode(ids)).apply() }

    /**
     * The first setup: finished once, and the steps skipped (comma separated names). A device
     * that saved a server before the setup existed was set up by hand, so it counts as finished.
     */
    fun setupFinished(ctx: Context): Boolean {
        val p = sp(ctx)
        return if (p.contains(KEY_SETUP_FINISHED)) p.getBoolean(KEY_SETUP_FINISHED, false) else p.contains(KEY_HOST)
    }
    fun setupSkipped(ctx: Context): String = sp(ctx).getString(KEY_SETUP_SKIPPED, "") ?: ""
    fun setSetup(ctx: Context, finished: Boolean, skipped: String) {
        sp(ctx).edit().putBoolean(KEY_SETUP_FINISHED, finished).putString(KEY_SETUP_SKIPPED, skipped).apply()
    }
}
