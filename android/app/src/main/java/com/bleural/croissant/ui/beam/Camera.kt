package com.bleural.croissant.ui.beam

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.bleural.croissant.VoiceState
import com.bleural.croissant.ui.glideBy
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.LocalLayerVisible
import com.bleural.croissant.ui.Photo
import com.bleural.croissant.ui.ShutterTarget
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.rememberCameraRig
import com.bleural.croissant.ui.rememberPull
import com.bleural.croissant.ui.showsPhoto
import com.bleural.croissant.ui.tappable
import kotlinx.coroutines.launch

/**
 * Camera (2026-09-28: full screen, no header word over it), on the one camera every design shares
 * ([rememberCameraRig]): the viewfinder fills the whole canvas, 480 x 640, which is the shot's own
 * 3:4 (preview and capture are both 4:3 on the R1), so it shows all the shot keeps; the top bar
 * away while it is in view. Over its foot a black shade, the prints key (a quiet key) at (82, 572)
 * and the shutter at (240, 572), a white ring round a disc in the hue. The side button's short
 * press is the shutter while the preview is live and this layer is in view; a double tap on the
 * viewfinder turns the lens; a tick up leaves through the pull. When a transform kept the last shot
 * itself, a badge says so for a while over the shade. A shot develops on the same canvas with "hold
 * to ask" in sub, the tick to keep it and the retake to throw it away; a hold asks about it, and
 * the exchange reads under it as a strip, in Ask's look, as on a photo's page.
 */
@Composable
internal fun CameraPage(voice: VoiceState, level: () -> Float, onPhoto: (Photo?) -> Unit, onGallery: () -> Unit, onBack: () -> Unit) {
    val r = rampOf(Feature.Camera)
    val rig = rememberCameraRig(voice, onPhoto)
    // A TextureView, so the preview moves with the pull; a SurfaceView ignores it. Set before
    // the rig first binds, which waits for the provider.
    remember(rig) { rig.view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE; true }
    val shot = rig.photo
    val live = shot != null && voice.showsPhoto(shot)
    if (shot == null) {
        val pull = rememberPull(onBack)
        WheelTarget { delta -> if (delta < 0) pull.stepBack(); false }
        if (LocalLayerVisible.current) ShutterTarget(rig::shoot)
        val flip by rememberUpdatedState(rig::flip)
        // A scroll that never moves, so a finger pulled down has something to pull.
        BoxWithConstraints(Modifier.fillMaxSize().background(r.ground)) {
            val h = maxHeight
            Box(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(h)) {
                    Box(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) { detectTapGestures(onDoubleTap = { flip() }) }) {
                        AndroidView(factory = { rig.view }, modifier = Modifier.fillMaxSize())
                    }
                    FootShade(r)
                    // 16 in from the left, clear over the keys' rings (their tops at 526).
                    if (rig.kept) Badge("making a picture", r, Modifier.align(Alignment.BottomStart).offset(x = 16.px, y = (-140).px))
                    QuietKeyAt(Glyphs.Photos, r, cx = 82f, cy = 572f, onClick = onGallery)
                    Shutter(r, rig::shoot)
                }
            }
        }
    } else if (!live) {
        WheelTarget { false }
        Box(Modifier.fillMaxSize().background(r.ground)) {
            val image = remember(shot.bitmap) { shot.bitmap.asImageBitmap() }
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clipToBounds())
            FootShade(r)
            // In the keys' row, left of them, on the line through their middle (574).
            Text("hold to ask", style = BeamType.Value, color = r.ink, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.px).paddingFromBaseline(top = 0.px, bottom = (640f - 584f).px))
            DangerKey(Glyphs.Retake, r, slot = 1, onClick = rig::discard)
            KnobKey(Glyphs.Check, r, onClick = rig::save)
        }
    } else {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val step = with(LocalDensity.current) { 96.px.roundToPx() }
        WheelTarget { delta ->
            scope.launch { scroll.glideBy(delta * step) }
            false
        }
        PageTop("camera", r) {
            Column(Modifier.fillMaxSize()) {
                ShotStrip(shot.bitmap, 210)
                ShotExchange(voice, null, level, r, scroll)
            }
            KeyShade(r)
            DangerKey(Glyphs.Retake, r, slot = 1, onClick = rig::discard)
            KnobKey(Glyphs.Check, r, onClick = rig::save)
        }
    }
}

/** Black rising over the picture's foot, from nothing at y 430 to 70% at the bottom, so the keys and words read on any picture. */
@Composable
private fun BoxScope.FootShade(r: Ramp) {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(210.px)
            .background(Brush.verticalGradient(listOf(r.ground.copy(alpha = 0f), r.ground.copy(alpha = 0.7f)))),
    )
}

/**
 * The shutter: a white ring r 44, 4 px, round a disc r 34 in the hue, centred at
 * (240, 572) on the page; placed from the page's foot, as the keys are.
 */
@Composable
private fun BoxScope.Shutter(r: Ramp, onShoot: () -> Unit) {
    Box(
        Modifier.align(Alignment.BottomStart).padding(start = (240f - 46f).px, bottom = (640f - 572f - 46f).px)
            .size(92.px).clip(CircleShape).tappable(onShoot),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 92f
            drawCircle(r.ink, 44f * u, style = Stroke(4f * u))
            drawCircle(r.hue, 34f * u)
        }
    }
}
