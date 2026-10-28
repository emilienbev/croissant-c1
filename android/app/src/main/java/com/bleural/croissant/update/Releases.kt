package com.bleural.croissant.update

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/*
 * What an update is made of, apart from the network and the device: where releases come
 * from, how versions compare, what GitHub's answer says, and when installing is allowed.
 * Plain Kotlin, so it is tested on the JVM (UpdateRulesTest).
 */

/** A GitHub repository releases come from. Names are case-insensitive on GitHub. */
data class Source(val owner: String, val repo: String) {
    val slug: String get() = "$owner/$repo"

    fun same(other: Source) = slug.equals(other.slug, ignoreCase = true)

    companion object {
        private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")
        private val REPO = Regex("^[A-Za-z0-9._-]{1,100}$")

        /**
         * `owner/repo`, or a github.com link to it (with or without the scheme, `www.`, a
         * `.git` suffix, a trailing slash or further path). Null for anything else: it goes
         * into a URL path, so it is checked before it is used.
         */
        fun parse(input: String): Source? {
            var s = input.trim()
            val link = Regex("^(?:https?://)?(?:www\\.)?github\\.com/", RegexOption.IGNORE_CASE)
            val linked = link.containsMatchIn(s)
            s = s.replace(link, "")
            val parts = s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || (!linked && parts.size > 2)) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
            return Source(owner, repo)
        }
    }
}

