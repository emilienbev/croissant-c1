package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bleural.croissant.Core
import com.bleural.croissant.net.FileKind
import com.bleural.croissant.net.FolderEntry
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.Screen
import kotlinx.coroutines.CancellationException

/*
 * A session's folder on the bridge's computer, to look through from the R1 (2026-10-21): read
 * only, nothing sent anywhere. Changes go off the computer as the user pushes them; this is to
 * see what an agent made.
 */

/** A folder's entries as they were read, or why they could not be. */
private sealed interface Read {
    data object Reading : Read
    data class Got(val entries: List<FolderEntry>) : Read
    data class Failed(val why: String) : Read
}

/**
 * A folder of a session's folder: its name the header (the session's folder's for its root),
 * folders first with a chevron, then files with their size. A folder opens its own page; a
 * picture opens on the whole canvas, any other file its page by its kind (FilePage).
 */
@Composable
internal fun AgentFilesPage(agent: String, cwd: String, path: String, core: Core, nav: NavState) {
    val r = agentLook(agent).ramp
    val read by produceState<Read>(Read.Reading, cwd, path) {
        value = try {
            Read.Got(core.agents.ls(cwd, path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Read.Failed(e.message ?: "the bridge did not answer")
        }
    }
    var picture by remember(path) { mutableStateOf<String?>(null) }
    var full by remember(path) { mutableStateOf(false) }
    val word = (path.ifEmpty { cwd }).trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
    val lines = remember(read) {
        when (val x = read) {
            Read.Reading -> listOf(AgentLine.Words("reading the folder"))
            is Read.Failed -> listOf(AgentLine.Words(x.why, danger = true))
            is Read.Got -> if (x.entries.isEmpty()) listOf(AgentLine.Words("nothing in it")) else x.entries.map { e ->
                val at = if (path.isEmpty()) e.name else "$path/${e.name}"
                AgentLine.Row("e-${e.name}", e.name, value = if (e.dir) null else fileSize(e.size), chevron = e.dir, act = {
                    when {
                        e.dir -> nav.push(Screen.AgentFiles(agent, cwd, at))
                        FileKind.of(at) == FileKind.Image -> { picture = at; full = true }
                        else -> nav.push(Screen.AgentFile(agent, cwd, at))
                    }
                })
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LinesPage(word, r, lines, nav::pop)
        picture?.let { p ->
            WholePicture(r, p, full, { side -> core.agents.image(cwd, p, side) }, onDismiss = { full = false }, onSend = null)
        }
    }
}

/** A file of a session's folder, shown by its kind (FilePage), under the agent's name. */
@Composable
internal fun AgentFilePage(agent: String, cwd: String, path: String, core: Core, nav: NavState) {
    val r = agentLook(agent).ramp
    val folder = path.substringBeforeLast('/', "")
    val source = remember(cwd, path) {
        FileSource({ core.agents.text(cwd, path) }, { core.agents.ls(cwd, folder).firstOrNull { it.name == path.substringAfterLast('/') }?.size })
    }
    val name = core.agents.state.value.agents.firstOrNull { it.id == agent }?.name ?: agent
    FilePage(name, r, path, source, "the bridge did not answer", onSend = null, onBack = nav::pop)
}
