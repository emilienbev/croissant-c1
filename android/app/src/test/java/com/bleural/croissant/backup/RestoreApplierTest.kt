package com.bleural.croissant.backup

import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.SoftSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A restore applied on the JVM: archives made by [ArchiveMaker], keys sealed by [SoftSealer], prefs in a map. */
class RestoreApplierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = AppVersion("com.bleural.croissant", "0.1.0", 100)
    private val gemini = "AIza00000000000000000000000000000000MNOP"
    private val openai = "openai-test-key-000000000000000"
    private val here = "TEST-C1-ONE"

    /** The settings file as a map: the next [failures] writes fail; [crash] stands for the process dying straight after one. */
    private class MapPrefs(var values: Map<String, Any?>) : PrefsAccess {
        var failures = 0
        var crash = false
        override fun read() = values
        override fun replace(values: Map<String, Any>) {
            if (failures > 0) { failures--; throw IOException("the settings file could not be written") }
            this.values = values
            if (crash) throw Crash()
        }
    }

    /** Not an Exception: the applier does not catch it, as it could not catch a killed process. */
    private class Crash : Error("the process died")

    private val theirPrefs: Map<String, Any?> = mapOf(
        "volume" to 40, "weather_places" to "[{\"id\":\"p1\"}]", "practice_ms" to 5_000_000_000L, "gain" to 0.5f,
        "muted" to true, "tags" to setOf("a", "b"), "update_daily" to false, "backup_last" to "{theirs}",
        "server_host" to "100.100.100.1", "server_token" to "theirs",
    )

    private val theirFiles = mapOf(
        "reminders.json" to "their reminders", "photos/p1.jpg" to "their photo",
        "battery.jsonl" to "their battery", "battery-days.jsonl" to "their days", "scores.json" to "their scores",
    )

    /** An archive of a C1 holding [files], [prefs] and [keys], named [name]. */
    private fun archive(
        origin: Origin = Origin(app, here, "2026-11-01T14:02:31+01:00"),
        files: Map<String, String> = theirFiles,
        prefs: Map<String, Any?> = theirPrefs,
        keys: Map<String, String> = mapOf("gemini" to gemini),
        name: String = "2026-11-01-1402.zip",
    ): File {
        val src = tmp.newFolder()
        for ((p, t) in files) File(src, p).apply { parentFile?.mkdirs(); writeText(t) }
        val store = KeysStore(File(src, "keys.json"), SoftSealer())
        for ((id, k) in keys) assertNull(store.save(id, k))
        return ArchiveMaker(src).make(File(tmp.newFolder(), name), prefs, store, origin).file
    }

    /** A C1 to restore onto, with files, a sealed key and prefs of its own. */
    private inner class Target(serialNo: String = here, prefs: Map<String, Any?> = mapOf("volume" to 10, "update_daily" to true, "backup_last" to "{mine}")) {
        val files: File = tmp.newFolder()
        val sealer = SoftSealer()
        val prefs = MapPrefs(prefs)
        var serial: () -> String = { serialNo }

        init {
            for ((p, t) in mapOf("reminders.json" to "my reminders", "only-here.json" to "mine", "fonts/PowerGrotesk-Regular.ttf" to "font",
                "update/croissant-0.2.0.apk" to "apk", "photos/old.jpg" to "my photo")) {
                File(files, p).apply { parentFile?.mkdirs(); writeText(t) }
            }
            assertNull(KeysStore(File(files, "keys.json"), sealer).save("openai", openai))
        }

        fun put(zip: File, name: String = zip.name) = zip.copyTo(File(files, "restore/$name"))
        fun run() = RestoreApplier(files, sealer, prefs, app) { serial() }.run()
        fun text(p: String) = File(files, p).readText()
        fun keys() = KeysStore(File(files, "keys.json"), sealer)

        /** Every file but the outcome and the restore folder, with its text (keys.json, sealed, by its providers). */
        fun snapshot(): Map<String, String> = files.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(files).invariantSeparatorsPath }
            .filter { it != "restore-outcome.json" && !it.startsWith("restore/") }
            .associateWith { if (it == "keys.json") keys().providers.sorted().joinToString() else text(it) }

        /** Nothing left in restore/: no zip, no staging, no aside, no journal. */
        fun restoreEmpty() = File(files, "restore").listFiles().orEmpty().isEmpty()
    }

    // ---- a good restore -----------------------------------------------------------

    @Test fun aGoodRestoreReplacesTheFilesTheKeysAndThePrefs() {
        val t = Target()
        t.put(archive())
        val o = t.run()!!
        assertTrue(o.error, o.ok)
        assertEquals("2026-11-01-1402.zip", o.name)
        assertEquals("their reminders", t.text("reminders.json"))
        assertEquals("their photo", t.text("photos/p1.jpg"))
        assertEquals("their battery", t.text("battery.jsonl"))
        assertFalse(File(t.files, "only-here.json").exists())
        assertFalse(File(t.files, "photos/old.jpg").exists())
        // This install's own stay.
        assertEquals("font", t.text("fonts/PowerGrotesk-Regular.ttf"))
        assertEquals("apk", t.text("update/croissant-0.2.0.apk"))
        // The archive's keys, sealed under this install's keystore; the old ones gone.
        assertEquals(setOf("gemini"), t.keys().providers)
        assertEquals(gemini, t.keys().key("gemini"))
        assertFalse(gemini in t.text("keys.json"))
        // Every setting typed as it was, but this install's update and backup state.
        val p = t.prefs.values
        assertEquals(40, p["volume"])
        assertEquals(5_000_000_000L, p["practice_ms"])
        assertEquals(0.5f, p["gain"])
        assertEquals(true, p["muted"])
        assertEquals(setOf("a", "b"), p["tags"])
        assertEquals("[{\"id\":\"p1\"}]", p["weather_places"])
        assertEquals(true, p["update_daily"])
        assertEquals("{mine}", p["backup_last"])
        // No server here: the archive's comes in.
        assertEquals("100.100.100.1", p["server_host"])
        assertEquals("theirs", p["server_token"])
        assertEquals(o, RestoreOutcome.read(File(t.files, "restore-outcome.json")))
        assertTrue(t.restoreEmpty())
    }

    @Test fun nothingWaitingIsNoOutcome() {
        val t = Target()
        assertNull(t.run())
        File(t.files, "restore").mkdirs()
        assertNull(t.run())
        assertFalse(File(t.files, "restore-outcome.json").exists())
    }

    // ---- refused, nothing changed -------------------------------------------------

    private fun assertRefused(t: Target, vararg said: String) {
        val before = t.snapshot()
        val prefs = t.prefs.values
        val o = t.run()!!
        assertFalse(o.ok)
        for (s in said) assertTrue("\"$s\" not in: ${o.error}", s in o.error.orEmpty())
        assertEquals(before, t.snapshot())
        assertEquals(prefs, t.prefs.values)
        assertEquals(openai, t.keys().key("openai"))
        assertEquals(o, RestoreOutcome.read(File(t.files, "restore-outcome.json")))
        // Deleted all the same, so it is never tried again at the next start.
        assertTrue(t.restoreEmpty())
    }

    @Test fun anotherAppsBackupIsRefused() {
        val t = Target()
        t.put(archive(origin = Origin(AppVersion("com.example.fork", "0.1.0", 100), here, "2026-11-01T14:02:31+01:00")))
        assertRefused(t, "com.example.fork", app.id)
    }

    @Test fun aBackupFromANewerVersionIsRefusedWithBothVersions() {
        val t = Target()
        t.put(archive(origin = Origin(AppVersion(app.id, "0.2.0", 200), here, "2026-11-01T14:02:31+01:00")))
        assertRefused(t, "0.2.0 (200)", "0.1.0 (100)")
    }

    @Test fun anOlderVersionsBackupIsTaken() {
        val t = Target()
        t.put(archive(origin = Origin(AppVersion(app.id, "0.0.9", 9), here, "2026-11-01T14:02:31+01:00")))
        assertTrue(t.run()!!.ok)
    }

    @Test fun aDamagedEntryIsRefusedByName() {
        val t = Target()
        val good = archive()
        t.put(rewrite(good) { name, bytes -> if (name == "files/reminders.json") "tampered".toByteArray() else bytes })
        assertRefused(t, "files/reminders.json", "damaged")
    }

    @Test fun anEntryNotInTheManifestIsRefused() {
        val t = Target()
        t.put(rewrite(archive(), extra = "files/stowaway.json" to "{}".toByteArray()))
        assertRefused(t, "files/stowaway.json", "not in its manifest")
    }

    @Test fun anEntryThatWouldLandOutsideIsRefused() {
        for (evil in listOf("files/../x", "../x", "/x", "files/a/../../x", "files\\..\\x")) {
            val t = Target()
            t.put(craft(mapOf(evil to "boo".toByteArray())))
            assertRefused(t, evil)
            assertFalse(File(t.files.parentFile, "x").exists())
            assertFalse(File(t.files, "x").exists())
        }
    }

    @Test fun anEntryABackupNeverCarriesIsRefused() {
        val t = Target()
        t.put(craft(mapOf("files/fonts/evil.ttf" to "font".toByteArray())))
        assertRefused(t, "files/fonts/evil.ttf")
    }

    @Test fun aNameThatIsNotABackupsIsRefused() {
        val t = Target()
        t.put(archive(), name = "restore-me.zip")
        assertRefused(t, "restore-me.zip")
    }

    @Test fun anUnreadableSerialRefuses() {
        val t = Target()
        t.serial = { throw SecurityException("getSerial: no READ_PHONE_STATE") }
        t.put(archive())
        assertRefused(t, "serial", "READ_PHONE_STATE")
    }

    @Test fun twoZipsAreBothRefused() {
        val t = Target()
        t.put(archive())
        t.put(archive(name = "2026-11-01-1403.zip"))
        assertRefused(t, "2026-11-01-1402.zip", "2026-11-01-1403.zip")
    }

    // ---- failures part way --------------------------------------------------------

    @Test fun aFailurePartWayPutsTheOldFilesAndPrefsBack() {
        val t = Target()
        t.prefs.failures = 1
        t.put(archive())
        val before = t.snapshot()
        val prefs = t.prefs.values
        val o = t.run()!!
        assertFalse(o.ok)
        assertTrue(o.error, "the settings file could not be written" in o.error!!)
        assertTrue(o.error, "put back" in o.error!!)
        assertEquals(before, t.snapshot())
        assertEquals(prefs, t.prefs.values)
        assertEquals(openai, t.keys().key("openai"))
        assertTrue(t.restoreEmpty())
    }

    @Test fun aCrashPartWayIsRolledBackAtTheNextStart() {
        val t = Target()
        t.prefs.crash = true
        t.put(archive())
        val before = t.snapshot()
        val prefs = t.prefs.values
        assertThrows(Crash::class.java) { t.run() }
        // Killed with the archive's files in and its prefs written.
        assertEquals("their reminders", t.text("reminders.json"))
        assertEquals(40, t.prefs.values["volume"])
        assertTrue(File(t.files, "restore/journal.json").exists())

        t.prefs.crash = false
        val o = t.run()!!
        assertFalse(o.ok)
        assertTrue(o.error, "stopped part way" in o.error!!)
        assertEquals("2026-11-01-1402.zip", o.name)
        assertEquals(before, t.snapshot())
        assertEquals(prefs, t.prefs.values)
        assertEquals(openai, t.keys().key("openai"))
        assertTrue(t.restoreEmpty())
        // The start after that has nothing to do.
        assertNull(t.run())
    }

    @Test fun aRollbackThatFailsTooIsFinishedAtTheNextStartWithoutLosingAnOldFile() {
        val t = Target()
        // The restore's write fails, and the rollback's too, after the old files are back.
        t.prefs.failures = 2
        t.put(archive())
        val before = t.snapshot()
        val prefs = t.prefs.values
        val o = t.run()!!
        assertFalse(o.ok)
        assertTrue(o.error, "the next start tries again" in o.error!!)
        assertEquals(before, t.snapshot())
        assertTrue(File(t.files, "restore/journal.json").exists())

        val again = t.run()!!
        assertFalse(again.ok)
        assertTrue(again.error, "put back" in again.error!!)
        assertEquals(before, t.snapshot())
        assertEquals(prefs, t.prefs.values)
        assertEquals(openai, t.keys().key("openai"))
        assertTrue(t.restoreEmpty())
    }

    // ---- what stays ---------------------------------------------------------------

    @Test fun anotherC1KeepsItsOwnBatteryLogAndScores() {
        val t = Target(serialNo = "TEST-C1-TWO")
        for ((p, s) in mapOf("battery.jsonl" to "my battery", "battery-days.jsonl" to "my days", "scores.json" to "my scores")) {
            File(t.files, p).writeText(s)
        }
        t.put(archive())
        assertTrue(t.run()!!.ok)
        assertEquals("their reminders", t.text("reminders.json"))
        assertEquals("my battery", t.text("battery.jsonl"))
        assertEquals("my days", t.text("battery-days.jsonl"))
        assertEquals("my scores", t.text("scores.json"))
    }

    @Test fun anotherC1WithNoBatteryLogDoesNotTakeTheArchives() {
        val t = Target(serialNo = "TEST-C1-TWO")
        t.put(archive())
        assertTrue(t.run()!!.ok)
        assertFalse(File(t.files, "battery.jsonl").exists())
        assertFalse(File(t.files, "scores.json").exists())
    }

    @Test fun theServerIsKeptWhenThisInstallHasOne() {
        val t = Target(prefs = mapOf("server_host" to "100.100.100.2", "server_port" to 8765, "server_token" to "mine", "volume" to 10))
        t.put(archive())
        assertTrue(t.run()!!.ok)
        val p = t.prefs.values
        assertEquals("100.100.100.2", p["server_host"])
        assertEquals(8765, p["server_port"])
        assertEquals("mine", p["server_token"])
        assertEquals(40, p["volume"])
    }

    @Test fun theMergeRuleOnItsOwn() {
        val archive = mapOf("server_host" to "100.100.100.1", "update_source" to "a/b", "backup_last" to "x", "volume" to 40)
        assertEquals(mapOf("server_host" to "100.100.100.1", "volume" to 40, "update_daily" to true),
            RestoreApplier.merged(mapOf("update_daily" to true, "server_host" to ""), archive))
        assertEquals(mapOf("server_host" to "100.100.100.2", "volume" to 40),
            RestoreApplier.merged(mapOf("server_host" to "100.100.100.2"), archive))
    }

    // ---- archives made by hand ----------------------------------------------------

    /** [zip] written again with each entry through [change] (the manifest as it was), and [extra] added unlisted. */
    private fun rewrite(zip: File, extra: Pair<String, ByteArray>? = null, change: (String, ByteArray) -> ByteArray = { _, b -> b }): File {
        val out = File(tmp.newFolder(), zip.name)
        ZipFile(zip).use { z ->
            ZipOutputStream(out.outputStream()).use { o ->
                for (e in z.entries()) {
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    o.putNextEntry(ZipEntry(e.name)); o.write(if (e.name == BackupConfig.MANIFEST) bytes else change(e.name, bytes)); o.closeEntry()
                }
                extra?.let { (n, b) -> o.putNextEntry(ZipEntry(n)); o.write(b); o.closeEntry() }
            }
        }
        return out
    }

    /** A zip of prefs, keys and [entries], every one listed with its right hash. */
    private fun craft(entries: Map<String, ByteArray>): File {
        val all = mapOf(
            BackupConfig.PREFS to TypedPrefs.encode(mapOf("volume" to 40)).toString().toByteArray(),
            BackupConfig.KEYS to Archive.keysJson(emptyMap()).toByteArray(),
        ) + entries
        val listed = all.mapValues { (_, b) -> Archive.hex(Archive.sha256().digest(b)) }
        val out = File(tmp.newFolder(), "2026-11-01-1402.zip")
        ZipOutputStream(out.outputStream()).use { o ->
            for ((n, b) in all + (BackupConfig.MANIFEST to Manifest(app.id, app.version, app.versionCode, here, "2026-11-01T14:02:31+01:00", listed).toJson().toByteArray())) {
                o.putNextEntry(ZipEntry(n)); o.write(b); o.closeEntry()
            }
        }
        return out
    }
}
