package com.bleural.croissant.wake

import android.content.res.AssetManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The wake word on the always-hot mic: [Spotter] and [WakeRule] on a thread of their own, fed
 * by the capture thread, which only queues, so a slow step never stalls the mic. After a wake
 * the same thread follows the ask to its end ([follow]: [SpeechDetector] and [Endpoint]).
 * [onWake] and the end run on that thread; the caller moves them where it needs. A minute's
 * cost is logged (grep `wake minute`), and each ask's end (grep `ask end`).
 */
class WakeListener(model: ByteBuffer, assets: AssetManager, private val onWake: () -> Unit) {
    private val spotter = Spotter(model)
    private val speech = SpeechDetector(assets)
    private val endpoint = Endpoint()
    /** The ask being followed to its end, or null while it listens for the wake. */
    @Volatile private var ask: Ask? = null
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_CHUNKS)
    @Volatile private var running = true
    @Volatile private var rule = WakeRule(WakeConfig.CUTOFF)
    /** Model steps still to ignore: after a pause the model's memory holds what came before it. */
    @Volatile private var deaf = 0
    @Volatile private var dropped = 0
    private val worker = thread(name = "wake-word", priority = Thread.NORM_PRIORITY) { listen() }

    /** A chunk from the capture thread: 16 kHz 16-bit little-endian PCM. */
    fun feed(buf: ByteArray, n: Int) {
        val s = ShortArray(n / 2) { i -> ((buf[2 * i].toInt() and 0xff) or (buf[2 * i + 1].toInt() shl 8)).toShort() }
        if (!queue.offer(s)) dropped++
    }

    /**
     * After a pause (a hold, an answer, a failed ask): the window starts empty and the first
     * [WakeConfig.RESTART_DEAF_MS] are ignored, so the phrase that started the last ask, still
     * in the model's memory, cannot start another.
     */
    fun restart() {
        ask = null
        queue.clear()
        deaf = WakeConfig.RESTART_DEAF_MS / Spotter.STEP_MS
        rule = WakeRule(WakeConfig.CUTOFF)
    }

    /** After a wake: the chunks fed from now on are the ask, and [onEnd] gets its end, once. */
    fun follow(onEnd: (Endpoint.Verdict) -> Unit) {
        queue.clear()
        ask = Ask(onEnd)
    }

    fun stop() {
        running = false
        worker.join(1000)
        spotter.close()
        speech.close()
    }

    private class Ask(val onEnd: (Endpoint.Verdict) -> Unit) {
        var started = false
        var ms = 0
        var cpuAt = 0L
    }

    private fun listen() {
        var heardMs = 0L
        var cpuAt = Debug.threadCpuTimeNanos()
        var windowAt = SystemClock.elapsedRealtime()
        while (running) {
            val s = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val a = ask
            if (a != null) {
                hearAsk(a, s)
                continue
            }
            val r = rule
            spotter.feed(s) { p ->
                if (deaf > 0) deaf-- else if (r.offer(p)) { Log.i(TAG, "wake, p %.2f".format(p)); onWake() }
            }
            heardMs += s.size / 16
            val now = SystemClock.elapsedRealtime()
            if (now - windowAt >= REPORT_MS) {
                val cpu = Debug.threadCpuTimeNanos()
                Log.i(TAG, "wake minute: heard $heardMs ms, cpu ${(cpu - cpuAt) / 1_000_000} ms, dropped $dropped")
                heardMs = 0; dropped = 0; cpuAt = cpu; windowAt = now
            }
        }
    }

    private fun hearAsk(a: Ask, s: ShortArray) {
        if (!a.started) {
            speech.reset()
            endpoint.start()
            a.started = true
            a.cpuAt = Debug.threadCpuTimeNanos()
        }
        speech.feed(s) { isSpeech ->
            if (ask !== a) return@feed
            a.ms += SpeechDetector.WINDOW_MS
            val v = endpoint.offer(isSpeech, SpeechDetector.WINDOW_MS)
            if (v == Endpoint.Verdict.Going) return@feed
            ask = null
            Log.i(TAG, "ask end: $v after ${a.ms} ms, speech check cpu ${(Debug.threadCpuTimeNanos() - a.cpuAt) / 1_000_000} ms")
            a.onEnd(v)
        }
    }

    private companion object {
        const val TAG = "WakeListener"
        /** Two seconds of 20 ms chunks: past that the model is too slow and chunks are dropped (and counted). */
        const val QUEUE_CHUNKS = 100
        const val REPORT_MS = 60_000L
    }
}
