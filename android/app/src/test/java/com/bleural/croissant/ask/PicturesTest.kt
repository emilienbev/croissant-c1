package com.bleural.croissant.ask

import com.bleural.croissant.ask.FakeHttp.Companion.fixture
import com.bleural.croissant.data.GeneratedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cloud pictures against answers in each API's shape (test/resources/ask/), and how they land on the shelf. */
class PicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "sk-proj-fake-0000000000000000000000000000"
    private val geminiKey = "AIzaSyD-fake-0123456789abcdefghijKLMNOP"
    private val photo = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    private fun provider(id: String) = Providers.resolve(id, ProviderSpec(), "100.100.100.7")
    private fun openAi(http: FakeHttp, model: String = "gpt-image-1", log: (String) -> Unit = {}) =
        CloudPictures(provider("openai"), model, key, http.client, log = log)
    private fun gemini(http: FakeHttp, model: String) = CloudPictures(provider("gemini"), model, geminiKey, http.client)

    private fun parts(a: FakeHttp.Asked): Map<String, Pair<String?, ByteArray>> = (a.request.body as MultipartBody).parts.associate { p ->
        val d = p.headers!!["Content-Disposition"]!!
        Regex("name=\"([^\"]+)\"").find(d)!!.groupValues[1] to
            (Regex("filename=\"([^\"]+)\"").find(d)?.groupValues?.get(1) to Buffer().also { p.body.writeTo(it) }.readByteArray())
    }

    @Test fun aNewPictureAsTheSdkAskedForIt() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations", body = fixture("openai_image.json"))
        assertEquals("a picture from words", String(openAi(http).render("a lighthouse at dusk")))
        val a = http.asked.single()
        assertEquals("Bearer $key", a.request.header("Authorization"))
        val b = a.json()
        assertEquals(listOf("gpt-image-1", "a lighthouse at dusk", 1, "768x1024", "b64_json"),
            listOf("model", "prompt", "n", "size", "response_format").map { b.get(it) })
    }

    @Test fun whatTheModelRefusesIsDroppedAndAskedAgain() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations", code = 400, body = fixture("openai_image_refused.json"))
        http.on("POST", "/v1/images/generations", code = 400, body = fixture("openai_image_size_refused.json"))
        http.on("POST", "/v1/images/generations", body = fixture("openai_image.json"))
        val logged = mutableListOf<String>()
        assertEquals("a picture from words", String(openAi(http) { logged += it }.render("a lighthouse")))
        assertEquals(listOf(setOf("size", "response_format"), setOf("size"), emptySet()),
            http.asked.map { a -> setOf("size", "response_format").filter { a.json().has(it) }.toSet() })
        assertEquals(2, logged.size)
        assertTrue(logged[0], "refuses response_format=b64_json" in logged[0])
    }

    @Test fun anEditSendsThePhotoAndNoSize() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/edits", body = fixture("openai_image.json"))
        openAi(http).render("make it night", photo)
        val p = parts(http.asked.single())
        assertEquals(listOf("image", "model", "prompt", "n", "response_format"), p.keys.toList())
        assertEquals("photo.jpg", p["image"]!!.first)
        assertArrayEquals(photo, p["image"]!!.second)
        assertEquals("1", String(p["n"]!!.second))
        assertEquals("make it night", String(p["prompt"]!!.second))
        assertEquals("image/jpeg", (http.asked.single().request.body as MultipartBody).parts[0].body.contentType().toString())
    }

    @Test fun aPictureByItsAddressIsFetchedWithoutTheKey() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations", body = fixture("openai_image_url.json"))
        http.on("GET", "/private/img-abc.png", body = "png bytes", type = "image/png")
        assertEquals("png bytes", String(openAi(http, "dall-e-3").render("a lighthouse")))
        val got = http.asked.last().request
        assertEquals("images.example", got.url.host)
        assertNull(got.header("Authorization"))

        val gone = FakeHttp()
        gone.on("POST", "/v1/images/generations", body = fixture("openai_image_url.json"))
        gone.on("GET", "/private/img-abc.png", code = 403, body = "expired")
        val e = assertThrows(IOException::class.java) { runBlocking { openAi(gone, "dall-e-3").render("a lighthouse") } }
        assertFalse(e.message!!, "sig=" in e.message!!)
    }

    @Test fun geminiAsksForAnImageBack() = runBlocking {
        val http = FakeHttp()
        http.on("POST", ":generateContent", body = fixture("gemini_image.json"))
        val g = gemini(http, "gemini-2.5-flash-image")
        assertEquals("a picture from a photo", String(g.render("a lighthouse at dusk")))
        g.render("make it night", photo)
        val (made, edit) = http.asked
        assertEquals("/v1beta/models/gemini-2.5-flash-image:generateContent", made.path)
        assertEquals(geminiKey, made.request.header("x-goog-api-key"))
        assertFalse(geminiKey in made.request.url.toString())
        val config = made.json().getJSONObject("generationConfig")
        assertEquals("IMAGE", config.getJSONArray("responseModalities").getString(0))
        assertEquals("3:4", config.getJSONObject("imageConfig").getString("aspectRatio"))
        val parts = edit.json().getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertArrayEquals(photo, Base64.getDecoder().decode(parts.getJSONObject(0).getJSONObject("inlineData").getString("data")))
        assertEquals("make it night", parts.getJSONObject(1).getString("text"))
        // An edit keeps its photo's shape.
        assertFalse(edit.json().getJSONObject("generationConfig").has("imageConfig"))
    }

    @Test fun geminiWithNoPictureSaysWhatItSaidInstead() {
        val http = FakeHttp()
        http.on("POST", ":generateContent", body = fixture("gemini_no_image.json"))
        val e = assertThrows(IOException::class.java) { runBlocking { gemini(http, "gemini-2.5-flash-image").render("a lighthouse") } }
        assertEquals("Gemini sent no picture: I can't make a picture of that, but I can describe a lighthouse at dusk.", e.message)
    }

    @Test fun imagenFromWordsOnly() = runBlocking {
        val http = FakeHttp()
        http.on("POST", ":predict", body = fixture("imagen_predict.json"))
        val imagen = gemini(http, "imagen-4.0-generate-001")
        assertEquals("an imagen picture", String(imagen.render("a lighthouse")))
        val a = http.asked.single()
        assertEquals("/v1beta/models/imagen-4.0-generate-001:predict", a.path)
        assertEquals("""{"instances":[{"prompt":"a lighthouse"}],"parameters":{"sampleCount":1,"aspectRatio":"3:4"}}""",
            imagen.imagenBody("a lighthouse").toString())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { imagen.render("night", photo) } }
        val filtered = FakeHttp().apply { on("POST", ":predict", body = "{}") }
        val e = assertThrows(IOException::class.java) { runBlocking { gemini(filtered, "imagen-4.0-generate-001").render("a lighthouse") } }
        assertEquals("Imagen sent no picture (refused by its filter?)", e.message)
    }

    @Test fun theDescriptionPassAndTheAspect() {
        assertTrue(CloudPictures.wantsDescription(local = true))
        assertFalse(CloudPictures.wantsDescription(local = false))
        assertEquals("3:4", CloudPictures.aspect(768, 1024))
        assertEquals("1:1", CloudPictures.aspect(1024, 1024))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { CloudPictures(provider("ollama"), "x", null).render("a") } }
    }

    @Test fun aPictureLandsOnTheShelfAndIsCounted() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations", body = fixture("openai_image.json"))
        http.on("POST", "/v1/images/edits", code = 400, body = """{"error":{"message":"Your request was rejected by the safety system."}}""")
        val store = GeneratedStore(tmp.newFolder("generated"))
        val ledger = Ledger(null)
        val shelf = Shelf(store, CoroutineScope(SupervisorJob()), ledger, toJpeg = { "jpeg of ".toByteArray() + it })
        val id = shelf.start(openAi(http), "openai:gpt-image-1", "a lighthouse at dusk")
        // The row is there before the picture, the C1's: the shelf shows it being made, the server is never asked for it.
        assertEquals("a lighthouse at dusk", store.get(id)!!.prompt)
        assertTrue(store.get(id)!!.c1)
        val edit = shelf.start(openAi(http), "openai:gpt-image-1", "make it night", photo, named = "20261016-120000.jpg", id = "g_try001")
        assertEquals("g_try001", edit)
        assertEquals("20261016-120000.jpg", store.get(edit)!!.source)
        shelf.join()
        val made = store.get(id)!!
        assertEquals("jpeg of a picture from words", String(made.file!!.readBytes()))
        assertFalse(made.pending)
        assertEquals("400: Your request was rejected by the safety system.", store.get(edit)!!.failed)
        val row = ledger.since(0.0).single()
        assertEquals("text_to_image" to "openai:gpt-image-1", row.slot to row.model)
        assertEquals(Counts(pictures = 1.0), row.counts)
        assertFalse(shelf.has(id))
    }

    @Test fun aPictureStoppedIsGone() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations") { FakeHttp.Answer(hang = true) }
        val store = GeneratedStore(tmp.newFolder("generated"))
        val shelf = Shelf(store, CoroutineScope(SupervisorJob()), Ledger(null), toJpeg = { it })
        val id = shelf.start(openAi(http), "openai:gpt-image-1", "a lighthouse")
        while (http.asked.isEmpty()) Thread.sleep(5)
        assertTrue(shelf.has(id))
        assertTrue(shelf.stop(id))
        assertNull(store.get(id))
        shelf.join()
        assertFalse(shelf.stop(id))
        assertNotNull(http.asked.single())
    }

    @Test fun aPictureStoppedWhileItLandsIsDroppedAndNotCounted() = runBlocking {
        val http = FakeHttp()
        http.on("POST", "/v1/images/generations", body = fixture("openai_image.json"))
        val store = GeneratedStore(tmp.newFolder("generated"))
        val ledger = Ledger(null)
        val converting = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        // The picture is in and being made a JPEG, which no cancelling interrupts, when it is stopped.
        val shelf = Shelf(store, CoroutineScope(SupervisorJob()), ledger, toJpeg = { converting.countDown(); stopped.await(); it })
        val id = shelf.start(openAi(http), "openai:gpt-image-1", "a lighthouse")
        val job = launch(Dispatchers.IO) { converting.await() }
        job.join()
        assertTrue(shelf.stop(id))
        stopped.countDown()
        Thread.sleep(100)
        assertNull(store.get(id))
        assertTrue(ledger.since(0.0).isEmpty())
        assertFalse(shelf.has(id))
    }

    @Test fun aPictureThatTimesOutIsNotAskedForTwice() = runBlocking {
        val slow = FakeHttp()
        slow.on("POST", "/v1/images/generations") { FakeHttp.Answer(hang = true) }
        val client = slow.client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertThrows(IOException::class.java) { runBlocking { CloudPictures(provider("openai"), "gpt-image-1", key, client).render("a lighthouse") } }
        assertEquals(1, slow.asked.size)

        val gateway = FakeHttp()
        gateway.on("POST", "/v1/images/generations", code = 504, body = "gateway timeout")
        assertThrows(AskError::class.java) { runBlocking { openAi(gateway).render("a lighthouse") } }
        assertEquals(1, gateway.asked.size)

        // Anything else as the SDK: once more.
        val busy = FakeHttp()
        busy.on("POST", "/v1/images/generations", code = 503, body = """{"error":{"message":"overloaded"}}""")
        busy.on("POST", "/v1/images/generations", body = fixture("openai_image.json"))
        assertEquals("a picture from words", String(openAi(busy).render("a lighthouse")))
        assertEquals(2, busy.asked.size)
    }
}
