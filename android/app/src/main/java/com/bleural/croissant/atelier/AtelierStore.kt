package com.bleural.croissant.atelier

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Where an installed app came from. */
sealed interface AppSource {
    /** A GitHub repository, pinned at the commit it was installed from. */
    data class GitHub(val repo: String, val commit: String) : AppSource
    /** Pushed from a computer by `croissant atelier run`. */
    data object Local : AppSource
}

/**
 * An app as it is on the device. Its folder is [dir], whose name is its [id]. Not [enabled]:
 * kept, greyed on the board, it does not open and offers Ask nothing (a `disabled` file in its folder).
 */
data class InstalledApp(
    val manifest: AppManifest, val dir: File, val source: AppSource, val installed: Long, val enabled: Boolean = true,
) {
    val id: String get() = dir.name

    fun script(): String = File(dir, AppManifest.SCRIPT).readText()

    /** The state its last handler left, as JSON; null on the first run or if it is unreadable. */
    fun savedState(): String? = File(dir, STATE).takeIf { it.isFile }?.readText()
        ?.takeIf { runCatching { org.json.JSONTokener(it).nextValue() }.isSuccess }

    fun saveState(json: String) = writeAtomic(File(dir, STATE), json)

    companion object {
        const val STATE = "state.json"
        const val SOURCE = "source.json"
        const val DISABLED = "disabled"
    }
}

/** An app fetched and checked, not yet installed: what the install sheet asks about, with the [id] it will have. */
data class StagedApp(val id: String, val manifest: AppManifest, val script: String, val source: AppSource)

/**
 * An app's id: its folder's name, the key of its state, its pins and its tools. The device
 * gives it, never the app: from a repo's name ([of] `alice/water-log`: `water-log`) or a
 * pushed folder's, and [AtelierStore.idFor] keeps a repo's own across updates and gives
 * another repo of the same name the next free one.
 */
object AppId {
    /** So that the id, an underscore and a tool's name (up to 23) fit a tool name's 64. */
    const val MAX = 40
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,${MAX - 1}}")
    private val DASHES = Regex("-+")

    fun valid(id: String) = ID.matches(id)

    /**
     * [name] as an id: A to Z lowercased, anything else outside a-z, 0-9 and the dash a dash,
     * runs of dashes one and none at the ends, cut to [MAX]; "app" if nothing is left. The
     * CLI's `atelier.app_id` is the same, for a folder it pushes.
     */
    fun of(name: String): String = name.map { c ->
        when (c) {
            in 'A'..'Z' -> c + ('a' - 'A')
            in 'a'..'z', in '0'..'9' -> c
            else -> '-'
        }
    }.joinToString("").replace(DASHES, "-").trim('-').take(MAX).trimEnd('-').ifEmpty { "app" }

    /** [base], or the first of [base]-2, -3, ... (cut to fit [MAX]) that is not [taken]. */
    fun free(base: String, taken: (String) -> Boolean): String {
        if (!taken(base)) return base
        var n = 2
        while (true) {
            val end = "-$n"
            val id = base.take(MAX - end.length).trimEnd('-') + end
            if (!taken(id)) return id
            n++
        }
    }
}

/**
 * The installed apps, one folder each under `atelier/apps/` in the device-protected files
 * (`croissant.json`, `croissant.js`, `source.json`, `state.json`), named by the app's id. The
 * folder is the record: a folder pushed there by the CLI is an app once [scan] reads it.
 */
class AtelierStore(root: File) {
    val appsDir = File(root, "apps")

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    /** In the order they were installed, the first on the board first. */
    val apps: StateFlow<List<InstalledApp>> = _apps

    fun get(id: String): InstalledApp? = _apps.value.firstOrNull { it.id == id }

