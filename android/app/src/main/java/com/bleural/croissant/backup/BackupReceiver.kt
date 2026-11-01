package com.bleural.croissant.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * The CLI's `croissant backup` (installer/croissant/backup.py). Root reaches this receiver,
 * which is not exported, so no other app can:
 *
 * ```
 * am broadcast -n <applicationId>/com.bleural.croissant.backup.BackupReceiver \
 *   -a com.bleural.croissant.action.BACKUP --es name 2026-11-01-1402
 * ```
 *
 * It builds the archive into filesDir/backup-out/<name>.zip, the name without `.zip` as the
 * CLI picks it from the computer's clock, and answers the ordered broadcast with
 * [BackupConfig.RESULT_MADE] and the zip's path, or [BackupConfig.RESULT_FAILED] and why. The
 * CLI pulls the zip and deletes it. Built on a thread of its own (goAsync): the broadcast
 * waits for the answer.
 */
class BackupReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BackupConfig.ACTION) {
            setResult(BackupConfig.RESULT_FAILED, "the action has to be ${BackupConfig.ACTION}, got ${intent.action}", null)
            return
        }
        val stem = intent.getStringExtra(BackupConfig.EXTRA_NAME)
        if (stem == null || !BackupConfig.NAME.matches(stem + BackupConfig.ZIP)) {
            setResult(BackupConfig.RESULT_FAILED, "the extra ${BackupConfig.EXTRA_NAME} has to be YYYY-MM-DD-HHMM, " +
                "with no .zip, got ${stem?.take(80)}", null)
            return
        }
        val pending = goAsync()
        thread(name = "backup") {
            try {
                val out = BackupDevice.outFile(ctx, stem)
                // One at a time: whatever an earlier run left the CLI did not take.
                out.parentFile?.listFiles()?.forEach { it.delete() }
                val made = BackupDevice.make(ctx, out)
                Log.i(TAG, "made ${made.file.name}, ${made.size} bytes, for the CLI")
                pending.setResult(BackupConfig.RESULT_MADE, made.file.absolutePath, null)
            } catch (e: Exception) {
                Log.w(TAG, "backup for the CLI failed: ${reason(e)}")
                pending.setResult(BackupConfig.RESULT_FAILED, reason(e), null)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Backup"
    }
}
