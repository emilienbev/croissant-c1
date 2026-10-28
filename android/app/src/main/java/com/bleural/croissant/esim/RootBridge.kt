package com.bleural.croissant.esim

import android.os.Binder
import android.os.IBinder
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The root half of the eSIM path: a process started by [Bridge] with
 * `su -c "CLASSPATH=<this APK> app_process /system/bin com.bleural.croissant.esim.RootBridge"`,
 * so it runs as uid 0 and may call the phone service's logical channel methods, which
 * need MODIFY_PHONE_STATE. It never loads lpac; it only carries APDUs.
 *
 * The line protocol is device/esim/EsimBridge.java's, proven from the Mac first.
 * One request per line, one answer per line:
 *   open <aid hex>        -> ok <channel>
 *   tx <channel> <apdu>   -> ok <response hex, ending with SW1 SW2>
 *   close <channel>       -> ok
 *   simpower <0|1>        -> ok
 * or `err <message>`. "ready" once at start; end of input closes every channel it opened.
 *
 * The AID goes to the phone process in lowercase: it refuses the ISD-R AID to anyone
 * but the registered LPA with a case-sensitive compare, and the modem does not care.
 * Each channel carries a [Binder], so the phone process closes it if this process dies.
 */
object RootBridge {
    private const val SLOT = 0
    private const val PORT = 0

    /**
     * Longer than any real APDU (a STORE DATA round trip is 40 to 230 ms), shorter than
     * [Bridge]'s own wait. With the SIM powered down the phone process does not fail an
     * open, it waits, so a request can block for good (verified 2026-09-17): past this
     * the process answers `err timeout` and exits.
     */
    private const val REQUEST_MS = 20_000L
    private const val END = "\u0000end"

    private lateinit var tel: Any
    private val open = HashMap<Int, Any>()

    @JvmStatic
    fun main(args: Array<String>) {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "phone") as IBinder
        tel = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

        // Input is read on its own thread, so the app going away is noticed even while a
        // request is blocked in the phone process: killing su does not kill this process.
        // End of input asks the loop to close the channels; if the loop is stuck, the
        // process leaves anyway and the phone process closes them as their binders die.
        val lines = LinkedBlockingQueue<String>()
        thread(isDaemon = true, name = "input") {
            while (true) lines.put(readlnOrNull() ?: break)
            lines.put(END)
            Thread.sleep(3_000)
            exitProcess(0)
        }
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "timeout").apply { isDaemon = true } }

        answer("ready")
        while (true) {
            val line = lines.take().trim()
            if (line == END) break
            if (line.isEmpty()) continue
            val stuck = timer.schedule({ answer("err timeout: ${line.substringBefore(' ')}"); exitProcess(1) }, REQUEST_MS, TimeUnit.MILLISECONDS)
            val reply = try {
                handle(line.split(' '))
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                "err ${cause.javaClass.simpleName}: ${cause.message}"
            }
            // A request that timed out has had its answer already.
            if (stuck.cancel(false)) answer(reply)
        }
        for (request in open.values) runCatching { method("iccCloseLogicalChannel", 1).invoke(tel, request) }
        exitProcess(0)
    }

    @Synchronized
    private fun answer(line: String) {
        println(line)
        System.out.flush()
    }

    private fun handle(p: List<String>): String = when (p[0]) {
        "open" -> {
            val request = request(p[1].lowercase())
            val response = method("iccOpenLogicalChannel", 1).invoke(tel, request)!!
            val channel = response.javaClass.getMethod("getChannel").invoke(response) as Int
            val status = response.javaClass.getMethod("getStatus").invoke(response) as Int
            // Status 1 is STATUS_NO_ERROR; the others say why the card refused the AID.
            if (status != 1 || channel <= 0) "err open status $status"
            else {
                request.javaClass.getField("channel").set(request, channel)
                open[channel] = request
                "ok $channel"
            }
        }
        "tx" -> {
            val apdu = p[2]
            fun byte(at: Int) = apdu.substring(at, at + 2).toInt(16)
            val p3 = if (apdu.length >= 10) byte(8) else -1
            val data = if (apdu.length > 10) apdu.substring(10) else ""
            val r = method("iccTransmitApduLogicalChannelByPort", 9)
                .invoke(tel, SLOT, PORT, p[1].toInt(), byte(0), byte(2), byte(4), byte(6), p3, data) as String?
            if (r == null || r.length < 4) "err no response" else "ok ${r.uppercase()}"
        }
        "close" -> {
            val request = open.remove(p[1].toInt())
            if (request == null) "err not open"
            else { method("iccCloseLogicalChannel", 1).invoke(tel, request); "ok" }
        }
        "simpower" -> {
            method("setSimPowerStateForSlot", 2).invoke(tel, SLOT, p[1].toInt())
            "ok"
        }
        else -> "err unknown ${p[0]}"
    }

    private fun request(aid: String): Any {
        val r = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest").getConstructor().newInstance()
        fun set(name: String, value: Any?) = r.javaClass.getField(name).set(r, value)
        set("slotIndex", SLOT)
        set("portIndex", PORT)
        set("subId", -1)
        set("callingPackage", "root")
        set("aid", aid)
        set("p2", 0)
        set("binder", Binder())
        return r
    }

    private fun method(name: String, argc: Int): Method =
        tel.javaClass.methods.first { it.name == name && it.parameterCount == argc }
}
