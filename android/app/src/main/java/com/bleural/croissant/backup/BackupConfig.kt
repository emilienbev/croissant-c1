package com.bleural.croissant.backup

import com.bleural.croissant.audio.Cues
import com.bleural.croissant.update.Updater

/**
 * Every name and limit of backup and restore, in one place. The archive's names are a
 * contract with croissant-server (server/croissant_server/backups.py) and the installer
 * (installer/croissant/archive.py): a change here is a change there.
 */
object BackupConfig {
    // ---- the archive ----------------------------------------------------------

    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val PREFS = "prefs.json"
    const val KEYS = "keys.json"
    /** The device-protected filesDir, under this prefix in the zip. */
    const val FILES = "files/"
    const val KEYS_VERSION = 1

    /** A backup's name, given by the server's clock or the CLI's: `2026-11-01-1402.zip`, `-2` for a second in that minute. */
    val NAME = Regex("""^\d{4}-\d{2}-\d{2}-\d{4}(-\d+)?\.zip$""")
    const val ZIP = ".zip"
    /** A file being written, never carried: the stores write through one (temp, then rename). */
    const val TMP = ".tmp"

    // ---- the folders, in the device-protected filesDir ------------------------

    /** Where a restore waits (the app's fetch, the CLI's push) and is staged. Never carried. */
    const val RESTORE_DIR = "restore"
    /** Inside [RESTORE_DIR]: the archive unpacked and checked, before anything else moves. */
    const val STAGING_DIR = "staging"
    /** Inside [RESTORE_DIR]: the current files, moved there until the restore is through. */
    const val ASIDE_DIR = "aside"
    /** Inside [RESTORE_DIR]: how far a restore got, so a start after a crash rolls it back. */
    const val JOURNAL_FILE = "journal.json"
    /** The CLI's archive, `<name>.zip`, until the CLI has pulled it. Never carried. */
    const val OUT_DIR = "backup-out"
    /** What the last restore came to, for the page and the CLI. Never carried. */
    const val OUTCOME_FILE = "restore-outcome.json"
    /** The archive an upload sends, in the device-protected cache: outside filesDir, so never in itself. */
    const val UPLOAD_FILE = "backup.zip"

    /**
     * Top-level names in filesDir an archive never carries: the sealed keys (the opened copy
     * is [KEYS]), the update's download, the font, the restore's and the CLI's folders, the
     * outcome, and the installer's intake files.
     */
    val NOT_CARRIED = setOf(KEYS, "update", "fonts", RESTORE_DIR, OUT_DIR, OUTCOME_FILE, Updater.SOURCE_FILE, Cues.PACK_FILE)

    /** What a restore leaves where it is: this install's own. The sealed keys are replaced. */
    val STAYS = NOT_CARRIED - KEYS

    /**
     * Restored only on the C1 that made the archive: the battery log (its raw samples and its
     * day and month summaries, data/BatteryLog.kt) and the model test's scores describe that
     * R1's hardware. Another R1 keeps its own.
     */
    val SAME_C1_ONLY = setOf("battery.jsonl", "battery-days.jsonl", "battery-months.jsonl", "scores.json")

    // ---- prefs (Prefs.kt's keys) ------------------------------------------------

    /** This install's own: its update state and its last backup, never set by a restore. */
    val OWN_PREFIXES = listOf("update_", "backup_")
    /** The server's settings, kept when this install has one: the server it restored through answers. */
    const val SERVER_PREFIX = "server_"
    const val SERVER_HOST = "server_host"

    // ---- limits ---------------------------------------------------------------

    /** The most an upload chunk holds (the server's `backup_chunk_bytes`). */
    const val CHUNK_BYTES = 1024 * 1024
    /** manifest.json, prefs.json and keys.json are read whole; anything past this is not ours. */
    const val TEXT_MAX_BYTES = 4 * 1024 * 1024
    /** A fetch needs room for the zip and the files unpacked from it, with margin. */
    const val ROOM_FACTOR = 3
    const val COPY_BUFFER = 64 * 1024

    // ---- timing ---------------------------------------------------------------

    const val CONNECT_TIMEOUT_MS = 5_000L
    /** A list or a fetch that stops sending this long has dropped. */
    const val READ_TIMEOUT_MS = 30_000L
    /** The longest an upload waits for the server's next frame (`ready`, `got`, `saved`). */
    const val REPLY_TIMEOUT_MS = 30_000L
    /** How often a fetch's progress reaches the page. */
    const val PROGRESS_MS = 250L
    /** "restarting" stays this long on the page before the process goes. */
    const val RESTART_MS = 1_000L

    // ---- the CLI's hook (BackupReceiver) ----------------------------------------

    /** The namespace's, not the application id's: a fork's CLI sends the same action. */
    const val ACTION = "com.bleural.croissant.action.BACKUP"
    const val EXTRA_NAME = "name"
    /** Activity.RESULT_OK: the data is the zip's path. */
    const val RESULT_MADE = -1
    /** Activity.RESULT_FIRST_USER: the data is why not. */
    const val RESULT_FAILED = 1
}
