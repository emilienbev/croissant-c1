package com.bleural.croissant.atelier

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Atelier: small apps anyone writes, run on the device. The installed apps, adding one
 * from GitHub, and the one open on screen.
 *
 * An app is a folder (`croissant.json`, `croissant.js`) and nothing else, named by the id the
 * device gives it ([AppId]); the design draws its pieces.
 * `croissant atelier run` pushes a folder from a computer and writes [RELOAD], and the app
 * open on screen starts again on the new script.
 *
 * [reserved] are the names an app's tool cannot take: Ask's own (data/AskCatalog.kt) and
 * the ones the server calls on the device, so an app can never answer for them.
 */
class Atelier(
    context: Context,
    private val scope: CoroutineScope,
    private val bridge: Bridge,
    userAgent: String,
    private val reserved: Set<String> = emptySet(),
) {
    private val root = File(context.createDeviceProtectedStorageContext().filesDir, "atelier")
    val store = AtelierStore(root)
    val apps: StateFlow<List<InstalledApp>> get() = store.apps

    private val prelude: String by lazy { context.assets.open(PRELUDE).bufferedReader().use { it.readText() } }
    /** Every app's script runs in the isolated engine process, never in this one (AtelierService.kt). */
    private val engines = IsolatedEngines(context, scope)

    private val github = GitHubSource(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
        userAgent,
    )

    init {
        scope.launch { withContext(Dispatchers.IO) { store.scan() } }
    }

    // ---- adding ---------------------------------------------------------------

    sealed interface Adding {
        data object Idle : Adding
        data class Finding(val repo: RepoRef) : Adding
        /** Fetched and checked: the install sheet asks about it. */
        data class Found(val app: StagedApp) : Adding
        data class Failed(val input: String, val why: String) : Adding
    }

    private val _adding = MutableStateFlow<Adding>(Adding.Idle)
    val adding: StateFlow<Adding> = _adding
    private var finding: Job? = null

    /** [input] as typed or scanned: `owner/repo`, `owner/repo@ref`, or the repo's URL. */
    fun find(input: String) {
        val repo = RepoRef.parse(input)
        if (repo == null) {
            _adding.value = Adding.Failed(input, "type it as owner/repo")
            return
        }
        finding?.cancel()
        _adding.value = Adding.Finding(repo)
        finding = scope.launch {
            _adding.value = try {
                val staged = github.fetch(repo)
                Adding.Found(withContext(Dispatchers.IO) { store.place(staged) })
            } catch (e: AddFailure) {
                Adding.Failed(input, e.message ?: "it could not be fetched")
            }
        }
    }

    /** The found app, installed (an update keeps its state); the add flow ends. */
    suspend fun install(): InstalledApp? {
        val found = _adding.value as? Adding.Found ?: return null
        val app = withContext(Dispatchers.IO) { store.install(found.app) }
        _adding.value = Adding.Idle
        if (_open.value?.app?.id == app.id) open(app.id)
        return app
    }

    /** Leave the add flow: the sheet cancelled, or the add page gone. */
    fun dismiss() {
        finding?.cancel()
        _adding.value = Adding.Idle
    }

    /** Turn [id] off (greyed, does not open, no tools) or on again. Off, its page closes if it is up. */
    fun setEnabled(id: String, on: Boolean) {
        if (!on && _open.value?.app?.id == id) open(null)
        scope.launch { withContext(Dispatchers.IO) { store.setEnabled(id, on) } }
    }

    fun delete(id: String) {
        if (_open.value?.app?.id == id) open(null)
        scope.launch { withContext(Dispatchers.IO) { store.delete(id) } }
    }

    // ---- the open app ------------------------------------------------------------

    private val _open = MutableStateFlow<AppRuntime?>(null)
    /** The app whose page is on screen, running; null when none is. */
    val open: StateFlow<AppRuntime?> = _open

    /** The open app has an `onWords` (and its page is drawn): a hold on it brings it the words. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val wantsWords: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        scope.launch {
            _open.flatMapLatest { rt -> rt?.view?.map { (it as? AppRuntime.View.Shown)?.page?.takesWords == true } ?: flowOf(false) }
                .collect { out.value = it }
        }
    }

    /** [id]'s page came up (its app starts), or null: the page went (the app stops). */
    fun open(id: String?) {
        val app = id?.let(store::get)?.takeIf { it.enabled }
        _open.value?.close()
        _open.value = app?.let { AppRuntime(it, prelude, bridge, scope, engines).also(AppRuntime::start) }
    }

    // ---- the apps' tools, for Ask ---------------------------------------------------------

    /** One app's tool as Ask sees it: [wire] its name to the model, the app's id and the tool's. */
    data class OfferedTool(val wire: String, val app: InstalledApp, val tool: AppTool)

    /** The tools the enabled apps offer Ask (croissant.json's `tools`); a disabled app's are not, nor one named like Ask's own. */
    fun offeredTools(): List<OfferedTool> = apps.value.filter { it.enabled }.flatMap { a ->
        a.manifest.tools.map { t -> OfferedTool(wire(a.id, t.name), a, t) }
    }.filter { it.wire !in reserved }

    /**
     * Ask called [wire] with [args]: the app's page comes up ([show], the design's), the app
     * starts if it was not running, its `onTool` runs there, and its reply comes back as
     * JSON. Null when [wire] is no app's tool. Throws [BadApp] with why when the app fails.
     */
    suspend fun callTool(wire: String, args: JSONObject, show: (String) -> Unit): String? {
        val offered = offeredTools().firstOrNull { it.wire == wire } ?: return null
        val id = offered.app.id
        val rt = withContext(Dispatchers.Main) {
            if (_open.value?.app?.id != id) open(id)
            show(id)
            _open.value
        } ?: throw BadApp("${offered.app.manifest.name} could not open")
        return rt.callTool(offered.tool.name, args.toString())
    }

    /** A hold's words, for the open app; false when it takes none. Safe from any thread. */
    fun hear(words: String): Boolean = _open.value?.hear(words) ?: false

    // ---- the CLI ---------------------------------------------------------------------

    /** `croissant atelier run` writes this after pushing an app's folder. */
    private val watcher = object : FileObserver(root, CLOSE_WRITE or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            if (path != RELOAD) return
            scope.launch {
                withContext(Dispatchers.IO) { store.scan() }
                val reloaded = runCatching { File(root, RELOAD).readText().trim() }.getOrNull()
                val open = _open.value?.app?.id
                if (open != null && (reloaded.isNullOrEmpty() || reloaded == open)) open(open)
            }
        }
    }

    init {
        root.mkdirs()
        watcher.startWatching()
    }

    companion object {
        const val PRELUDE = "atelier/prelude.js"
        /** A file the CLI writes into `atelier/`, holding the id it pushed. */
        const val RELOAD = "reload"

        /** App [app]'s tool [tool] by the name the model reads: the app's id, an underscore, the tool's own. */
        fun wire(app: String, tool: String): String = "${app}_$tool"
    }
}
