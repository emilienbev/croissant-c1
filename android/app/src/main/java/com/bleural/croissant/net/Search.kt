package com.bleural.croissant.net

import com.bleural.croissant.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ask's `web_search` while it stays on the Mac (both engines challenge a plain client):
 * `GET /search?q=` on the server, its digest back as text, written to be read.
 * Offered only while the server's `GET /providers` lists it.
 */
object WebSearch {
    /** The server's own budget is 5 s; this leaves room for the link. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The digest for [query]; throws why not (no words, the search off, the server away). */
    suspend fun digest(server: Prefs.Server, query: String): String = withContext(Dispatchers.IO) {
        val url = "${server.httpUrl}/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body.string()
            if (r.isSuccessful) return@use body
            throw IOException(runCatching { JSONObject(body).optString("error") }.getOrNull()?.ifEmpty { null }
                ?: "the server answered ${r.code}")
        }
    }
}
