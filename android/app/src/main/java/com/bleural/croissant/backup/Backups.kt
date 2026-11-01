package com.bleural.croissant.backup

import android.content.Context
import android.os.Process
import android.util.Log
import com.bleural.croissant.Prefs
import com.bleural.croissant.backup.BackupPhase.Fetching
import com.bleural.croissant.backup.BackupPhase.Idle
import com.bleural.croissant.backup.BackupPhase.Listing
import com.bleural.croissant.backup.BackupPhase.Making
import com.bleural.croissant.backup.BackupPhase.Restarting
import com.bleural.croissant.backup.BackupPhase.Saved
import com.bleural.croissant.backup.BackupPhase.Sending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Device > backup, for the page to read and call. Back up now makes the archive in
 * the cache and sends it to the server in chunks; the server's list comes split into this
 * C1's backups and another C1's by serial; a restore is refused before anything is fetched
 * when the backup is newer than this app or not one it can read, else fetched into restore/,
 * and the app restarts so that its start applies it (CroissantApp.kt). One thing at a
 * time. Every failure is a [BackupProblem] carrying its data.
 *
 * Reads the last backup (a `backup_` pref, so a restore never replaces it) and the last
 * start's restore outcome when made.
 */
class Backups(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The server set now. */
    private val server: () -> Prefs.Server,
) {
    private val app = BackupDevice.app

    private val _ui = MutableStateFlow(BackupUi(
        last = Prefs.backupLast(ctx)?.let(LastBackup::fromJson),
        outcome = RestoreOutcome.read(BackupDevice.outcomeFile(ctx)),
    ))
    val ui: StateFlow<BackupUi> = _ui

    private var job: Job? = null

    /** Back up now, to the server set. */
    fun backUp() {
        if (running()) return
        val s = server()
        if (!s.set) return fail(BackupProblem.NoServer, Sending)
        _ui.update { it.copy(phase = Making, to = s.label, sent = 0L, total = 0L, problem = null, failedIn = null) }
        job = scope.launch {
            val out = BackupDevice.uploadFile(ctx)
            try {
                val made = try {
                    withContext(Dispatchers.IO) { BackupDevice.make(ctx, out) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SerialUnreadable) {
                    return@launch fail(BackupProblem.NoSerial(reason(e)), Making)
                } catch (e: Exception) {
                    return@launch fail(BackupProblem.Local(reason(e)), Making)
                }
                _ui.update { it.copy(phase = Sending, total = made.size) }
                val answer = BackupClient(s).upload(made) { n -> _ui.update { u -> if (u.phase == Sending) u.copy(sent = n) else u } }
                when (answer) {
                    is Answer.Done -> {
                        val last = LastBackup(answer.value.name, answer.value.folder, s.label, System.currentTimeMillis())
                        Prefs.setBackupLast(ctx, last.toJson())
                        _ui.update { it.copy(phase = Saved, last = last) }
                    }
                    is Answer.Failed -> fail(answer.problem, Sending)
                }
            } finally {
                out.delete()
            }
        }
    }

    /** A backup, a list or a fetch under way stops: the server drops a part it was sent, a fetched part is deleted. */
    fun cancel() {
        if (_ui.value.phase !in CANCELLABLE) return
        job?.cancel()
        _ui.update { it.copy(phase = Idle, sent = 0L, total = 0L, restoring = null, fetched = 0L) }
    }

    /** The server's backups, asked again. */
    fun list() {
        if (running()) return
        val s = server()
        if (!s.set) return fail(BackupProblem.NoServer, Listing)
        val serial = try { BackupDevice.serial() } catch (e: SerialUnreadable) { return fail(BackupProblem.NoSerial(reason(e)), Listing) }
        _ui.update { it.copy(phase = Listing, problem = null, failedIn = null) }
        job = scope.launch {
            when (val a = BackupClient(s).list(app.versionCode)) {
                is Answer.Done -> _ui.update { it.copy(phase = Idle, list = BackupList.split(a.value.folder, s.label, a.value.backups, serial)) }
                is Answer.Failed -> fail(a.problem, Listing)
            }
        }
    }

    /** Why a restore of [b] is refused before anything is fetched, for the page's card; null when it would go ahead. */
    fun refusal(b: ListedBackup): BackupProblem? {
        val about = b.about ?: return BackupProblem.Refused(b.name, "the server cannot read its manifest: ${b.error}")
        return when {
            about.app != app.id -> BackupProblem.Refused(b.name, "it is a backup of ${about.app}, and this app is ${app.id}")
            about.versionCode > app.versionCode -> BackupProblem.Newer(b.name, about.version, about.versionCode, app.version, app.versionCode)
            else -> null
        }
    }

    /** [b] fetched into restore/, then the app restarts and applies it; refused first when [refusal] says so. */
    fun restore(b: ListedBackup) {
        if (running()) return
        _ui.update { it.copy(restoring = b, fetched = 0L, problem = null, failedIn = null) }
        refusal(b)?.let { return fail(it, Fetching) }
        val s = server()
        if (!s.set) return fail(BackupProblem.NoServer, Fetching)
        _ui.update { it.copy(phase = Fetching) }
        job = scope.launch {
            val answer = BackupClient(s).fetch(b, BackupDevice.restoreDir(ctx)) { n ->
                _ui.update { u -> if (u.phase == Fetching) u.copy(fetched = n) else u }
            }
            when (answer) {
                is Answer.Done -> {
                    _ui.update { it.copy(phase = Restarting) }
                    delay(BackupConfig.RESTART_MS)
                    // HOME: killed, it comes straight back, and its start applies the restore.
                    Process.killProcess(Process.myPid())
                }
                is Answer.Failed -> fail(answer.problem, Fetching)
            }
        }
    }

    /** "backed up" or a problem put away: the page at rest. */
    fun dismiss() {
        if (_ui.value.busy) return
        _ui.update { it.copy(phase = Idle, problem = null, failedIn = null) }
    }

    /** The last start's restore outcome put away. */
    fun dismissOutcome() {
        val f = BackupDevice.outcomeFile(ctx)
        if (!f.delete() && f.exists()) Log.w(TAG, "could not delete ${f.path}: it shows again at the next start")
        _ui.update { it.copy(outcome = null) }
    }

    /** Busy, or a cancelled job still finishing its file: one at a time, so two never write the same one. */
    private fun running() = _ui.value.busy || job?.isActive == true

    private fun fail(problem: BackupProblem, at: BackupPhase) {
        Log.w(TAG, "$at stopped: $problem")
        _ui.update { it.copy(phase = Idle, problem = problem, failedIn = at) }
    }

    private companion object {
        const val TAG = "Backup"
        val CANCELLABLE = setOf(Making, Sending, Listing, Fetching)
    }
}
