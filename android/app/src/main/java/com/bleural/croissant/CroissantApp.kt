package com.bleural.croissant

import android.app.Application
import android.util.Log
import com.bleural.croissant.backup.BackupDevice
import com.bleural.croissant.backup.RestoreOutcome

/**
 * The app's Application. Its one job: a restore waiting in restore/ is applied here, before
 * any activity, service or receiver starts, so before any store or pref is read. Only in the
 * main process: the Atelier's isolated process (`:atelier`) runs this class too, and has no
 * files.
 */
class CroissantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return
        // Never a crash at the start: the app is HOME, and a crash here would loop. The backup
        // page shows what went wrong instead, from the outcome file, and the log has the trace.
        val outcome = try {
            BackupDevice.applyRestore(this)
        } catch (e: Exception) {
            Log.e(TAG, "the restore could not run", e)
            unexpected(e)
            return
        } ?: return
        if (outcome.ok) Log.i(TAG, "restored ${outcome.name}")
        else Log.w(TAG, "restore of ${outcome.name.ifEmpty { "a backup" }} refused or failed: ${outcome.error}")
    }

    private fun unexpected(e: Exception) {
        try {
            RestoreOutcome.failed("", "the restore could not run (${e.javaClass.simpleName}: ${e.message})")
                .write(BackupDevice.outcomeFile(this))
        } catch (w: Exception) {
            Log.e(TAG, "and its outcome could not be written", w)
        }
    }

    private companion object {
        const val TAG = "Restore"
    }
}
