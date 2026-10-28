package com.bleural.croissant.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One WebSocket per utterance.
 *
 * Failure classification is deliberate: anything that
 * prevents the connection from being established at all is "unreachable" and should fail
 * fast; anything that goes wrong *after* a successful connect is transient. The spill-and-
 * retry behaviour that consumes this distinction is not implemented yet, so for now it only
 * decides the message shown to the user.
 */
class SessionClient(private val url: String) {

    companion object { private const val TAG = "SessionClient" }

    enum class FailureKind { UNREACHABLE, TRANSIENT }

    interface Listener {
        fun onOpen()
        fun onMessage(msg: Protocol.Incoming)
        fun onClosed()
        fun onFailure(kind: FailureKind, message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // a websocket is expected to idle
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var everOpened = false

    fun connect(listener: Listener) {
        everOpened = false
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpened = true
                Log.i(TAG, "open $url")
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(Protocol.parse(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Server sends no binary frames in this version.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closed $code $reason")
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val kind = classify(t)
                Log.w(TAG, "failure (${kind}): ${t.javaClass.simpleName}: ${t.message}")
                listener.onFailure(kind, describe(kind, t))
            }
        })
    }

    /** Connect-level problems mean the backend is not there; everything else is transient. */
    private fun classify(t: Throwable): FailureKind = when {
        everOpened -> FailureKind.TRANSIENT
        t is ConnectException || t is NoRouteToHostException ||
            t is UnknownHostException || t is SocketTimeoutException -> FailureKind.UNREACHABLE
        else -> FailureKind.TRANSIENT
    }

    private fun describe(kind: FailureKind, t: Throwable): String = when (kind) {
        FailureKind.UNREACHABLE -> "Backend unreachable"
        FailureKind.TRANSIENT -> t.message ?: "Connection lost"
    }

    fun sendText(s: String): Boolean = ws?.send(s) ?: false

    fun sendAudio(bytes: ByteArray, len: Int): Boolean =
        ws?.send(bytes.toByteString(0, len)) ?: false

    /** Bytes handed to [sendAudio] and not yet on the wire: OkHttp refuses a send once 16 MiB queue up, so a file is fed at the socket's pace. */
    fun queued(): Long = ws?.queueSize() ?: 0L

    fun close() {
        ws?.close(1000, "done")
        ws = null
    }

    fun abort() {
        ws?.cancel()
        ws = null
    }
}
