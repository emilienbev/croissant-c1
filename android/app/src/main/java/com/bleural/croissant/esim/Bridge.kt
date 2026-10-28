package com.bleural.croissant.esim

import android.util.Log
import net.typeblog.lpac_jni.ApduInterface
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's half of the eSIM path: starts [RootBridge] through Magisk's su and speaks
 * its line protocol, and is the [ApduInterface] lpac's libeuicc sends its APDUs to.
 * One per operation: [start], the reads or writes, [close].
 *
 * A request that gets no answer in [TIMEOUT_MS] kills the root process, which ends the
 * blocked read, so nothing on the eSIM thread can hang for good.
 */
class Bridge private constructor(private val process: Process) : ApduInterface, Closeable {

    /** Thrown when the root process cannot be started or will not answer: su refused, or app_process failed. */
    class NoRoot(message: String) : IOException(message)

    companion object {
        private const val TAG = "EsimBridge"
        /** app_process takes about a second to start on this chip; su adds a little. */
        private const val START_MS = 15_000L
        /** Past [RootBridge]'s own 20 s: this one is for a root process that stopped answering at all. */
        private const val TIMEOUT_MS = 30_000L

        private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esim-watchdog").apply { isDaemon = true } }

        /** [apk] is the app's own `sourceDir`: the root process loads [RootBridge] from it. */
        fun start(apk: String): Bridge {
            val cmd = "CLASSPATH=$apk app_process /system/bin ${RootBridge::class.java.name}"
            val process = try {
                ProcessBuilder("su", "-c", cmd).start()
            } catch (e: IOException) {
                throw NoRoot("su: ${e.message}")
            }
            val bridge = Bridge(process)
            val first = bridge.readLine(START_MS)
            if (first != "ready") {
                bridge.close()
                throw NoRoot(if (first == null) "root process did not start" else "root process said: $first")
            }
            return bridge
        }
    }

    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val reader: BufferedReader = process.inputStream.bufferedReader()

    /** The last `err` answer without its `err`, kept so a failure deep inside lpac can be told apart afterwards. */
    var lastError: String? = null
        private set

    init {
        // app_process writes nothing to stderr when all is well; drain it so a crash cannot
        // block the pipe. The read fails when the process is killed, which must not take the
        // app down with it (it did, 2026-09-17).
        Thread({ runCatching { process.errorStream.bufferedReader().forEachLine { Log.w(TAG, it) } } }, "esim-stderr").apply { isDaemon = true }.start()
    }

    private fun readLine(timeoutMs: Long): String? {
        val kill = watchdog.schedule({ Log.w(TAG, "no answer in $timeoutMs ms"); process.destroy() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try { reader.readLine() } finally { kill.cancel(false) }
    }

    @Synchronized
    private fun ask(request: String): String {
        writer.write(request)
        writer.newLine()
        writer.flush()
        val answer = readLine(TIMEOUT_MS) ?: throw IOException("root process ended")
        if (answer.startsWith("err")) {
            lastError = answer.removePrefix("err").trim()
            throw IOException(lastError)
        }
        return answer.removePrefix("ok").trim()
    }

    /**
     * Powers the SIM slot down or up. What makes the modem read the card again after a
     * profile switch, since the card sends it no REFRESH. lpac must
     * be closed first: a channel open on a powered-down SIM waits in the phone process.
     */
    fun simPower(on: Boolean) {
        ask("simpower ${if (on) 1 else 0}")
    }

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun logicalChannelOpen(aid: ByteArray): Int = ask("open ${aid.toHex()}").toInt()
    override fun logicalChannelClose(handle: Int) { runCatching { ask("close $handle") } }
    override fun transmit(handle: Int, tx: ByteArray): ByteArray = ask("tx $handle ${tx.toHex()}").hexToBytes()
    override val valid: Boolean get() = process.isAlive

    /** Ends the root process: end of input makes it close whatever channel is still open. */
    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
