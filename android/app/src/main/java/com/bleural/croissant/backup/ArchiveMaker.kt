package com.bleural.croissant.backup

import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.backup.BackupConfig.COPY_BUFFER
import com.bleural.croissant.backup.BackupConfig.FILES
import com.bleural.croissant.backup.BackupConfig.KEYS
import com.bleural.croissant.backup.BackupConfig.MANIFEST
import com.bleural.croissant.backup.BackupConfig.PREFS
import com.bleural.croissant.backup.BackupConfig.TMP
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An archive written: where, its size and its sha256, what an upload announces. */
data class Made(val file: File, val size: Long, val sha256: String)

/** Who made an archive: the app, this C1's serial, and when (ISO with offset). */
data class Origin(val app: AppVersion, val serial: String, val date: String)

/**
 * Writes the archive of a C1: prefs.json (every setting, typed), keys.json (the provider
 * keys, opened through [KeysStore]), the device-protected filesDir under `files/` (what
 * [Archive.carried] lets in), and manifest.json with every entry's sha256. The manifest goes
 * last, since the hashes are known only once the entries are written.
 */
class ArchiveMaker(private val files: File) {

    /**
     * The archive into [out], whole or not at all (a temp file, then a rename). Throws
     * [IOException] naming what failed: a key that cannot be opened, a file that cannot be read.
     */
    fun make(out: File, prefs: Map<String, Any?>, keys: KeysStore, origin: Origin): Made {
        val opened = keys.providers.sorted().associateWith { id ->
            keys.key(id) ?: throw IOException("the key for $id went while the backup was made")
        }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + TMP)
        val whole = Archive.sha256()
        val hashes = LinkedHashMap<String, String>()
        try {
            FileOutputStream(tmp).use { fos ->
                val zip = ZipOutputStream(DigestOutputStream(BufferedOutputStream(fos), whole))
                hashes[PREFS] = put(zip, PREFS, TypedPrefs.encode(prefs).toString(2).toByteArray().inputStream())
                hashes[KEYS] = put(zip, KEYS, Archive.keysJson(opened).toByteArray().inputStream())
                for ((path, file) in carriedFiles()) {
                    val name = FILES + path
                    hashes[name] = try {
                        file.inputStream().use { put(zip, name, it, file.lastModified()) }
                    } catch (e: IOException) {
                        throw IOException("$name could not be copied into the archive: ${e.message}")
                    }
                }
                val m = Manifest(origin.app.id, origin.app.version, origin.app.versionCode, origin.serial, origin.date, hashes)
                put(zip, MANIFEST, m.toJson().toByteArray().inputStream())
                zip.finish()
                zip.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(out)) throw IOException("could not rename ${tmp.path} to ${out.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        return Made(out, out.length(), Archive.hex(whole.digest()))
    }

    /** Every carried file under filesDir, by its `/` path, in path order. */
    private fun carriedFiles(): List<Pair<String, File>> = files.walkTopDown()
        .onEnter { it == files || Archive.carried(rel(it)) }
        .filter { it.isFile && Archive.carried(rel(it)) }
        .map { rel(it) to it }
        .sortedBy { it.first }
        .toList()

    private fun rel(f: File) = f.relativeTo(files).invariantSeparatorsPath

    /** One entry from [input]; its sha256. */
    private fun put(zip: ZipOutputStream, name: String, input: InputStream, time: Long = System.currentTimeMillis()): String {
        val sha = Archive.sha256()
        zip.putNextEntry(ZipEntry(name).apply { this.time = time })
        val buf = ByteArray(COPY_BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sha.update(buf, 0, n)
            zip.write(buf, 0, n)
        }
        zip.closeEntry()
        return Archive.hex(sha.digest())
    }
}
