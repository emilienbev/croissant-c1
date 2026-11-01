package com.bleural.croissant.backup

import com.bleural.croissant.Prefs
import com.bleural.croissant.backup.BackupConfig.CHUNK_BYTES
import com.bleural.croissant.backup.BackupConfig.CONNECT_TIMEOUT_MS
import com.bleural.croissant.backup.BackupConfig.COPY_BUFFER
import com.bleural.croissant.backup.BackupConfig.PROGRESS_MS
import com.bleural.croissant.backup.BackupConfig.READ_TIMEOUT_MS
import com.bleural.croissant.backup.BackupConfig.REPLY_TIMEOUT_MS
import com.bleural.croissant.backup.BackupConfig.ROOM_FACTOR
import com.bleural.croissant.backup.BackupConfig.TMP
import com.bleural.croissant.backup.BackupConfig.ZIP
import com.bleural.croissant.net.ServerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** What came of a call to the server: its value, or the problem the page shows. */
internal sealed interface Answer<out T> {
    data class Done<T>(val value: T) : Answer<T>
    data class Failed(val problem: BackupProblem) : Answer<Nothing>
}

/** Where the server kept an upload: the file's name and its folder, as the server names them. */
internal data class Kept(val name: String, val folder: String)

/**
 * croissant-server's backups (server/croissant_server/backups.py): the list (`GET /backups`),
 * an upload (WebSocket `/backup`, a `begin`, then chunks each answered `got` before the next
 * goes, then `saved`) and a fetch (`GET /backups/<name>`). Every request carries the LAN
 * token (net/ServerAuth.kt). An old server (no `/backups`, `/backup` closed as an unknown
 * path) is told apart from one that does not answer and from one that wants its code.
 * Cancelling the coroutine stops a call at once; an upload cancelled closes its socket, and
 * the server drops the part.
 */
internal class BackupClient(private val server: Prefs.Server) {
    private val machine = server.label
    private val address = "${server.host}:${server.port}"
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    // An upload waits for each frame with its own timeout ([REPLY_TIMEOUT_MS]).
    private val sockets = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** A stop with the page's problem, out of the middle of an exchange. */
    private class Stopped(val problem: BackupProblem) : Exception()

    private sealed interface Event {
        data object Open : Event
        data class Text(val text: String) : Event
        data class Closed(val code: Int, val reason: String) : Event
        data class Failed(val error: Throwable, val code: Int?) : Event
    }

    suspend fun list(appCode: Int): Answer<ServerList> {
        val call = client.newCall(get("/backups"))
        return stopped {
            cancellable(call) {
                val resp = try { call.execute() } catch (e: IOException) { throw Stopped(unreachable(e)) }
                resp.use { r ->
                    if (r.code != 200) throw Stopped(refusal(r, listing = true))
                    val body = try { r.body.string() } catch (e: IOException) { throw Stopped(dropped(e)) }
                    try {
                        Answer.Done(BackupWire.list(body, appCode))
                    } catch (e: JSONException) {
                        throw Stopped(BackupProblem.ServerSaid(machine, "its list of backups cannot be read: ${e.message}"))
                    }
                }
            }
        }
    }

