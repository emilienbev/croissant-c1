package com.bleural.croissant

import android.os.Handler
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The side button as the kernel reports it, for the one press the framework never passes
 * on: the press that wakes the device. Android drops that press's DOWN, its UP and every
 * auto-repeat in between (the repeats inherit the DOWN's policy flags), so
 * without this a hold from sleep only wakes. A root `getevent` on `mtk-kpd` sees every
 * press whatever the screen; [Core] decides which ones are its to act on.
 * [onDown] and [onUp] run on [handler]'s thread. Without root (the emulator) it does nothing.
 */
class KernelButton(private val handler: Handler, private val onDown: () -> Unit, private val onUp: () -> Unit) {
    companion object {
        private const val TAG = "KernelButton"
        /** The side button's input node, found by name so a renumbering does not matter. */
        private const val CMD = "for e in /sys/class/input/event*; do " +
            "[ \"\$(cat \$e/device/name)\" = mtk-kpd ] && exec getevent -ql /dev/input/\${e##*/}; done"
    }

    private var process: Process? = null

    /** Starts the reader, or starts it again if it has ended; one at a time. */
    fun start() {
        if (process?.isAlive == true) return
        val p = try {
            ProcessBuilder("su", "-c", CMD).start()
        } catch (e: IOException) {
            Log.w(TAG, "no root: ${e.message}")
            return
        }
        process = p
        thread(name = "kernel-button", isDaemon = true) {
            // Lines read "EV_KEY  KEY_POWER  DOWN"; the SYN_REPORTs between them are skipped.
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val w = line.trim().split(Regex("\\s+"))
                    if (w.size < 3 || w[0] != "EV_KEY" || w[1] != "KEY_POWER") return@forEachLine
                    when (w[2]) {
                        "DOWN" -> handler.post(onDown)
                        "UP" -> handler.post(onUp)
                    }
                }
            }
            Log.i(TAG, "reader ended")
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
