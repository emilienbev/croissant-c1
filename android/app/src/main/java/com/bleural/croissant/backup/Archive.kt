package com.bleural.croissant.backup

import com.bleural.croissant.backup.BackupConfig.FILES
import com.bleural.croissant.backup.BackupConfig.FORMAT
import com.bleural.croissant.backup.BackupConfig.KEYS_VERSION
import com.bleural.croissant.backup.BackupConfig.NOT_CARRIED
import com.bleural.croissant.backup.BackupConfig.TMP
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** This app as a manifest names it, and as a restore checks an archive against. */
data class AppVersion(val id: String, val version: String, val versionCode: Int)

/** An archive this app will not apply, with why: the entry, the field or both versions. */
class Unfit(message: String) : Exception(message)

/**
 * An archive's manifest.json: the app and version that made it, the C1's serial, when, and
 * every other entry with its sha256. Nothing is in the zip that is not listed here.
 */
data class Manifest(
    val app: String,
    val version: String,
    val versionCode: Int,
    val serial: String,
    val date: String,
    val files: Map<String, String>,
    val format: Int = FORMAT,
) {
    fun toJson(): String = JSONObject()
        .put("format", format).put("app", app).put("version", version).put("versionCode", versionCode)
        .put("serial", serial).put("date", date)
        .put("files", JSONObject().apply { for ((name, sha) in files) put(name, sha) })
        .toString(2)

    companion object {
        /** Throws [Unfit] naming the field that is missing or of the wrong type. */
        fun parse(text: String): Manifest {
            val o = try { JSONObject(text) } catch (e: JSONException) { throw Unfit("manifest.json is not JSON: ${e.message}") }
            fun string(k: String) = o.opt(k) as? String ?: throw Unfit("manifest.json has no $k as text (${shown(o.opt(k))})")
            fun int(k: String) = Json.integral(o.opt(k))?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                ?: throw Unfit("manifest.json has no $k as a whole number (${shown(o.opt(k))})")
            val f = o.opt("files") as? JSONObject ?: throw Unfit("manifest.json has no files object")
            val files = f.keys().asSequence().associateWith { k ->
                f.opt(k) as? String ?: throw Unfit("manifest.json lists $k with no sha256")
            }
            return Manifest(string("app"), string("version"), int("versionCode"), string("serial"), string("date"), files, int("format"))
        }

        private fun shown(v: Any?): String = if (v == null) "missing" else v.toString().take(40)
    }
}

/** The archive's rules every part shares: what is carried, which names are safe, the keys' file. */
object Archive {

    /** Whether the file at [path] (relative to filesDir, `/` separated) goes in an archive. */
    fun carried(path: String): Boolean =
        path.substringBefore('/') !in NOT_CARRIED && !path.substringAfterLast('/').endsWith(TMP)

    /**
     * Whether [name] stays inside the folder it is unpacked into: relative, `/` separated,
     * no empty, `.` or `..` part, no backslash or NUL.
     */
    fun safe(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && '\u0000' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** An entry name an archive may hold besides the manifest: prefs, keys, or a carried file. */
    fun allowed(name: String): Boolean = name == BackupConfig.PREFS || name == BackupConfig.KEYS ||
        (name.startsWith(FILES) && carried(name.removePrefix(FILES)))

    /** The provider keys, opened: `{"version": 1, "keys": {"openai": "sk-..."}}`. */
    fun keysJson(keys: Map<String, String>): String = JSONObject().put("version", KEYS_VERSION)
        .put("keys", JSONObject().apply { for ((id, key) in keys) put(id, key) }).toString(2)

    /** Throws [Unfit] naming the provider, never the key. */
    fun parseKeys(text: String): Map<String, String> {
        val o = try { JSONObject(text) } catch (e: JSONException) { throw Unfit("keys.json is not JSON") }
        val version = Json.integral(o.opt("version"))
        if (version != KEYS_VERSION.toLong()) throw Unfit("keys.json is version $version, this app reads $KEYS_VERSION")
        val k = o.opt("keys") as? JSONObject ?: throw Unfit("keys.json has no keys object")
        return k.keys().asSequence().associateWith { id -> k.opt(id) as? String ?: throw Unfit("keys.json holds no text key for $id") }
    }

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }
}

/** A failure in words: ours (an [Unfit], a plain [IOException]) as they are, anything else with its class. */
internal fun reason(e: Exception): String =
    if (e is Unfit || e.javaClass == IOException::class.java) e.message.orEmpty() else "${e.javaClass.simpleName}: ${e.message}"

/** Up to [len] bytes of [input] into [buf], fewer only at its end; how many. */
internal fun readUpTo(input: InputStream, buf: ByteArray, len: Int): Int {
    var n = 0
    while (n < len) {
        val r = input.read(buf, n, len - n)
        if (r < 0) break
        n += r
    }
    return n
}

/** JSON numbers as org.json parses them, Android's and the JVM's alike. */
internal object Json {
    /** A whole number, or null for anything else (a fraction, text, missing). */
    fun integral(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is java.math.BigInteger -> if (v.bitLength() < 64) v.toLong() else null
        else -> null
    }
}
