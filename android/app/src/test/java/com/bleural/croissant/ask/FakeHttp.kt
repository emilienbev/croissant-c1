package com.bleural.croissant.ask

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An OkHttp client that never reaches the network: each request is answered from what was
 * said [on] its method and path (the first answer not yet given, the last one again after
 * that), and kept in [asked]. The answers are the recorded fixtures in test/resources/ask/.
 */
class FakeHttp {
    class Asked(val request: Request, val body: String) {
        val path: String get() = request.url.encodedPath
        fun json(): JSONObject = JSONObject(body)
    }

    class Answer(val code: Int = 200, val body: String = "", val type: String = "application/json", val hang: Boolean = false)

    private class Route(val method: String, val path: String, val answer: (Asked) -> Answer)

    val asked = CopyOnWriteArrayList<Asked>()
    private val routes = mutableListOf<Route>()
    private val given = mutableMapOf<Route, Int>()

    fun on(method: String, path: String, code: Int = 200, body: String = "", type: String = "application/json") =
        on(method, path) { Answer(code, body, type) }

    fun on(method: String, path: String, answer: (Asked) -> Answer) {
        routes += Route(method, path, answer)
    }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> serve(chain) }).build()

    private fun serve(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val a = Asked(req, Buffer().also { req.body?.writeTo(it) }.readUtf8())
        asked += a
        val matching = synchronized(this) {
            val all = routes.filter { it.method == req.method && a.path.endsWith(it.path) }
            all.firstOrNull { (given[it] ?: 0) == 0 }?.also { given[it] = 1 } ?: all.lastOrNull()
        } ?: throw IOException("nothing recorded for ${req.method} ${a.path}")
        val answer = matching.answer(a)
        if (answer.hang) {
            // As a call left waiting on a slow model: only its cancelling ends it.
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("Canceled")
        }
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(answer.code).message(if (answer.code == 200) "OK" else "Error")
            .body(answer.body.toResponseBody(answer.type.toMediaType())).build()
    }

    companion object {
        fun fixture(name: String): String = File("src/test/resources/ask/$name").readText()
        val SSE = "text/event-stream"
        val NDJSON = "application/x-ndjson"
    }
}
