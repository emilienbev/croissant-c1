package com.bleural.croissant.backup

import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.Sealer
import com.bleural.croissant.backup.BackupConfig.ASIDE_DIR
import com.bleural.croissant.backup.BackupConfig.COPY_BUFFER
import com.bleural.croissant.backup.BackupConfig.FILES
import com.bleural.croissant.backup.BackupConfig.FORMAT
import com.bleural.croissant.backup.BackupConfig.JOURNAL_FILE
import com.bleural.croissant.backup.BackupConfig.KEYS
import com.bleural.croissant.backup.BackupConfig.MANIFEST
import com.bleural.croissant.backup.BackupConfig.OUTCOME_FILE
import com.bleural.croissant.backup.BackupConfig.PREFS
import com.bleural.croissant.backup.BackupConfig.RESTORE_DIR
import com.bleural.croissant.backup.BackupConfig.STAGING_DIR
import com.bleural.croissant.backup.BackupConfig.TEXT_MAX_BYTES
import com.bleural.croissant.backup.BackupConfig.TMP
import com.bleural.croissant.backup.BackupConfig.ZIP
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The settings file, read and replaced whole: Prefs on the C1 (BackupDevice), a map in the tests. */
interface PrefsAccess {
    fun read(): Map<String, Any?>

    /** Every value replaced by [values], on disk before it returns. Throws [IOException] when it cannot be written. */
    fun replace(values: Map<String, Any>)
}

/**
 * Applies the archive waiting in filesDir/restore/, at the process start, before anything
 * reads a store or a pref. One path for both carriers: the app's own fetch and the CLI's push.
 *
 * Refused, with nothing changed, unless the zip is named as a backup, is this app's
 * ([AppVersion.id]) from a versionCode no newer than this one, lists every entry with a
 * matching sha256 and holds no entry that would land outside filesDir. Then: unpacked into
 * restore/staging (the hashes checked on the way), the keys sealed there under this install's
 * keystore, the current files moved into restore/aside, the staged ones moved in, the prefs
 * set, and the aside folder deleted. A journal written before each phase lets a start after a
 * crash finish or roll back. Any failure puts the old files and prefs back.
 *
 * What stays where it is: [BackupConfig.STAYS], this install's own, and on another C1 (another
 * serial) its battery log and scores ([BackupConfig.SAME_C1_ONLY]). The prefs keep this install's
 * `update_*` and `backup_*`, and its `server_*` when it has a server set.
 *
 * Every outcome is written to restore-outcome.json and deletes the zip, so a refused one is
 * never tried again at the next start: it is a copy, the server or the computer keeps the backup.
 */
