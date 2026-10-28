package com.bleural.croissant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.bleural.croissant.data.BatterySample
import com.bleural.croissant.data.Stores

/**
 * The battery sampler behind Settings > Device > Battery. While tracking is on, one exact alarm fires at every multiple of the
 * interval on the clock (5 min by default, 2026-10-18) and
 * [BatteryReceiver] takes a sample and sets the next; a sample is also taken at every
 * plug, unplug, screen on and screen off (the voice service's receiver), boot and
 * service start, so a run's edges are exact. Everything the sample holds comes from the battery broadcast and the gauge
 * through [BatteryManager]; no root.
 *
 * The alarm is a wakeup: asleep (the voice service's wake lock released, IdleWatch) it
 * brings the R1 out of suspend, about 1.75 s late on battery, and allow-while-idle
 * keeps it firing in Doze.
 */
object BatteryTracker {
    private const val TAG = "Battery"
    const val ACTION_TICK = "com.bleural.croissant.BATTERY_TICK"
    /**
     * The Every picker, in order. One minute is 60 allow-while-idle alarms an hour, under
     * the 72 the system allows an app in Doze (`dumpsys alarm`, allow_while_idle_quota).
     */
    val EVERY_OPTIONS = listOf(1, 3, 5, 10, 15, 30, 60, 120).map { it * 60_000 }
    const val DEFAULT_EVERY_MS = 5 * 60_000

    fun label(ms: Int): String = if (ms < 3_600_000) "${ms / 60_000} min" else "${ms / 3_600_000} h"

    /** Apply the prefs: the next tick, or none. Called at service start and on every change to the two settings. */
    fun sync(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = tick(ctx)
        am.cancel(pi)
        if (!Prefs.batteryTrack(ctx)) { Log.i(TAG, "tracking off"); return }
        val every = Prefs.batteryEvery(ctx).toLong()
        val now = System.currentTimeMillis()
        val next = (now / every + 1) * every
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        Log.i(TAG, "next sample in ${(next - now) / 1000} s")
    }

    /** One sample now, if tracking is on; [why] says what prompted it. */
    fun sample(ctx: Context, why: String) {
        if (!Prefs.batteryTrack(ctx)) return
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val bm = ctx.getSystemService(BatteryManager::class.java)
        var ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        if (ua == Int.MIN_VALUE || ua == 0) ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE) ua = 0
        val s = BatterySample(
            at = System.currentTimeMillis(),
            pct = level * 100 / scale,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive,
            mV = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            uA = ua,
            tempC10 = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            why = why,
        )
        Stores.battery(ctx).append(s)
        Log.i(TAG, "sample $why: ${s.pct}% ${if (s.plugged) "plugged" else "on battery"} ${if (s.screenOn) "screen on" else "screen off"} ${s.mV} mV ${s.uA} uA")
    }

    private fun tick(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, BatteryReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * The tick alarm and boot: each a sample and the next tick. The charger in and out are
 * sampled by the voice service's receiver: declared here they never arrived (Android 8
 * and later keep them from manifest receivers; no plug sample in the first day's log).
 */
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BatteryTracker.ACTION_TICK -> { BatteryTracker.sample(ctx, "tick"); BatteryTracker.sync(ctx) }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> { BatteryTracker.sample(ctx, "boot"); BatteryTracker.sync(ctx) }
        }
    }
}
