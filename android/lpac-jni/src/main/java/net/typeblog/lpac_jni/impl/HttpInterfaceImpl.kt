package net.typeblog.lpac_jni.impl

import android.util.Log
import net.typeblog.lpac_jni.HttpInterface
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/**
 * HTTPS to the SM-DP+, trusting only the GSMA CI roots the eUICC names
 * ([usePublicKeyIds], [keyIdToKeystore]).
 *
 * Trimmed for Croissant from OpenEUICC's version: no verbose logging, proxy or
 * ignore-TLS flows (they were its settings), so there is no path that switches
 * certificate checks off.
 */
class HttpInterfaceImpl : HttpInterface {
    companion object {
        private const val TAG = "HttpInterfaceImpl"
    }

    private lateinit var trustManagers: Array<TrustManager>

    override fun transmit(
        url: String,
        tx: ByteArray,
        headers: Array<String>
    ): HttpInterface.HttpResponse {
        Log.d(TAG, "transmit(url = $url)")

        val parsedUrl = URL(url)
        if (parsedUrl.protocol != "https") {
            throw IllegalArgumentException("SM-DP+ servers must use the HTTPS protocol")
        }

        val conn = parsedUrl.openConnection() as HttpsURLConnection

        // OpenEUICC waits 2 s to connect and forever to read, 1 s each for a notification.
        // Longer here: a slow link should fail with a timeout, not hang or give up early.
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000

        if (url.contains("handleNotification")) {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
        }

        conn.sslSocketFactory = getSocketFactory()
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true

        for (h in headers) {
            val s = h.split(":", limit = 2)
            conn.setRequestProperty(s[0], s[1])
        }

        conn.outputStream.write(tx)
        conn.outputStream.flush()
        conn.outputStream.close()

        Log.d(TAG, "transmit responseCode = ${conn.responseCode}")

        return HttpInterface.HttpResponse(conn.responseCode, conn.inputStream.readBytes())
    }

    private fun getSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagers, SecureRandom())
        return sslContext.socketFactory
    }

    override fun usePublicKeyIds(pkids: Array<String>) {
        val trustManagerFactory = TrustManagerFactory.getInstance("PKIX").apply {
            init(keyIdToKeystore(pkids))
        }
        trustManagers = trustManagerFactory.trustManagers
    }
}
