package com.bleural.croissant.backup

import org.json.JSONException
import org.json.JSONObject

/** A frame the server sends on `/backup`. */
internal sealed interface Reply {
    data class Ready(val name: String) : Reply
    data class Got(val bytes: Long) : Reply
    data class Saved(val name: String, val folder: String) : Reply
    data class Error(val message: String) : Reply
    /** Anything else: not ours, said as it came. */
    data class Other(val text: String) : Reply
}

/** The server's list: its folder, as it names it, and every backup in it, newest first. */
internal data class ServerList(val folder: String, val backups: List<ListedBackup>)

/**
 * croissant-server's side of backups as text (server/croissant_server/backups.py), apart from
 * the sockets so it is tested on its own: the frames of an upload, the error texts the page
 * tells apart, and the list.
 */
internal object BackupWire {
    /** An old croissant-server closes `/backup` as it closes any path it does not know (app.py `handler`). */
    const val UNKNOWN_PATH_CODE = 1008
    const val UNKNOWN_PATH = "unknown path"
    private const val DISK_FULL = "the disk on this machine is full"
    private val DAMAGED = Regex("""the backup arrived damaged: (\d+) bytes, sha256 \w+, expected (\d+) bytes""")

    fun begin(size: Long, sha256: String): String =
        JSONObject().put("type", "begin").put("size", size).put("sha256", sha256).toString()

    fun reply(text: String): Reply {
        val o = try { JSONObject(text) } catch (e: JSONException) { return Reply.Other(text) }
        return when (o.optString("type")) {
            "ready" -> o.optString("name").takeIf { it.isNotEmpty() }?.let { Reply.Ready(it) }
            "got" -> Json.integral(o.opt("bytes"))?.let { Reply.Got(it) }
            "saved" -> {
                val name = o.optString("name"); val folder = o.optString("folder")
                if (name.isEmpty() || folder.isEmpty()) null else Reply.Saved(name, folder)
            }
            "error" -> Reply.Error(o.optString("error").ifEmpty { text })
            else -> null
        } ?: Reply.Other(text)
    }

    /** What an `error` frame means for the page: damaged on arrival, a full disk, or the server's words. */
    fun problem(message: String, machine: String): BackupProblem {
        DAMAGED.find(message)?.let { m -> return BackupProblem.Damaged(m.groupValues[1].toLong(), m.groupValues[2].toLong(), message) }
        if (message.startsWith(DISK_FULL)) return BackupProblem.DiskFull(machine, message)
        return BackupProblem.ServerSaid(machine, message)
    }

    /** `/backup` closed before `saved`: an old server, before `ready`, or a connection that ended part way. */
    fun closed(code: Int, reason: String, ready: Boolean, machine: String): BackupProblem =
        if (!ready && code == UNKNOWN_PATH_CODE && reason == UNKNOWN_PATH) BackupProblem.TooOld(machine)
        else BackupProblem.Dropped(machine, "it closed the connection ($code${if (reason.isEmpty()) "" else " $reason"}) before saying it kept the backup")

    /** The `error` of a JSON answer, when there is one. */
    fun error(body: String): String? = runCatching { JSONObject(body).optString("error").ifEmpty { null } }.getOrNull()

    /**
     * `GET /backups`. A backup listed without the fields a restore checks has an [ListedBackup.error]
     * saying which, as one whose manifest the server could not read. Throws [JSONException]
     * when the answer is not a list at all.
     */
    fun list(json: String, appCode: Int): ServerList {
        val o = JSONObject(json)
        val a = o.getJSONArray("backups")
        return ServerList(o.getString("folder"), (0 until a.length()).map { i ->
            val b = a.getJSONObject(i)
            val name = b.getString("name")
            val size = Json.integral(b.opt("size")) ?: throw JSONException("$name is listed with no size")
            val code = Json.integral(b.opt("versionCode"))?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
            val app = b.opt("app") as? String
            val version = b.opt("version") as? String
            val serial = b.opt("serial") as? String
            val date = b.opt("date") as? String
            val about = if (app != null && version != null && code != null && serial != null && date != null) {
                BackupAbout(app, version, code, serial, date)
            } else null
            val missing = mapOf("app" to app, "version" to version, "versionCode" to code, "serial" to serial, "date" to date)
                .filterValues { it == null }.keys
            val error = b.optString("error").ifEmpty { null }
                ?: missing.takeIf { it.isNotEmpty() }?.let { "the server listed it without its ${it.joinToString()}" }
            if (error != null || about == null) ListedBackup(name, size, null, error)
            else ListedBackup(name, size, about, newer = about.versionCode > appCode)
        })
    }
}
