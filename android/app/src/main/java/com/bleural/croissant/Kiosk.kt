package com.bleural.croissant

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Makes the app *the device*: it is HOME, it is pinned, there is no lock screen and
 * no status bar. Everything here needs Device Owner; without it every call is a no-op
 * and the app behaves as an ordinary app, so development on the emulator is
 * unaffected.
 *
 * Power: reboot is a Device Owner API; power-off is not, and goes through root.
 * The PMIC hardware long-press was found not to fire on the R1, so
 * these two menu entries are the only on-device off/reset paths.
 */
object Kiosk {
    private const val TAG = "Kiosk"

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Idempotent; applied on every launch so a reinstall picks up policy changes. */
    fun apply(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
            // The keyguard is kept away by the owner's policy below, not by lock task: lock task
            // without this feature disables it, a sleep then leaves it waiting to come back, and
            // it shows the moment lock task ends, whenever this app's process does (an update,
            // a restart), until the next start dismisses it.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            dpm.addPersistentPreferredActivity(
                admin,
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
                ComponentName(ctx, MainActivity::class.java)
            )
            // The idle timeout (Settings > Device > Battery > Rest) is DeviceLevels.applyRest.
            // Scan results and the SSID need location even for the owner: with only
            // NEARBY_WIFI_DEVICES the scan list came back empty on this build (verified
            // 2026-09-14). Holding location makes Android flash its green location chip
            // over the top of our screen on every scan; that chip is turned off at provisioning
            // with `device_config put privacy location_indicators_enabled false`.
            for (p in listOf(android.Manifest.permission.RECORD_AUDIO,
                             android.Manifest.permission.CAMERA,
                             android.Manifest.permission.POST_NOTIFICATIONS,
                             android.Manifest.permission.NEARBY_WIFI_DEVICES,
                             android.Manifest.permission.BLUETOOTH_CONNECT,
                             // The mobile signal and the data state for the eSIM page.
                             android.Manifest.permission.READ_PHONE_STATE,
                             android.Manifest.permission.ACCESS_FINE_LOCATION,
                             android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
                dpm.setPermissionGrantState(admin, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            // The eSIM is for data; SMS stays off so nothing is sent or charged by
            // accident, and the eSIM page shows it as a toggle that cannot move
            // (2026-09-17).
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SMS)
            Log.i(TAG, "policies applied")
        } catch (e: SecurityException) {
            Log.e(TAG, "policy failed", e)
        }
    }

    /** Pin the activity. Harmless to call repeatedly. */
    fun enterLockTask(activity: Activity) {
        if (!isOwner(activity)) return
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            activity.startLockTask()
        }
    }

    /** Screen off. With the keyguard disabled this is plain sleep; the button wakes. */
    fun sleep(ctx: Context) {
        if (isOwner(ctx)) dpm(ctx).lockNow() else Log.w(TAG, "sleep: not owner")
    }

    fun reboot(ctx: Context) {
        if (isOwner(ctx)) dpm(ctx).reboot(admin(ctx)) else root("reboot")
    }

    /**
     * Off-mode charging is off in LK so a plug into an off R1 boots it, and this kernel turns
     * every power off into a restart that LK then decides.
     * With the charger in, LK would boot straight back up, so the flag goes to 1 first and LK
     * charges instead; the boot ramdisk's `croissant-offmode.rc` puts it back to 0 as charge-only
     * mode starts (`device/offmode/`). Without the script it is a plain power off, which reboots.
     */
    fun powerOff(ctx: Context) {
        val plugged = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        root(if (plugged != 0) "sh \"\$(magisk --path)/croissant-offmode.sh\" 1; svc power shutdown"
             else "svc power shutdown")
    }

    /**
     * The device's time zone, chosen by hand: automatic detection off first, which the owner
     * must do before it may set one (the first setup's time step).
     */
    fun setTimeZone(ctx: Context, id: String): Boolean {
        if (!isOwner(ctx)) { Log.w(TAG, "time zone: not owner"); return false }
        return try {
            dpm(ctx).setAutoTimeZoneEnabled(admin(ctx), false)
            dpm(ctx).setTimeZone(admin(ctx), id).also { Log.i(TAG, "time zone $id: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "time zone $id failed", e); false
        }
    }

    /** Undo everything so the app can be uninstalled or the device reprovisioned. */
    fun release(activity: Activity) {
        if (!isOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        runCatching { activity.stopLockTask() }
        dpm.setStatusBarDisabled(admin, false)
        dpm.setKeyguardDisabled(admin, false)
        dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
        dpm.clearDeviceOwnerApp(activity.packageName)
        Log.i(TAG, "device owner released")
    }

    private fun root(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (e: Exception) {
            Log.e(TAG, "root command failed: $cmd", e)
        }
    }
}
