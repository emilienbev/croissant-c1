package com.bleural.croissant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.data.Exchange
import com.bleural.croissant.data.Stores
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A shot: the JPEG that goes to the server, the bitmap the screen shows, the file it
 * is kept in (null until saved, or when saving failed) and when it came up, so an
 * exchange that began before it is not taken for one about it.
 */
class Photo(val jpeg: ByteArray, val bitmap: Bitmap, val file: File?, val at: Long = System.currentTimeMillis())

/** Whether the exchange on screen is about [photo]: a hold since it came up, with something to show. */
fun VoiceState.showsPhoto(photo: Photo): Boolean =
    withPhoto && askedAt >= photo.at && (phase != Phase.Idle || transcript.isNotEmpty() || parts.isNotEmpty() || error != null)

/**
 * Camera. Live: the viewfinder full bleed, four green corner brackets and
 * "Press button to capture" centred at the bottom; a double tap anywhere on it flips
 * between the HAL's two cameras, which turn the one rotating sensor themselves.
 * The side button's short press is
 * the shutter while the preview is live ([ShutterTarget]); one tick up on the wheel
 * leaves, which closes the room's panel rather than popping
 * a screen, the double press having opened it the same way. A shot releases the camera
 * (the lens parks) and develops into the 438 x 484 card at y 56 (418 until the rail went
 * and gave the card its last 20 px back), "Hold button to ask" under it, Retake as a red
 * round button (discards) and a green tick to its right (saves); neither the file nor the
 * exchange is written until the tick, and both return to the live view. A hold about the
 * shot shrinks the card to a 210 px strip in 120 ms with the green meter under it and
 * the exchange reading under that, as on Ask. [onPhoto] tells the activity what the
 * next hold should send.
 */
/**
 * The one camera, shared by this screen and the eSIM scanner. Both take it with
 * [ProcessCameraProvider.unbindAll], since the chip will not hold two previews at once,
 * so whichever page lets go bumps [released] and the other one binds again.
 *
 * Without it a double press over the scanner left a dead viewfinder: the scanner binds
 * as the camera starts sliding out, and the camera's own dispose, which lands when the
 * slide ends, unbound it again (2026-09-17).
 */
internal object CameraHolder {
    var released by mutableIntStateOf(0)
        private set

    fun release(provider: ProcessCameraProvider?) {
        provider?.unbindAll()
        released++
    }
}

/**
 * The camera itself, whatever draws it: the preview, the lens, the shot as it develops, and
 * what saving or discarding it does. Each design draws the same rig its own way, so the
 * camera behaves the same in every design.
 */
@Stable
class CameraRig internal constructor(val view: PreviewView) {
    /** The shot up on screen, or null while the preview is live. */
    var photo by mutableStateOf<Photo?>(null); internal set
    /** A transform kept the last shot itself: the live view says so for a few seconds. */
    var kept by mutableStateOf(false); internal set
    internal var towards by mutableStateOf(false)
    internal var busy = false
    internal var shootNow: () -> Unit = {}
    internal var saveNow: () -> Unit = {}
    internal var discardNow: () -> Unit = {}

    /** Turns to the other of the HAL's two cameras. */
    fun flip() { towards = !towards; Cues.play(Sound.Commit) }
    /** The shutter; a press while a shot is developing is spent. */
    fun shoot() { if (!busy) { kept = false; Cues.play(Sound.Shutter); shootNow() } }
    /** Keeps the shot, and the exchange about it if it was answered, then back to live. */
    fun save() = saveNow()
    /** Throws the shot away, back to live. */
    fun discard() = discardNow()
}