    /** Read every folder again. One that is not an app (not an id, a bad croissant.json, no croissant.js) is skipped and logged. */
    fun scan() {
        val found = appsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            read(dir).onFailure { Log.w(TAG, "${dir.name}: ${it.message}") }.getOrNull()
        }
        _apps.value = found.sortedWith(compareBy({ it.installed }, { it.id }))
    }

    /** The app installed from [repo] (`owner/repo`, in any case), if one is. */
    fun fromRepo(repo: String): InstalledApp? =
        _apps.value.firstOrNull { (it.source as? AppSource.GitHub)?.repo.equals(repo, ignoreCase = true) }

    /**
     * The id an app from [source] installs as: that of the app already installed from the same
     * repo, whatever it is (an update), else the repo's name's ([AppId.of]), or the next free
     * one past any other app's or folder's: an app from elsewhere never takes another's place.
     */
    fun idFor(source: AppSource.GitHub): String = fromRepo(source.repo)?.id
        ?: AppId.free(AppId.of(source.repo.substringAfter('/'))) { get(it) != null || File(appsDir, it).exists() }

    /** [staged] with the id it would install as now ([idFor]); one from the CLI keeps its own. */
    fun place(staged: StagedApp): StagedApp = when (val s = staged.source) {
        is AppSource.GitHub -> staged.copy(id = idFor(s))
        AppSource.Local -> staged
    }

    /** Put [staged] in its folder ([place]), keeping the state of the app it updates. */
    fun install(staged: StagedApp, now: Long = System.currentTimeMillis()): InstalledApp {
        scan()
        val id = place(staged).id
        if (!AppId.valid(id)) throw BadApp("could not install it")
        val dir = File(appsDir, id)
        val tmp = File(appsDir, ".$id.new").apply { deleteRecursively(); mkdirs() }
        val previous = get(id)
        File(dir, InstalledApp.STATE).takeIf { it.isFile }?.copyTo(File(tmp, InstalledApp.STATE))
        File(dir, InstalledApp.DISABLED).takeIf { it.isFile }?.copyTo(File(tmp, InstalledApp.DISABLED))
        writeAtomic(File(tmp, AppManifest.SCRIPT), staged.script)
        writeAtomic(File(tmp, AppManifest.FILE), encodeManifest(staged.manifest))
        writeAtomic(File(tmp, InstalledApp.SOURCE), encodeSource(staged.source, previous?.installed ?: now))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw BadApp("could not install it")
        scan()
        return get(id) ?: throw BadApp("could not install it")
    }

    /** [id] turned on or off; an update or a push from the CLI keeps it as it is. */
    fun setEnabled(id: String, on: Boolean) {
        val marker = File(File(appsDir, id), InstalledApp.DISABLED)
        if (on) marker.delete() else if (marker.parentFile?.isDirectory == true) writeAtomic(marker, "")
        scan()
    }

    fun delete(id: String) {
        File(appsDir, id).deleteRecursively()
        scan()
    }

    private fun read(dir: File): Result<InstalledApp> = runCatching {
        if (!AppId.valid(dir.name)) throw BadApp("its folder's name is not an app's id")
        val json = File(dir, AppManifest.FILE).takeIf { it.isFile }?.readText() ?: throw BadApp("no ${AppManifest.FILE}")
        val manifest = AppManifest.parse(json).getOrThrow()
        val script = File(dir, AppManifest.SCRIPT)
        if (!script.isFile) throw BadApp("no ${AppManifest.SCRIPT}")
        if (script.length() > MAX_SCRIPT) throw BadApp("${AppManifest.SCRIPT} is over ${MAX_SCRIPT / 1024} kB")
        val src = File(dir, InstalledApp.SOURCE).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val source = when (src?.optString("kind")) {
            "github" -> AppSource.GitHub(src.optString("repo"), src.optString("commit"))
            else -> AppSource.Local
        }
        val installed = src?.optLong("installed")?.takeIf { it > 0 } ?: dir.lastModified()
        InstalledApp(manifest, dir, source, installed, enabled = !File(dir, InstalledApp.DISABLED).exists())
    }

    companion object {
        private const val TAG = "Atelier"
        const val MAX_SCRIPT = 256 * 1024

        fun encodeManifest(m: AppManifest): String = JSONObject()
            .put("croissant", m.croissant).put("name", m.name).put("hue", "#%06X".format(m.hue))
            .apply { if (m.glyph != null) put("glyph", m.glyph) }
            .put("version", m.version)
            .put("uses", org.json.JSONArray(m.uses.map { it.key }))
            .apply { if (m.tools.isNotEmpty()) put("tools", org.json.JSONArray(m.tools.map { it.toJson() })) }
            .toString(2)

        fun encodeSource(s: AppSource, installed: Long): String = when (s) {
            is AppSource.GitHub -> JSONObject().put("kind", "github").put("repo", s.repo).put("commit", s.commit)
            AppSource.Local -> JSONObject().put("kind", "local")
        }.put("installed", installed).toString(2)
    }
}

/** Temp file, sync, rename: a crash leaves the old file or the new, never half of one. */
internal fun writeAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) throw BadApp("could not write ${file.name}")
}
