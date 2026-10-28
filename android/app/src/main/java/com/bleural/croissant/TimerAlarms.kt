package com.bleural.croissant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One exact alarm for the soonest timer, replaced on every change. When it fires,
 * [TimerReceiver] pokes [VoiceService], which rings what is due and sets the next.
 * `setAlarmClock` is used because that is what this is: it is exact, allowed through
 * doze, and needs only USE_EXACT_ALARM (the API 33 alarm-clock permission, granted at
 * install). If that grant ever goes, the inexact "allow while idle" set is the fallback.
 */
object TimerAlarms {
    private const val TAG = "TimerAlarms"
    const val ACTION_FIRE = "com.bleural.croissant.TIMER_FIRE"

    fun set(ctx: Context, nextAt: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePending(ctx)
        am.cancel(pi)
        if (nextAt == null) { Log.i(TAG, "no timer ahead"); return }
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (am.canScheduleExactAlarms()) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(nextAt, show), pi)
            Log.i(TAG, "exact alarm in ${(nextAt - System.currentTimeMillis()) / 1000} s")
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pi)
            Log.w(TAG, "exact alarms not allowed; inexact alarm in ${(nextAt - System.currentTimeMillis()) / 1000} s")
        }
    }

    private fun firePending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, TimerReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
