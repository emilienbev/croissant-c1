package com.bleural.croissant.ui

import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.bleural.croissant.esim.ActivationCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import zxingcpp.BarcodeReader

private const val TAG = "QrScan"

/** How long a QR code that is not an activation code keeps being named after it leaves the frame. */
private const val REJECT_MS = 1500L

/**
 * The floor between two reads. Left alone the reader runs on every frame the camera
 * sends, 1.4 cores of a 1280x960 search; ten tries a second find a code just as fast
 * and leave the panel to the preview (measured on the R1, 2026-09-17).
 */
private const val DECODE_MS = 100L

/**
 * The scanner itself, whatever draws it: the rear camera bound with a QR reader, what it has
 * seen. Each design draws the same scanner its own way.
 * The first code [rememberQrScanner]'s parse accepts goes to its onCode; the wheel is the caller's.
 */
@Stable
class QrScanner internal constructor(val view: PreviewView) {
    /** Null while the camera starts, false when it could not be opened. */
    var opened by mutableStateOf<Boolean?>(null); internal set
    /** A QR code the caller cannot use (not an eSIM code, not a Wi-Fi code) is in the frame. */
    var notACode by mutableStateOf(false); internal set
    /** The first code wins: frames keep arriving while the add page slides in. */
    internal val took = AtomicBoolean(false)

    /** Read codes again after the one taken turned out no use (a server that does not answer). */
    fun readAgain() { took.set(false) }
}

/** The eSIM's scanner: an activation code. */
@Composable
fun rememberEsimScanner(onCode: (ActivationCode) -> Unit): QrScanner = rememberQrScanner(ActivationCode::parse, onCode)

/** A scanner for whatever [parse] reads out of a QR code's text (null: not one of ours). */
@Composable
fun <T : Any> rememberQrScanner(parse: (String) -> T?, onCode: (T) -> Unit): QrScanner {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // A screen pushed over this one (the camera, by a double press) takes the lens with
    // it; binding again on the way back is what brings the viewfinder to life.
    val visible = LocalLayerVisible.current
    val code = rememberUpdatedState(onCode)

    val rejectedAt = remember { AtomicLong(0L) }
    val frames = remember { AtomicLong(0L) }
    val readAt = remember { AtomicLong(0L) }

    val scanner = remember {
        QrScanner(PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        })
    }
    val view = scanner.view
    val took = scanner.took
    // 1280x720 rather than CameraX's 640x480 default: a code the fixed-focus lens sees
    // soft needs the pixels. One frame at a time, the newest.
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val reader = remember {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                // A QR code's finder patterns carry its orientation, so rotating the
                // image buys nothing; inverted and downscaled reads do.
                tryRotate = false,
                tryInvert = true,
                tryDownscale = true,
                maxNumberOfSymbols = 1,
            ),
        )
    }
    val decode = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        provider = runCatching { ProcessCameraProvider.awaitInstance(ctx) }
            .onFailure { Log.e(TAG, "no camera provider", it); scanner.opened = false }
            .getOrNull()
    }
    // [CameraHolder.released] as well as [visible]: the camera screen's dispose lands
    // after this one has bound, and took the lens with it until it woke us again.
    LaunchedEffect(provider, visible, CameraHolder.released) {
        val p = provider ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val main = ContextCompat.getMainExecutor(ctx)
        analysis.setAnalyzer(decode) { image ->
            val now = SystemClock.uptimeMillis()
            if (took.get() || now - readAt.get() < DECODE_MS) {
                image.close()
                return@setAnalyzer
            }
            readAt.set(now)
            // One line for the first frame: it says the reader is being fed, and at what size.
            if (frames.getAndIncrement() == 0L) Log.i(TAG, "reading ${image.width}x${image.height} frames")
            val text = runCatching { reader.read(image).firstOrNull()?.text }
                .onFailure { Log.e(TAG, "could not read the frame", it) }
                .getOrNull()
            image.close()
            if (text == null) return@setAnalyzer
            val parsed = parse(text)
            if (parsed == null) {
                rejectedAt.set(SystemClock.uptimeMillis())
                main.execute { scanner.notACode = true }
            } else if (took.compareAndSet(false, true)) {
                Log.i(TAG, "read a code")
                main.execute { code.value(parsed) }
            }
        }
        runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            .onSuccess { scanner.opened = true }
            .onFailure { Log.e(TAG, "bind failed", it); scanner.opened = false }
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            CameraHolder.release(provider)
            decode.shutdown()
        }
    }
    // The words stay while the code is in the frame and clear once it has left.
    LaunchedEffect(scanner.notACode) {
        if (!scanner.notACode) return@LaunchedEffect
        while (SystemClock.uptimeMillis() - rejectedAt.get() < REJECT_MS) delay(300)
        scanner.notACode = false
    }
    return scanner
}
