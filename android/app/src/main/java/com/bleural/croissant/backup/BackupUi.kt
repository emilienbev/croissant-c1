package com.bleural.croissant.backup

import org.json.JSONObject

enum class BackupPhase {
    Idle,
    /** The archive being made, before a byte goes. */
    Making,
    /** Going to [BackupUi.to]: [BackupUi.sent] of [BackupUi.total] bytes taken by the server. */
    Sending,
    /** The server kept it: [BackupUi.last] is the one just sent, until put away. */
    Saved,
    /** Asking the server for its backups. */
    Listing,
    /** [BackupUi.restoring] coming into restore/: [BackupUi.fetched] of its size. */
    Fetching,
    /** Fetched: the app restarts, and its start applies it. */
    Restarting,
}

/** What the backup page draws (backup/Backups.kt). */
data class BackupUi(
    /** This C1's last backup to a server, null before the first. */
    val last: LastBackup? = null,
    val phase: BackupPhase = BackupPhase.Idle,
    /** While [BackupPhase.Making] and [BackupPhase.Sending]: the server's name. */
    val to: String = "",
    /** Kept after a failure, to say how far it got. */
    val sent: Long = 0L,
    val total: Long = 0L,
    /** The server's backups, once asked; kept while a restore of one runs. */
    val list: BackupList? = null,
    /** The one being fetched, and after a refused or failed restore the one it was. */
    val restoring: ListedBackup? = null,
    val fetched: Long = 0L,
    val problem: BackupProblem? = null,
    /** With [problem]: the phase it stopped, so the page knows which question it answers. */
    val failedIn: BackupPhase? = null,
    /** What the last start made of a restore, until put away. */
    val outcome: RestoreOutcome? = null,
) {
    val busy: Boolean get() = phase in BUSY

    private companion object {
        val BUSY = setOf(BackupPhase.Making, BackupPhase.Sending, BackupPhase.Listing, BackupPhase.Fetching, BackupPhase.Restarting)
    }
}

/** A backup this C1 made: its file, the folder and the machine it went to, and when (ms). */
data class LastBackup(val name: String, val folder: String, val machine: String, val at: Long) {
    fun toJson(): String = JSONObject().put("name", name).put("folder", folder).put("machine", machine).put("at", at).toString()

    companion object {
        fun fromJson(s: String): LastBackup? = runCatching {
            val o = JSONObject(s)
            LastBackup(o.getString("name"), o.getString("folder"), o.getString("machine"), o.getLong("at"))
        }.getOrNull()
    }
}

/** What a backup's manifest says, as the server read it. */
data class BackupAbout(val app: String, val version: String, val versionCode: Int, val serial: String, val date: String)

/**
 * One backup as the server lists it (`GET /backups`): its manifest's [about], or [error]
 * instead when the server cannot read it. [newer]: made by a versionCode above this app's, so
 * a restore of it is refused before anything is fetched.
 */
data class ListedBackup(
    val name: String,
    val size: Long,
    val about: BackupAbout?,
    val error: String? = null,
    val newer: Boolean = false,
)

/** The server's backups, newest first in each group: this C1's (by serial), another C1's, and those it cannot read. */
data class BackupList(
    val folder: String,
    val machine: String,
    val mine: List<ListedBackup>,
    val others: List<ListedBackup>,
    val unreadable: List<ListedBackup>,
) {
    companion object {
        fun split(folder: String, machine: String, all: List<ListedBackup>, serial: String): BackupList {
            val (readable, unreadable) = all.partition { it.about != null }
            val (mine, others) = readable.partition { it.about?.serial == serial }
            return BackupList(folder, machine, mine, others, unreadable)
        }
    }
}

/** Why a backup or a restore stopped, with what the page says about it. */
sealed interface BackupProblem {
    /** No server set: backups go to croissant-server. */
    data object NoServer : BackupProblem

    /** [machine] did not answer at [address] (host:port); [why] is what the connection said. */
    data class Unreachable(val machine: String, val address: String, val why: String) : BackupProblem

    /** [machine]'s croissant-server has no backups (no `/backups`, or `/backup` closed as an unknown path): update it. */
    data class TooOld(val machine: String) : BackupProblem

    /** [machine] on the LAN wants its pairing code scanned again (401, net/ServerAuth.kt). */
    data class NeedsCode(val machine: String) : BackupProblem

    /** The connection to [machine] broke part way, or it stopped answering. */
    data class Dropped(val machine: String, val why: String) : BackupProblem

    /**
     * It arrived with [got] bytes where [expected] were sent (an upload: the server dropped its
     * copy) or listed (a fetch: this C1 dropped its own). [detail] as it was found.
     */
    data class Damaged(val got: Long, val expected: Long, val detail: String) : BackupProblem

    /** The disk on [machine] is full; [detail] in the server's words. */
    data class DiskFull(val machine: String, val detail: String) : BackupProblem

    /** [machine] turned it away for another reason, in its words. */
    data class ServerSaid(val machine: String, val message: String) : BackupProblem

    /** This C1 could not make the archive, or keep the one fetched; [why] names the file. */
    data class Local(val why: String) : BackupProblem

    /** This C1's serial cannot be read, so a backup cannot say whose it is nor the list whose are this C1's. */
    data class NoSerial(val why: String) : BackupProblem

    /** Too little room on this C1 to fetch and apply it: [free] bytes, [needed] wanted. */
    data class NoRoom(val free: Long, val needed: Long) : BackupProblem

    /** Refused before anything is fetched: [name] was made by [version] ([versionCode]), newer than this app's [app] ([appCode]). */
    data class Newer(val name: String, val version: String, val versionCode: Int, val app: String, val appCode: Int) : BackupProblem

    /** Refused before anything is fetched, for [why]: its manifest cannot be read, or it is another app's. */
    data class Refused(val name: String, val why: String) : BackupProblem
}