    /** [made] up to the server; [progress] gets the bytes it has taken, after each `got`. */
    suspend fun upload(made: Made, progress: (Long) -> Unit): Answer<Kept> = withContext(Dispatchers.IO) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val ws = sockets.newWebSocket(get("/backup"), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Event.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Event.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed(code, reason)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed(code, reason)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(Event.Failed(t, response?.code)) }
        })
        var kept = false
        try {
            stopped { FileInputStream(made.file).use { exchange(ws, events, it, made, progress) } }.also { kept = it is Answer.Done }
        } finally {
            // A socket closed before `saved` is how the server is told to drop the part.
            if (kept) ws.close(1000, null) else ws.cancel()
        }
    }

    private suspend fun exchange(ws: WebSocket, events: Channel<Event>, input: InputStream, made: Made, progress: (Long) -> Unit): Answer<Kept> {
        when (val e = withTimeoutOrNull(CONNECT_TIMEOUT_MS + REPLY_TIMEOUT_MS) { events.receive() }) {
            Event.Open -> Unit
            is Event.Failed -> throw Stopped(when {
                e.code == ServerAuth.REFUSED -> BackupProblem.NeedsCode(machine)
                e.code != null -> BackupProblem.ServerSaid(machine, "it answered ${e.code} to an upload")
                else -> BackupProblem.Unreachable(machine, address, describe(e.error))
            })
            is Event.Closed -> throw Stopped(BackupWire.closed(e.code, e.reason, ready = false, machine))
            is Event.Text -> throw Stopped(BackupProblem.ServerSaid(machine, "it spoke before the connection opened"))
            null -> throw Stopped(BackupProblem.Unreachable(machine, address, "no answer in ${(CONNECT_TIMEOUT_MS + REPLY_TIMEOUT_MS) / 1000} s"))
        }
        if (!ws.send(BackupWire.begin(made.size, made.sha256))) throw Stopped(BackupProblem.Dropped(machine, "the begin frame could not be sent"))
        when (val r = next(events, ready = false)) {
            is Reply.Ready -> Unit
            is Reply.Error -> throw Stopped(BackupWire.problem(r.message, machine))
            else -> throw Stopped(unexpected(r, "ready"))
        }
        var sent = 0L
        val buf = ByteArray(CHUNK_BYTES)
        while (sent < made.size) {
            val n = readUpTo(input, buf, minOf(CHUNK_BYTES.toLong(), made.size - sent).toInt())
            if (n <= 0) throw Stopped(BackupProblem.Local("${made.file.path} ended at $sent of ${made.size} bytes"))
            if (!ws.send(buf.toByteString(0, n))) throw Stopped(BackupProblem.Dropped(machine, "a chunk could not be sent after $sent bytes"))
            when (val r = next(events, ready = true)) {
                is Reply.Got -> {
                    if (r.bytes != sent + n) throw Stopped(BackupProblem.ServerSaid(machine, "it counted ${r.bytes} bytes where ${sent + n} were sent"))
                    sent = r.bytes
                    progress(sent)
                }
                is Reply.Error -> throw Stopped(BackupWire.problem(r.message, machine))
                else -> throw Stopped(unexpected(r, "got"))
            }
        }
        return when (val r = next(events, ready = true)) {
            is Reply.Saved -> Answer.Done(Kept(r.name, r.folder))
            is Reply.Error -> throw Stopped(BackupWire.problem(r.message, machine))
            else -> throw Stopped(unexpected(r, "saved"))
        }
    }

    /** The server's next frame on `/backup`; a close, a failure or silence stops the upload. */
    private suspend fun next(events: Channel<Event>, ready: Boolean): Reply {
        val e = withTimeoutOrNull(REPLY_TIMEOUT_MS) { events.receive() }
            ?: throw Stopped(BackupProblem.Dropped(machine, "no answer in ${REPLY_TIMEOUT_MS / 1000} s"))
        return when (e) {
            is Event.Text -> BackupWire.reply(e.text)
            is Event.Closed -> throw Stopped(BackupWire.closed(e.code, e.reason, ready, machine))
            is Event.Failed -> throw Stopped(BackupProblem.Dropped(machine, describe(e.error)))
            Event.Open -> throw Stopped(BackupProblem.ServerSaid(machine, "the connection opened twice"))
        }
    }

    /**
     * [b] into [dir] as `<name>.tmp`, its size checked against the list, then renamed to its
     * name: exactly one zip waits there for the next start. [progress] gets the bytes so far.
     */
    suspend fun fetch(b: ListedBackup, dir: File, progress: (Long) -> Unit): Answer<File> {
        if (!dir.isDirectory && !dir.mkdirs()) return Answer.Failed(BackupProblem.Local("could not make ${dir.path}"))
        // What an earlier fetch or push left goes, so this one is the only zip there.
        dir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(ZIP) || it.name.endsWith(ZIP + TMP)) }.forEach { it.delete() }
        val need = b.size * ROOM_FACTOR
        val free = dir.usableSpace
        if (free < need) return Answer.Failed(BackupProblem.NoRoom(free, need))
        val tmp = File(dir, b.name + TMP)
        val zip = File(dir, b.name)
        val call = client.newCall(get("/backups/${b.name}"))
        try {
            return stopped {
                cancellable(call) {
                    val resp = try { call.execute() } catch (e: IOException) { throw Stopped(unreachable(e)) }
                    val got = resp.use { r ->
                        if (r.code != 200) throw Stopped(refusal(r, listing = false))
                        copy(r.body.byteStream(), tmp, progress)
                    }
                    if (got != b.size) {
                        throw Stopped(BackupProblem.Damaged(got, b.size, "${b.name} arrived with $got bytes, and the server listed ${b.size}"))
                    }
                    if (!tmp.renameTo(zip)) throw Stopped(BackupProblem.Local("could not rename ${tmp.path} to ${zip.name}"))
                    Answer.Done(zip)
                }
            }
        } finally {
            if (!zip.exists()) tmp.delete()
        }
    }

    /** The body into [to], synced; its length. A read that breaks is the connection's, a write that fails this C1's. */
    private fun copy(input: InputStream, to: File, progress: (Long) -> Unit): Long {
        val out = try { FileOutputStream(to) } catch (e: IOException) { throw Stopped(BackupProblem.Local("${to.path} cannot be written: ${e.message}")) }
        out.use {
            val buf = ByteArray(COPY_BUFFER)
            var got = 0L
            var shown = 0L
            while (true) {
                val n = try { input.read(buf) } catch (e: IOException) { throw Stopped(dropped(e, "after $got bytes")) }
                if (n < 0) break
                try { out.write(buf, 0, n) } catch (e: IOException) {
                    throw Stopped(BackupProblem.Local("${to.path} could not be written after $got bytes: ${e.message}"))
                }
                got += n
                val now = System.nanoTime() / 1_000_000
                if (now - shown >= PROGRESS_MS) { shown = now; progress(got) }
            }
            try { out.fd.sync() } catch (e: IOException) { throw Stopped(BackupProblem.Local("${to.path} could not be synced: ${e.message}")) }
            progress(got)
            return got
        }
    }

    // ---- shared ---------------------------------------------------------------

    private fun get(path: String): Request = ServerAuth.authorize(Request.Builder().url(server.httpUrl + path), server).build()

    private fun refusal(r: Response, listing: Boolean): BackupProblem = when {
        r.code == ServerAuth.REFUSED -> BackupProblem.NeedsCode(machine)
        // An old server has no route: its WebSocket side answers a plain GET with 426.
        listing && (r.code == 404 || r.code == 426) -> BackupProblem.TooOld(machine)
        else -> BackupProblem.ServerSaid(machine, runCatching { r.body.string() }.getOrNull()?.let(BackupWire::error) ?: "it answered ${r.code}")
    }

    private fun unreachable(e: IOException) = BackupProblem.Unreachable(machine, address, describe(e))

    private fun dropped(e: IOException, at: String = "") =
        BackupProblem.Dropped(machine, listOf("the connection broke", at).filter { it.isNotEmpty() }.joinToString(" ") + ": ${describe(e)}")

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

    private fun unexpected(r: Reply, due: String) =
        BackupProblem.ServerSaid(machine, "it sent ${(r as? Reply.Other)?.text?.take(80) ?: r.toString()} where $due was due")

    private inline fun <T> stopped(block: () -> Answer<T>): Answer<T> = try { block() } catch (s: Stopped) { Answer.Failed(s.problem) }

    /** [block] off the main thread, with [call] cancelled the moment the coroutine is, a blocked read included. */
    private suspend fun <T> cancellable(call: Call, block: () -> T): T = coroutineScope {
        val watch = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { withContext(Dispatchers.IO) { block() } } finally { watch.cancel() }
    }
}