class RestoreApplier(
    private val files: File,
    private val sealer: Sealer,
    private val prefs: PrefsAccess,
    private val app: AppVersion,
    /** This C1's serial. A throw refuses the restore, since it decides what stays. */
    private val serial: () -> String,
) {
    private val dir = File(files, RESTORE_DIR)
    private val staging = File(dir, STAGING_DIR)
    private val aside = File(dir, ASIDE_DIR)
    private val journal = File(dir, JOURNAL_FILE)
    private val outcomeFile = File(files, OUTCOME_FILE)

    private class Staged(val manifest: Manifest, val prefs: Map<String, Any>, val keys: Map<String, String>)

    /** The restore waiting, applied; a crashed one finished or rolled back. Null when there was none. */
    fun run(): RestoreOutcome? {
        if (!dir.isDirectory) return null
        if (journal.exists()) return record(recover())
        // A fetch cut off, or a crash before the first journal: nothing had moved yet.
        waiting { it.name.endsWith(ZIP + TMP) }.forEach { it.delete() }
        staging.deleteRecursively()
        val zips = waiting { it.name.endsWith(ZIP) }
        if (zips.isEmpty()) return null
        val outcome = if (zips.size == 1) apply(zips[0]) else {
            val names = zips.joinToString { it.name }
            RestoreOutcome.failed(names, "restore/ holds ${zips.size} zips ($names): one at a time, so none was applied")
        }
        zips.forEach { it.delete() }
        return record(outcome)
    }

    private fun waiting(which: (File) -> Boolean): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && which(it) }.sortedBy { it.name }

    private fun apply(zip: File): RestoreOutcome {
        val name = zip.name
        val staged: Staged
        val stays: Set<String>
        val before: Map<String, Any>
        try {
            if (!BackupConfig.NAME.matches(name)) throw Unfit("it is not named as a backup (YYYY-MM-DD-HHMM.zip)")
            if (aside.exists()) throw Unfit("${aside.path} is left from an earlier restore, with no journal to say what it holds")
            staged = stage(zip)
            stays = stays(staged.manifest)
            seal(staged.keys)
            // Another C1's battery log and scores stay out of it; this C1 keeps its own.
            for (n in stays) if (!File(staging, n).deleteRecursively()) throw IOException("could not leave out $n")
            before = present(prefs.read())
        } catch (e: Exception) {
            staging.deleteRecursively()
            return RestoreOutcome.failed(name, "$name was not restored: ${reason(e)}")
        }
        val next = merged(before, staged.prefs)
        val j = Journal(name, Phase.Aside, stays, before)
        var phase = Phase.Aside
        try {
            j.write(journal)
            moveAside(stays)
            phase = Phase.In
            j.copy(phase = phase).write(journal)
            moveIn()
            prefs.replace(next)
            phase = Phase.Applied
            j.copy(phase = phase).write(journal)
        } catch (e: Exception) {
            val back = rollBack(j, phase)
            return RestoreOutcome.failed(name, "$name could not be applied (${reason(e)}): " +
                (back?.let { "putting the old files back failed too ($it): what is left of them is in ${aside.path}, and the next start tries again" }
                    ?: "everything was put back as it was"))
        }
        cleanUp()
        return RestoreOutcome(name, true)
    }

    /** A start that finds a journal: the restore before it stopped part way. */
    private fun recover(): RestoreOutcome {
        val j = try {
            Journal.read(journal)
        } catch (e: Exception) {
            return RestoreOutcome.failed("", "a restore stopped part way and its journal ${journal.path} cannot be read " +
                "(${reason(e)}): what was moved aside is in ${aside.path}")
        }
        if (BackupConfig.NAME.matches(j.name)) File(dir, j.name).delete()
        if (j.phase == Phase.Applied) {
            cleanUp()
            return RestoreOutcome(j.name, true)
        }
        val back = rollBack(j, j.phase)
        return RestoreOutcome.failed(j.name, "the restore of ${j.name} stopped part way (the app was stopped): " +
            (back?.let { "putting the old files back failed ($it): what is left of them is in ${aside.path}, and the next start tries again" }
                ?: "everything was put back as it was"))
    }

    // ---- checking and staging ---------------------------------------------------

    /** Every check, then every entry unpacked into the staging folder, each hash checked on the way. */
    private fun stage(zip: File): Staged = try {
        ZipFile(zip).use { z ->
            val entries = LinkedHashMap<String, ZipEntry>()
            for (e in z.entries()) {
                if (entries.put(e.name, e) != null) throw Unfit("it holds ${e.name} twice")
                if (!Archive.safe(e.name)) throw Unfit("it holds an entry named \"${e.name}\", which would land outside the app's files")
            }
            val m = Manifest.parse(text(z, entries[MANIFEST] ?: throw Unfit("it has no $MANIFEST"), null))
            if (m.format != FORMAT) throw Unfit("its format is ${m.format}, and this app reads $FORMAT")
            if (m.app != app.id) throw Unfit("it is a backup of ${m.app}, and this app is ${app.id}")
            if (m.versionCode > app.versionCode) {
                throw Unfit("it was made by version ${m.version} (${m.versionCode}), newer than this app's " +
                    "${app.version} (${app.versionCode}): update the app, then restore")
            }
            for (n in entries.keys) if (n != MANIFEST && n !in m.files) throw Unfit("$n is in the zip but not in its manifest")
            for (n in m.files.keys) {
                if (n !in entries) throw Unfit("$n is in its manifest but not in the zip")
                if (!Archive.allowed(n)) throw Unfit("it holds $n, which a backup never carries")
            }
            for (n in listOf(PREFS, KEYS)) if (n !in m.files) throw Unfit("it has no $n")
            val prefsText = text(z, entries.getValue(PREFS), m.files.getValue(PREFS))
            val keysText = text(z, entries.getValue(KEYS), m.files.getValue(KEYS))
            if (!staging.mkdirs()) throw IOException("could not make ${staging.path}")
            for (n in m.files.keys.filter { it.startsWith(FILES) }.sorted()) unpack(z, entries.getValue(n), m.files.getValue(n))
            val prefsJson = try { JSONObject(prefsText) } catch (e: JSONException) { throw Unfit("prefs.json is not JSON: ${e.message}") }
            Staged(m, TypedPrefs.decode(prefsJson), Archive.parseKeys(keysText))
        }
    } catch (e: java.util.zip.ZipException) {
        throw Unfit("it cannot be read as a zip (${e.message})")
    }

    /** A small entry read whole, its hash checked against [want] (null: the manifest itself, which no hash covers). */
    private fun text(z: ZipFile, e: ZipEntry, want: String?): String {
        val over = Unfit("${e.name} is over $TEXT_MAX_BYTES bytes")
        if (e.size > TEXT_MAX_BYTES) throw over
        val bytes = z.getInputStream(e).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(COPY_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > TEXT_MAX_BYTES) throw over
            }
            out.toByteArray()
        }
        if (want != null) checkHash(e.name, Archive.hex(Archive.sha256().digest(bytes)), want)
        return String(bytes, Charsets.UTF_8)
    }

    /** files/<path> into staging/<path>, synced, with its time, its hash checked. */
    private fun unpack(z: ZipFile, e: ZipEntry, want: String) {
        val out = File(staging, e.name.removePrefix(FILES))
        // Archive.safe already refused `..`; this holds whatever the platform makes of a name.
        if (!out.canonicalFile.startsWith(staging.canonicalFile)) throw Unfit("${e.name} would land outside the app's files")
        out.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("could not make ${it.path}") }
        val sha = Archive.sha256()
        try {
            z.getInputStream(e).use { input ->
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sha.update(buf, 0, n)
                        o.write(buf, 0, n)
                    }
                    o.fd.sync()
                }
            }
        } catch (x: IOException) {
            throw IOException("${e.name} could not be unpacked into ${out.path}: ${x.message}")
        }
        checkHash(e.name, Archive.hex(sha.digest()), want)
        if (e.time > 0) out.setLastModified(e.time)
    }

    private fun checkHash(name: String, got: String, want: String) {
        if (!got.equals(want, ignoreCase = true)) throw Unfit("$name arrived damaged: sha256 $got, its manifest says $want")
    }

    /** What stays where it is: this install's own, and on another C1 its battery log and scores too. */
    private fun stays(m: Manifest): Set<String> {
        val here = try { serial() } catch (e: Exception) { throw Unfit("this C1's serial cannot be read (${reason(e)})") }
        return if (m.serial == here) BackupConfig.STAYS else BackupConfig.STAYS + BackupConfig.SAME_C1_ONLY
    }

    /** The archive's keys sealed under this install's keystore, into the staged keys.json. */
    private fun seal(keys: Map<String, String>) {
        val store = KeysStore(File(staging, KEYS), sealer)
        for ((id, key) in keys) store.save(id, key)?.let { throw Unfit("the key for $id cannot be kept: $it") }
    }

    // ---- moving -----------------------------------------------------------------

    private fun moveAside(stays: Set<String>) {
        if (!aside.mkdirs()) throw IOException("could not make ${aside.path}")
        for (f in list(files)) if (f.name !in stays) move(f, File(aside, f.name))
    }

    private fun moveIn() {
        for (f in list(staging)) {
            val to = File(files, f.name)
            if (to.exists()) throw IOException("${to.path} is in the way")
            move(f, to)
        }
    }

    /**
     * The old files and prefs back, from [phase] on; null when all went back, else why not. From
     * [Phase.In] on, every top-level entry not in [Journal.stays] came from the archive and goes
     * first, and the journal then says [Phase.Aside], so a rollback cut short and repeated at the
     * next start only moves back what is still aside and never deletes an old file. The journal
     * goes last.
     */
    private fun rollBack(j: Journal, phase: Phase): String? = try {
        if (phase != Phase.Aside) {
            for (f in list(files)) if (f.name !in j.stays && !f.deleteRecursively()) throw IOException("could not delete ${f.path}")
            j.copy(phase = Phase.Aside).write(journal)
        }
        if (aside.exists()) for (f in list(aside)) {
            val back = File(files, f.name)
            if (back.exists()) throw IOException("${back.path} is in the way")
            move(f, back)
        }
        prefs.replace(j.prefs)
        if (journal.exists() && !journal.delete()) throw IOException("could not delete ${journal.path}")
        aside.deleteRecursively()
        staging.deleteRecursively()
        null
    } catch (e: Exception) {
        reason(e)
    }

    /** The old files and the staging deleted; the journal last, so a crash here only repeats this. */
    private fun cleanUp() {
        if (aside.deleteRecursively() && staging.deleteRecursively()) journal.delete()
    }

    private fun list(d: File): Array<File> = d.listFiles() ?: throw IOException("${d.path} cannot be listed")

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** The outcome kept for the page and the CLI. One that cannot be written says so, for the log. */
    private fun record(o: RestoreOutcome): RestoreOutcome = try {
        o.write(outcomeFile)
        o
    } catch (e: IOException) {
        o.copy(error = listOfNotNull(o.error, "${outcomeFile.path} could not be written (${e.message})").joinToString("; "))
    }

    companion object {
        /**
         * The prefs a restore sets: the archive's, except this install's own `update_*` and
         * `backup_*`, and its `server_*` when it has a server set; those keep their current values.
         */
        internal fun merged(current: Map<String, Any>, archive: Map<String, Any>): Map<String, Any> {
            val serverHere = (current[BackupConfig.SERVER_HOST] as? String)?.isNotBlank() == true
            fun own(k: String) = BackupConfig.OWN_PREFIXES.any { k.startsWith(it) } || (serverHere && k.startsWith(BackupConfig.SERVER_PREFIX))
            return archive.filterKeys { !own(it) } + current.filterKeys(::own)
        }

        private fun present(m: Map<String, Any?>): Map<String, Any> = buildMap { for ((k, v) in m) if (v != null) put(k, v) }
    }
}
