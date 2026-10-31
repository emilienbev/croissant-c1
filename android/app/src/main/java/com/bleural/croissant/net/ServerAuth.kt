package com.bleural.croissant.net

import com.bleural.croissant.Prefs
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection

/**
 * croissant-server on a LAN lets a C1 in only with the token its pairing code carried
 * (server/croissant_server/app.py, `gated`). Every request to it sends [Prefs.Server.auth],
 * and a 401 means the C1 has no token or an old one, so it is told to scan the code again.
 */
object ServerAuth {
    private const val HEADER = "Authorization"
    const val REFUSED = 401
    const val NEEDS_CODE = "the server wants its pairing code: scan it again in Settings > server"

    fun authorize(c: HttpURLConnection, s: Prefs.Server) {
        s.auth?.let { c.setRequestProperty(HEADER, it) }
    }

    fun authorize(b: Request.Builder, s: Prefs.Server): Request.Builder = s.auth?.let { b.header(HEADER, it) } ?: b

    /** A status other than 200, as the exception the callers throw: a 401 says what to do about it. */
    fun failure(code: Int) = IOException(if (code == REFUSED) NEEDS_CODE else "the server answered $code")
}
