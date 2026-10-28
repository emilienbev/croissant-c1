package com.bleural.croissant.net

import android.util.Log
import com.bleural.croissant.Prefs
import com.bleural.croissant.data.Generated
import com.bleural.croissant.data.GeneratedStore
import com.bleural.croissant.data.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Asking the server for pictures, and collecting them.
 *
 * A picture takes far longer than the hold that asked for it, so the server hands the
 * device an id and the device comes back for the bytes. It comes back on a schedule,
 * not in a loop: [FIRST_S] after it was told (about how long a picture takes), then
 * [TRIES] more at [RETRY_S], and then it stops. Anything still missing is fetched by
 * opening its page, which polls while it is open, and otherwise by [watch] every few
 * minutes. Nothing is lost while the server still has it, and after the 24 hours it
 * keeps a picture for, a row that never arrived says so by itself (Generated.lost).
 *
 * A fetched picture is deleted from the server (`DELETE`), and that call is the
 * server's confirmation that the bytes really arrived. [forget] is the same call for a
 * picture the owner stopped or threw away, so a job nobody wants stops being kept - and
 * because the case it exists for is a link that has just dropped, a call that does not
 * land is written down ([Forgets]) and made again by [watch] rather than lost.
 *
 * [watch] is the other half of the schedule: every [SWEEP_MIN] minutes it finishes any
 * deletion still owed and knocks once for every picture still expected, so a picture
 * that was being made while the tailnet was down arrives when it comes back instead of
 * waiting for someone to open its page.
 *
 * [start] asks for one (Ask's `image_generate`, the Transform button) and [again] for a
 * picture the Mac failed to make, a second time, by prompt, straight to the image model
 * (`Protocol.generate`).
 *
 * A picture a cloud model makes from the C1 (`Generated.c1`, ask/Pictures.kt) is none of
 * this: it is never fetched, knocked for or told to the server.
 */
class GenerationFetcher(
    private val store: GeneratedStore,
    private val scope: CoroutineScope,
    /** The device's model picks (net/Models.kt): a Try again goes to the picture model picked now. */
    private val picks: () -> Map<String, String> = { emptyMap() },
    private val server: () -> Prefs.Server,
) {
    companion object {
        private const val TAG = "Generations"

        /**
         * Measured on the Mac, 768x1024 at nine steps: 28 s warm, 54 s when the worker
         * had to be started and the weights loaded first. So the first ask lands just
         * after a warm picture is ready, and the three retries cover a cold one: 35, 50,
         * 65, 80 s after the frame.
         */
        const val FIRST_S = 35L
        const val RETRY_S = 15L
        const val TRIES = 3

        /**
         * How long [again] waits for the server to say it has taken the prompt. It only
         * has to file a job and answer, which is immediate; anything past this is the
         * Mac asleep or the tailnet down.
         */
        const val ASK_S = 8L

        /**
         * The gap between fetches while a picture's page is open (ui/beam/Photos.kt).
         * Standing on the page is the ask, so it goes back often; a picture takes 15 to
         * 60 s, so five seconds is a handful of knocks, not a busy loop.
         */
        const val POLL_S = 5L

        /**
         * The gap between sweeps ([watch]). Nobody is waiting on these: they are the
         * deletions the Mac has not heard about and the pictures whose four knocks are
         * spent, both of which are only waiting for the tailnet to come back. Five
         * minutes is often enough that a picture lands soon after the link does, and
         * rare enough to be nothing at all when there is no link.
         */
        const val SWEEP_MIN = 5L
    }

    private val forgets = Forgets(File(store.dir, "forget.json"))

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val running = HashMap<String, Job>()

    /** What the server says when asked for a picture. */
    sealed interface Answer {
        data object Saved : Answer
        data object NotYet : Answer
        data class Failed(val reason: String) : Answer
    }

    /** Fetch [id] after the usual wait, then a few more times, then leave it to a tap. */
    @Synchronized
    fun schedule(id: String) {
        if (running.containsKey(id) || c1(id)) return
        running[id] = scope.launch {
            try {
                delay(FIRST_S * 1000)
                repeat(TRIES + 1) { n ->
                    if (n > 0) delay(RETRY_S * 1000)
                    when (val a = fetch(id)) {
                        is Answer.Saved -> return@launch
                        is Answer.Failed -> {
                            Log.w(TAG, "$id failed: ${a.reason}")
                            return@launch
                        }
                        Answer.NotYet -> Unit
                    }
                }
                Log.i(TAG, "$id not ready after ${TRIES + 1} tries; waiting for a tap")
            } finally {
                synchronized(this@GenerationFetcher) { running.remove(id) }
            }
        }
    }

    /**
     * One attempt, on the IO dispatcher. 200 saves the picture and claims it, 202 means
     * it is still being made, 404 means the server no longer has it, 500 carries the
     * reason it failed. Anything else (the Mac asleep, the tailnet down) is "not yet":
     * the picture may well still be there. One the C1 is making is not the server's to give: not yet.
     */
    suspend fun fetch(id: String): Answer = withContext(Dispatchers.IO) {
        if (c1(id)) return@withContext Answer.NotYet
        val base = server().httpUrl
        try {
            client.newCall(Request.Builder().url("$base/generated/$id").build()).execute().use { r ->
                when (r.code) {
                    200 -> {
                        val bytes = r.body.bytes()
                        if (bytes.isEmpty()) return@withContext Answer.NotYet
                        store.save(id, bytes)
                        Log.i(TAG, "$id fetched, ${bytes.size / 1024} kB")
                        // Deleted only once the bytes are on this disk, so the DELETE
                        // says the picture arrived rather than only that it was sent.
                        // One that does not land is owed, and [watch] pays it.
                        if (!claim(base, id)) forgets.add(id)
                        Answer.Saved
                    }
                    202 -> { store.tried(id); Answer.NotYet }
                    404 -> Answer.Failed("No longer on the server").also { store.fail(id, it.reason) }
                    else -> {
                        val why = reason(r.body.string()) ?: "server error ${r.code}"
                        Answer.Failed(why).also { store.fail(id, why) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "$id not fetched: ${e.javaClass.simpleName}")
            store.tried(id)
            Answer.NotYet
        }
    }

    /**
     * A picture of [prompt], from [photo] when there is one: Ask's `image_generate` and the
     * Transform button. Its id is made here and its row written ([source] the photo's name in
     * the library) before the `generate` frame goes, as [again] does; collected on the usual
     * schedule, a refusal written onto its row. The id comes back at once, before the server
     * has answered: the model is told the picture is started, and the fetch finds out.
     */
    fun start(prompt: String, photo: ByteArray? = null, source: String? = null): String {
        val id = JsonStore.newId("g")
        store.add(id, prompt, source = source)
        scope.launch {
            var sent = false
            val answer = withTimeoutOrNull(ASK_S * 1000) { ask(id, prompt, photo) { sent = true } }
                ?: if (sent) Again.Unsure(id) else Again.Refused("The server did not answer")
            if (answer is Again.Refused) {
                Log.w(TAG, "$id not started: ${answer.reason}")
                store.fail(id, answer.reason)
            } else schedule(id)
        }
        return id
    }

    /** What came of asking for a picture again. */
    sealed interface Again {
        /** The server took the prompt and is making it under the new id. */
        data class Started(val id: String) : Again
        /**
         * The request went out and no answer came back. The server may well be making it,
         * under the id this device gave it, so it is treated as started: the fetch is
         * what finds out (a 404 says it never arrived).
         */
        data class Unsure(val id: String) : Again
        data class Refused(val reason: String) : Again
    }

    /**
     * Make [item]'s picture again, from the same prompt, and let the old one go.
     *
     * Straight to the image model rather than back through the assistant: the prompt is
     * already the one the owner wanted, and asking the model to call its tool a second
     * time is a round trip that could reword it. [photo] is the shot a transform worked
     * from, when it is still on the device, so a failed transform is transformed again
     * rather than invented from its own description; without it the prompt alone is
     * used, and the new picture claims no source.
     *
     * The new id is made here and its row written before anything is sent, so a picture
     * the server starts is never one the device has not heard of; the old
     * row goes once the request is out. A refusal is written onto the old row instead,
     * which is what puts the reason on the page, and the new row goes.
     */
    suspend fun again(item: Generated, photo: ByteArray? = null): Again {
        val prompt = item.prompt.trim()
        if (prompt.isEmpty()) return Again.Refused("Nothing was asked for this one")
        val id = JsonStore.newId("g")
        store.add(id, prompt, source = if (photo != null) item.source else null)
        var sent = false
        val answer = withTimeoutOrNull(ASK_S * 1000) { ask(id, prompt, photo) { sent = true } }
            ?: if (sent) Again.Unsure(id) else Again.Refused("The server did not answer")
        when (answer) {
            is Again.Started, is Again.Unsure -> {
                Log.i(TAG, "${item.id} asked again as $id" + if (answer is Again.Unsure) " (no answer yet)" else "")
                schedule(id)
                // One the C1 made was never the server's to drop.
                if (!item.c1) forget(item.id)
                store.delete(item.id)
            }
            is Again.Refused -> {
                Log.w(TAG, "${item.id} not asked again: ${answer.reason}")
                store.delete(id)
                store.fail(item.id, answer.reason)
            }
        }
        return answer
    }

    /**
     * One connection, one `generate` frame, one answer. [onSent] runs once the frame is
     * handed to the socket; a socket that drops after that is [Again.Unsure], not refused.
     */
    private suspend fun ask(id: String, prompt: String, photo: ByteArray?, onSent: () -> Unit): Again =
        suspendCancellableCoroutine { cont ->
            val client = SessionClient(server().url)
            fun settle(answer: Again) {
                if (!cont.isActive) return
                client.close()
                cont.resume(answer)
            }
            var opened = false
            client.connect(object : SessionClient.Listener {
                override fun onOpen() {
                    opened = true
                    client.sendText(Protocol.generate(id, prompt, image = photo != null, models = picks()))
                    if (photo != null) client.sendAudio(photo, photo.size)
                    onSent()
                }

                override fun onMessage(msg: Protocol.Incoming) = when (msg) {
                    is Protocol.Incoming.Generation -> settle(Again.Started(msg.id))
                    is Protocol.Incoming.Failure -> settle(Again.Refused(msg.message))
                    else -> Unit
                }

                override fun onClosed() = settle(Again.Unsure(id))

                override fun onFailure(kind: SessionClient.FailureKind, message: String) =
                    settle(if (opened) Again.Unsure(id) else Again.Refused(message))
            })
            cont.invokeOnCancellation { client.abort() }
        }

    /**
     * Tell the server we will not be collecting this one, so it stops keeping it and
     * stops making it: the owner stopped it or threw it away, or it is being asked for
     * again under a new id.
     *
     * Written down before it is tried. The link being down is the whole reason this
     * exists - a picture stopped while the Mac is unreachable would otherwise be made
     * in full, for nobody, and kept for a day - so a call that does not land is owed
     * rather than lost, and [watch] makes it again until it does.
     */
    fun forget(id: String) {
        // Whatever knocking was still scheduled for it stops here: the row is going, so
        // every answer it could get is about a picture nobody is waiting for any more.
        synchronized(this) { running.remove(id) }?.cancel()
        forgets.add(id)
        scope.launch(Dispatchers.IO) { pay() }
    }

    /**
     * Finish what is owed and knock for what is still expected. Runs for as long as the
     * service does; both halves are cheap and silent when there is nothing to do.
     */
    fun watch() {
        scope.launch {
            while (true) {
                withContext(Dispatchers.IO) { pay() }
                for (g in store.list()) if (g.pending && !g.c1) fetch(g.id)
                delay(SWEEP_MIN * 60_000)
            }
        }
    }

    /** Every deletion still owed, dropped once it lands or once the server has swept it anyway. */
    private fun pay() {
        for ((id, at) in forgets.all()) {
            val stale = System.currentTimeMillis() - at > Generated.KEEP_MS
            if (stale || claim(server().httpUrl, id)) {
                forgets.remove(id)
                if (stale) Log.i(TAG, "$id given up on; the server has swept it by now")
            }
        }
    }

    /** True when the server heard it. A 404 counts: it does not have the picture either way. */
    private fun claim(base: String, id: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("$base/generated/$id").delete().build())
            .execute().use { it.isSuccessful || it.code == 404 }
    }.getOrElse { Log.i(TAG, "$id not claimed: ${it.javaClass.simpleName}"); false }

    /** Whether [id] is the C1's own (`Generated.c1`): nothing about it is the server's. */
    private fun c1(id: String): Boolean = store.get(id)?.c1 == true

    private fun reason(body: String): String? =
        runCatching { JSONObject(body).optString("error").takeIf { it.isNotEmpty() } }.getOrNull()
}

/**
 * The ids the server has been told to drop but has not heard about yet, by when they
 * were written down.
 *
 * A file rather than a set in memory, because the link can be down for longer than the
 * app is up, and the picture on the Mac outlives a reboot. An id older than the server's
 * own keep window is dropped: the picture it names is gone by then whatever happened.
 */
private class Forgets(private val file: File) {
    @Synchronized
    fun all(): List<Pair<String, Long>> = read().let { o ->
        o.keys().asSequence().map { it to o.optLong(it) }.toList()
    }

    @Synchronized
    fun add(id: String) = write(read().put(id, System.currentTimeMillis()))

    @Synchronized
    fun remove(id: String) = write(read().apply { remove(id) })

    private fun read(): JSONObject =
        runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    private fun write(o: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }
}