/** The one [CameraRig] for a camera page: binds while the preview is live, releases as it goes. */
@Composable
fun rememberCameraRig(voice: VoiceState, onPhoto: (Photo?) -> Unit): CameraRig {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val rig = remember { CameraRig(PreviewView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val latestVoice by rememberUpdatedState(voice)
    val latestOnPhoto by rememberUpdatedState(onPhoto)

    LaunchedEffect(Unit) { provider = ProcessCameraProvider.awaitInstance(ctx) }
    LaunchedEffect(provider, rig.towards, rig.photo == null, CameraHolder.released) {
        val p = provider ?: return@LaunchedEffect
        p.unbindAll()
        if (rig.photo != null) return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.surfaceProvider = rig.view.surfaceProvider }
        val selector = if (rig.towards) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { p.bindToLifecycle(owner, selector, preview, capture) }
            .onFailure { Log.e(TAG, "bind failed", it) }
    }
    DisposableEffect(Unit) { onDispose { CameraHolder.release(provider); latestOnPhoto(null) } }

    val scope = rememberCoroutineScope()
    rig.shootNow = {
        rig.busy = true
        capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val bytes = image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                image.close()
                Thread {
                    val p = develop(bytes, degrees)
                    ContextCompat.getMainExecutor(ctx).execute { rig.photo = p; rig.busy = false; latestOnPhoto(p) }
                }.start()
            }
            override fun onError(e: ImageCaptureException) { Log.e(TAG, "capture failed", e); rig.busy = false }
        })
    }
    rig.discardNow = { rig.photo = null; latestOnPhoto(null); Cues.play(Sound.PhotoDiscarded) }
    rig.saveNow = save@{
        val shot = rig.photo ?: return@save
        val v = latestVoice
        // The file, and the exchange about it if there is an answer, then back to live.
        val exchange = if (v.showsPhoto(shot) && v.answer.isNotEmpty()) Exchange(v.askedAt, v.transcript, v.answer) else null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = Stores.photos(ctx)
                val file = store.save(shot.jpeg)
                if (exchange != null) store.keep(file, exchange)
            }.onFailure { Log.e(TAG, "could not save the photo", it) }
        }
        rig.photo = null
        latestOnPhoto(null)
        Cues.play(Sound.PhotoSaved)
    }

    // A transform keeps the shot itself (VoiceService) and the answer is filed beside
    // it, so there is nothing left to decide: the shot goes and the live view says what
    // happened for a few seconds (2026-09-18: "nothing too complex").
    LaunchedEffect(voice.keptPhoto, voice.phase) {
        val name = voice.keptPhoto
        if (name != null && voice.phase == Phase.Idle && rig.photo != null) {
            rig.photo = null
            latestOnPhoto(null)
            rig.kept = true
            Cues.play(Sound.PhotoSaved)
            delay(KEPT_MS)
            rig.kept = false
        }
    }
    return rig
}

private const val TAG = "CameraScreen"

/** How long the live view says a transform kept the shot before it goes back to its usual line. */
private const val KEPT_MS = 4000L

/** Keeps an answered exchange about a saved [photo] beside its file, once the answer is complete (the photo page). */
@Composable
internal fun KeepExchange(photo: Photo, voice: VoiceState) {
    val ctx = LocalContext.current
    LaunchedEffect(voice.phase, photo) {
        val file = photo.file ?: return@LaunchedEffect
        if (voice.phase != Phase.Idle || !voice.showsPhoto(photo) || voice.answer.isEmpty()) return@LaunchedEffect
        val exchange = Exchange(voice.askedAt, voice.transcript, voice.answer)
        withContext(Dispatchers.IO) { runCatching { Stores.photos(ctx).keep(file, exchange) }.onFailure { Log.e(TAG, "could not keep the exchange", it) } }
    }
}

/**
 * The sensor's JPEG at half size with the rotation baked in: the model may not read
 * EXIF, 8 MP buys it nothing, and the socket frame limit is 4 MB. Around 1632x1224
 * at quality 90, a few hundred kB. Not saved: that is Save on the shot.
 */
private fun develop(jpeg: ByteArray, degrees: Int): Photo {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    val bmp = if (degrees == 0) raw else {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
    val bytes = out.toByteArray()
    Log.i(TAG, "photo ${bmp.width}x${bmp.height}, ${bytes.size / 1024} kB (sensor JPEG ${jpeg.size / 1024} kB, rotated $degrees)")
    return Photo(bytes, bmp, file = null)
}
