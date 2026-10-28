package com.bleural.croissant

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/**
 * Settings > Device: sound and brightness as percentages, kept in prefs and applied
 * here, at every app start and on every change. Sound is a gain on the app's own
 * players (the ringtone, and the interface cues in audio/Cues.kt, which since
 * 2026-09-16 replace the synthesised click): the alarm and music streams are pinned at their
 * maximum, because the alarm stream's lowest step (1 of 7 on the R1) is still loud,
 * and [gain] maps the percentage on a decibel curve instead. The ring stream (system
 * and notification alias to it on the R1) carries nothing of ours any more, so it
 * follows the percentage on its own 7 steps as a backstop for stray system sounds
 * (the keyboard's key clicks, for one). Muted (the control centre's sound tile) is
 * the cues' gain at 0 and the ring stream at 0; the timer's ringtone is not touched,
 * it reads the level itself and always plays. Brightness is the
 * system setting, in manual mode, through the Device Owner API (plain writes need
 * WRITE_SETTINGS, which the emulator build does not have, so there it just logs).
 * Rest is the system idle timeout, one value on battery and plugged in ([Idle]);
 * Android dims the panel a few seconds before it goes dark.
 */
object DeviceLevels {
    private const val TAG = "DeviceLevels"
    /** Below this the panel is near black and the slider can no longer be seen. */
    private const val MIN_BRIGHTNESS = 10
    /**
     * The level maps linearly in dB relative to the player's full output: 0 is [MIN_DB],
     * 100 is [MAX_DB]. Full output was far too loud on the R1 speaker, so the top sits
     * below it (2026-09-14). A level of 0 is silence, not [MIN_DB].
     */
    private const val MAX_DB = -12.0
    private const val MIN_DB = -42.0
    const val DEFAULT_VOLUME = 60

    /**
     * The streams the app plays on sit at full; the level is applied per player through
     * [gain]. The ring stream, which nothing of ours uses, takes the level coarsely, and
     * nothing at all while [muted].
     */
    fun applyVolume(ctx: Context, pct: Int, muted: Boolean = false) {
        val am = ctx.getSystemService(AudioManager::class.java)
        for (stream in intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)) {
            runCatching { am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0) }.onFailure { Log.w(TAG, "volume", it) }
        }
        val ringMax = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val ring = if (muted) 0 else Math.ceil(pct.coerceIn(0, 100) * ringMax / 100.0).toInt()
        runCatching { am.setStreamVolume(AudioManager.STREAM_RING, ring, 0) }.onFailure { Log.w(TAG, "ring volume", it) }
    }

    /** Linear gain for a player, 0..1: the level mapped linearly from [MIN_DB] to [MAX_DB], silence at 0. */
    fun gain(pct: Int): Float {
        val p = pct.coerceIn(0, 100)
        if (p == 0) return 0f
        val db = MIN_DB + (MAX_DB - MIN_DB) * p / 100.0
        return Math.pow(10.0, db / 20.0).toFloat()
    }

    fun applyBrightness(ctx: Context, pct: Int) {
        val v = MIN_BRIGHTNESS + Math.round((255 - MIN_BRIGHTNESS) * pct.coerceIn(0, 100) / 100f)
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(ctx.packageName)) {
            val admin = ComponentName(ctx, AdminReceiver::class.java)
            runCatching {
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, v.toString())
            }.onFailure { Log.w(TAG, "brightness", it) }
        } else {
            runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v) }
                .onFailure { Log.w(TAG, "brightness: not owner and no WRITE_SETTINGS") }
        }
    }

    /**
     * Rest (Settings > Device > Battery): the screen's idle timeout, one for battery and
     * charger alike (2026-09-30). Never = the stay-on switch when plugged (which also
     * survives the app being away) and a 24-day timeout on battery.
     */
    fun applyRest(ctx: Context, ms: Int) {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) { Log.i(TAG, "rest: not owner, skipped"); return }
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, (if (ms == Idle.NEVER) plugged else 0).toString())
            dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, (if (ms == Idle.NEVER) Int.MAX_VALUE else ms).toString())
        }.onFailure { Log.w(TAG, "rest", it) }
    }

    fun currentBrightness(ctx: Context): Int {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return Math.round((v - MIN_BRIGHTNESS).coerceAtLeast(0) * 100f / (255 - MIN_BRIGHTNESS))
    }
}

/**
 * Settings > Device > Battery's two times, one list for both (2026-09-30): Rest, the
 * idle time before the screen and the mic go off; Sleep, the time still with the screen off
 * before the R1 suspends (IdleWatch). [NEVER] turns either off.
 */
object Idle {
    const val NEVER = -1
    val OPTIONS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, NEVER)
    /** 1 min, as the screen off on battery was. */
    const val REST_DEFAULT = 60_000
    const val SLEEP_DEFAULT = NEVER

    fun label(ms: Int): String = when {
        ms == NEVER -> "Never"
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h"
    }
}