/** X.Y.Z, as VERSION holds it and a release is tagged (`vX.Y.Z`). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    /** The versionCode Gradle gives it (android/app/build.gradle.kts). */
    val code: Int get() = major * 10000 + minor * 100 + patch

    companion object {
        private val FORM = Regex("^v?(\\d{1,4})\\.(\\d{1,2})\\.(\\d{1,2})$")
        fun parse(s: String): Version? = FORM.matchEntire(s.trim())?.destructured?.let { (a, b, c) ->
            Version(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

/** The APK of a release: where it is, how big, and its sha256 when GitHub gives it. */
data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

/**
 * A release worth offering: its version, when it came out, the first lines of its notes,
 * its APK, and the `.sha256` file beside the APK for when the asset carries no digest.
 */
data class Release(
    val version: Version,
    val published: String,
    val notes: List<String>,
    val apk: Asset,
    val shaUrl: String?,
) {
    fun toJson(): String = JSONObject()
        .put("version", version.toString())
        .put("published", published)
        .put("notes", org.json.JSONArray(notes))
        .put("apk", JSONObject().put("name", apk.name).put("url", apk.url).put("size", apk.size).put("sha256", apk.sha256 ?: ""))
        .put("shaUrl", shaUrl ?: "")
        .toString()

    companion object {
        fun fromJson(s: String): Release? = runCatching {
            val o = JSONObject(s)
            val a = o.getJSONObject("apk")
            val notes = o.getJSONArray("notes")
            Release(
                Version.parse(o.getString("version")) ?: return null,
                o.optString("published"),
                List(notes.length()) { notes.getString(it) },
                Asset(a.getString("name"), a.getString("url"), a.getLong("size"), a.optString("sha256").ifEmpty { null }),
                o.optString("shaUrl").ifEmpty { null },
            )
        }.getOrNull()
    }
}

/**
 * GitHub's `releases/latest` answer as a [Release], or null when it is not one this app can
 * take: no `vX.Y.Z` tag, or no `croissant-X.Y.Z.apk` among its assets (release.yml's names).
 */
fun parseRelease(json: String): Release? = runCatching {
    val o = JSONObject(json)
    val version = Version.parse(o.getString("tag_name")) ?: return null
    val assets = o.getJSONArray("assets")
    val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
    val apkName = "croissant-$version.apk"
    val apk = byName[apkName] ?: return null
    val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
    Release(
        version = version,
        published = o.optString("published_at"),
        notes = notesOf(o.optString("body")),
        apk = Asset(apkName, apk.getString("browser_download_url"), apk.getLong("size"), digest?.lowercase()),
        shaUrl = byName["$apkName.sha256"]?.getString("browser_download_url"),
    )
}.getOrNull()

/** The first [max] lines of a release's notes that say something, without their list marks. */
fun notesOf(body: String, max: Int = 3): List<String> = body.lineSequence()
    .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("+ ").trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .take(max)
    .toList()

/** The hex digest in a `sha256sum` line (`<hex>  <name>`), or null. */
fun shaOf(line: String): String? = line.trim().split(Regex("\\s+")).firstOrNull()
    ?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Installing needs half the battery, or a quarter on the charger: an install cut off by a flat battery is the one that must not happen. */
fun installAllowed(pct: Int, charging: Boolean): Boolean = pct >= 50 || (charging && pct >= 25)

/** The steps of an install, as the locked page lists them. */
enum class InstallStep { Checking, Installing, Compiling, Restarting }

/** Why an install did not happen. */
enum class InstallFailure {
    /** The system had no room for it. */
    NoRoom,
    /** The system turned it down. */
    Refused,
    /** The system stopped it part way. */
    Interrupted,
    /** No answer, and no new version, long after the commit. */
    TooLong,
}

/**
 * How long each step of an install usually takes on this C1. The system says nothing of its
 * progress, so the locked page's time left and its meter are timed against these.
 */
data class InstallTimes(val checkingMs: Long, val installingMs: Long, val compilingMs: Long, val restartingMs: Long) {
    fun of(step: InstallStep): Long = when (step) {
        InstallStep.Checking -> checkingMs
        InstallStep.Installing -> installingMs
        InstallStep.Compiling -> compilingMs
        InstallStep.Restarting -> restartingMs
    }

    val totalMs: Long get() = InstallStep.entries.sumOf(::of)

    /**
     * The time left [elapsedMs] into [step]: what the step usually has left, never less than
     * [floorMs] (or the whole step, if shorter), since a step that overruns is still one to
     * wait for; then every step after it.
     */
    fun leftMs(step: InstallStep, elapsedMs: Long, floorMs: Long = 3_000L): Long =
        maxOf(of(step) - elapsedMs, minOf(floorMs, of(step))) + InstallStep.entries.drop(step.ordinal + 1).sumOf(::of)

    companion object {
        /** Before this C1 has timed an install of its own: an 85 MB release, as measured on the first R1. */
        val USUAL = InstallTimes(checkingMs = 2_000L, installingMs = 9_000L, compilingMs = 35_000L, restartingMs = 3_000L)
    }
}

/**
 * A meter for a step with no progress of its own, timed against how long it usually takes:
 * steady to 90 % at [expectedMs], then slower and slower, never full; it ends when the step does.
 */
fun timedLevel(elapsedMs: Long, expectedMs: Long): Float {
    if (expectedMs <= 0L) return 0.9f
    val f = elapsedMs.coerceAtLeast(0L).toFloat() / expectedMs
    return if (f <= 1f) 0.9f * f else 0.9f + 0.08f * (1f - kotlin.math.exp(-(f - 1f) * 2f))
}

/**
 * A download's pace over the last few seconds, for its time left. Fed the bytes so far as they
 * arrive; [reset] when the download stops, since a pace from before a stop says nothing after.
 */
class Pace(private val windowMs: Long = 8_000L, private val minSpanMs: Long = 2_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(atMs: Long, bytes: Long) {
        samples.addLast(atMs to bytes)
        while (samples.size > 2 && atMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** The time left to [total] bytes at the recent pace, or null before there are [minSpanMs] of it to go by, or while nothing arrives. */
    fun leftMs(total: Long): Long? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        if (t1 - t0 < minSpanMs || b1 <= b0) return null
        val perMs = (b1 - b0).toDouble() / (t1 - t0)
        return ((total - b1).coerceAtLeast(0L) / perMs).toLong()
    }
}

/** How long to wait before the [failures]th retry of a download: soon at first, then every fifteen minutes. */
fun retryAfterMs(failures: Int): Long = when (failures) {
    0, 1 -> 10_000L
    2 -> 30_000L
    3 -> 120_000L
    4 -> 300_000L
    else -> 900_000L
}
