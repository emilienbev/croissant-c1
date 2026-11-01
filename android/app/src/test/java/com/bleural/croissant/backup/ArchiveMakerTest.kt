package com.bleural.croissant.backup

import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.Sealer
import com.bleural.croissant.ask.SoftSealer
import org.json.JSONObject
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
import java.security.ProviderException
import java.util.zip.ZipFile

/** The archive as the server and the CLI read it: the entries, the manifest, the keys opened, the prefs typed. */
class ArchiveMakerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = AppVersion("com.bleural.croissant", "0.1.0", 100)
    private val origin = Origin(app, "TEST-C1-ONE", "2026-11-01T14:02:31+01:00")
    private val gemini = "AIza00000000000000000000000000000000MNOP"

    private fun filesDir(): File = tmp.newFolder().also { d ->
        for ((p, t) in mapOf(
            "reminders.json" to "[]", "photos/p1.jpg" to "jpeg", "atelier/apps/a1/state.json" to "{}",
            // Never carried:
            "keys.json" to "sealed", "update/croissant-0.2.0.apk" to "apk", "fonts/PowerGrotesk-Regular.ttf" to "font",
            "restore/2026-11-01-1402.zip" to "zip", "backup-out/2026-11-01-1402.zip" to "zip", "restore-outcome.json" to "{}",
            "update-source" to "a/b", "sound-pack" to "zen", "reminders.json.tmp" to "half", "photos/p2.jpg.tmp" to "half",
        )) File(d, p).apply { parentFile?.mkdirs(); writeText(t) }
    }

    private fun entries(zip: File): Map<String, ByteArray> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).use { it.readBytes() } }
    }

    @Test fun itHoldsTheManifestPrefsKeysAndTheCarriedFilesEachHashed() {
        val files = filesDir()
        val keys = KeysStore(File(tmp.newFolder(), "keys.json"), SoftSealer()).apply { assertNull(save("gemini", gemini)) }
        val made = ArchiveMaker(files).make(File(tmp.newFolder(), "2026-11-01-1402.zip"), mapOf("volume" to 40), keys, origin)
        val e = entries(made.file)
        assertEquals(setOf("manifest.json", "prefs.json", "keys.json", "files/reminders.json", "files/photos/p1.jpg",
            "files/atelier/apps/a1/state.json"), e.keys)
        val m = Manifest.parse(String(e.getValue("manifest.json")))
        assertEquals(Manifest(app.id, "0.1.0", 100, "TEST-C1-ONE", "2026-11-01T14:02:31+01:00", m.files), m)
        assertEquals(e.keys - "manifest.json", m.files.keys)
        for ((n, sha) in m.files) assertEquals(n, Archive.hex(Archive.sha256().digest(e.getValue(n))), sha)
        assertEquals(mapOf("gemini" to gemini), Archive.parseKeys(String(e.getValue("keys.json"))))
        // The whole file's size and sha256, as an upload announces them.
        assertEquals(made.file.length(), made.size)
        assertEquals(Archive.hex(Archive.sha256().digest(made.file.readBytes())), made.sha256)
        assertFalse(File(made.file.path + ".tmp").exists())
    }

    @Test fun theManifestTypesAreTheOnesTheServerChecks() {
        val made = ArchiveMaker(filesDir()).make(File(tmp.newFolder(), "a.zip"), emptyMap(), KeysStore(File(tmp.newFolder(), "k.json"), SoftSealer()), origin)
        val o = JSONObject(String(entries(made.file).getValue("manifest.json")))
        assertTrue(o.get("versionCode") is Int)
        assertTrue(o.get("format") is Int)
        for (k in listOf("app", "version", "serial", "date")) assertTrue(k, o.get(k) is String)
    }

    @Test fun aKeyThatCannotBeOpenedStopsTheBackupWithoutTheKey() {
        val f = File(tmp.newFolder(), "keys.json")
        KeysStore(f, SoftSealer()).save("gemini", gemini)
        val lost = object : Sealer {
            override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> = throw ProviderException("no")
            override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray = throw ProviderException("Keystore key gone")
        }
        val out = File(tmp.newFolder(), "a.zip")
        val e = assertThrows(IOException::class.java) { ArchiveMaker(filesDir()).make(out, emptyMap(), KeysStore(f, lost), origin) }
        assertTrue(e.message, "gemini" in e.message!!)
        assertFalse(gemini in e.message!!)
        assertFalse(out.exists())
    }

    @Test fun everySettingTypeGoesBackAsItsOwnType() {
        val values = mapOf("s" to "text", "i" to 40, "l" to 5_000_000_000L, "f" to 0.1f, "b" to false, "set" to setOf("x", "y"))
        val text = TypedPrefs.encode(values).toString(2)
        assertEquals(values, TypedPrefs.decode(JSONObject(text)))
        assertEquals("int", JSONObject(text).getJSONObject("i").getString("type"))
        assertEquals("strings", JSONObject(text).getJSONObject("set").getString("type"))
    }

    @Test fun aTypedValueThatIsNotItsTypeIsRefusedByKey() {
        for (bad in listOf("""{"volume": {"type": "int", "value": "40"}}""", """{"volume": {"type": "int", "value": 4.5}}""",
            """{"volume": {"type": "double", "value": 4}}""", """{"volume": 40}""")) {
            val e = assertThrows(Unfit::class.java) { TypedPrefs.decode(JSONObject(bad)) }
            assertTrue(e.message, "volume" in e.message!!)
        }
    }

    @Test fun namesAndPaths() {
        for (n in listOf("2026-11-01-1402.zip", "2026-11-01-1402-2.zip", "2026-11-01-1402-10.zip")) assertTrue(n, BackupConfig.NAME.matches(n))
        for (n in listOf("2026-11-01-1402", "2026-11-01.zip", "../2026-11-01-1402.zip", "2026-11-01-1402.zip.tmp")) assertFalse(n, BackupConfig.NAME.matches(n))
        assertTrue(Archive.safe("files/photos/p1.jpg"))
        for (n in listOf("", "/x", "../x", "files/../x", "files//x", "files/./x", "files\\x", "files/")) assertFalse(n, Archive.safe(n))
        assertTrue(Archive.carried("photos/p1.jpg"))
        for (p in listOf("keys.json", "update/x.apk", "fonts/a.ttf", "restore/a.zip", "backup-out/a.zip", "restore-outcome.json",
            "update-source", "sound-pack", "a.json.tmp", "photos/a.jpg.tmp")) assertFalse(p, Archive.carried(p))
    }
}
