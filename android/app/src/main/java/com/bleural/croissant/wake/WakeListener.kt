package com.bleural.croissant.wake

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The wake word on the always-hot mic: [Spotter] and [WakeRule] on a thread of their own, fed
 * by the capture thread, which only queues, so a slow step never stalls the mic. [onWake] runs
 * on that thread; the caller moves it where it needs. A minute's cost is logged (grep `wake minute`).
 */
class WakeListener(model: ByteBuffer, private val onWake: () -> Unit) {
    private val spotter = Spotter(model)
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
        queue.clear()
        deaf = WakeConfig.RESTART_DEAF_MS / Spotter.STEP_MS
        rule = WakeRule(WakeConfig.CUTOFF)
    }

    fun stop() {
        running = false
        worker.join(1000)
        spotter.close()
    }

    private fun listen() {
        var heardMs = 0L
        var cpuAt = Debug.threadCpuTimeNanos()
        var windowAt = SystemClock.elapsedRealtime()
        while (running) {
            val s = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
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

    private companion object {
        const val TAG = "WakeListener"
        /** Two seconds of 20 ms chunks: past that the model is too slow and chunks are dropped (and counted). */
        const val QUEUE_CHUNKS = 100
        const val REPORT_MS = 60_000L
    }
}
