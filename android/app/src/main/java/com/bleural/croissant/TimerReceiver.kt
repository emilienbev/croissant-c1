package com.bleural.croissant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Two ways in: the exact alarm firing, and the device booting (LOCKED_BOOT_COMPLETED,
 * since the app and its files are direct-boot aware). Both just poke the service,
 * which reads `timers.json`, rings what is due and schedules the next alarm. The
 * service is normally already running (the activity starts it), so this is a plain
 * onStartCommand; after a boot it may be the first thing to start it.
 */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("TimerReceiver", "received ${intent.action}")
        ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java).setAction(VoiceService.ACTION_TIMERS))
    }
}
