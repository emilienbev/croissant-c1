package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Links
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.ControlState
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.Ink
import com.bleural.croissant.ui.LocalSheet
import com.bleural.croissant.ui.PowerAction
import com.bleural.croissant.ui.SheetAction
import com.bleural.croissant.ui.SheetState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.controlDrag
import com.bleural.croissant.ui.held
import com.bleural.croissant.ui.tappable
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * What hangs over every Beam screen from the shell: the control centre and the sheet any screen
 * asks for through [LocalSheet]. Nothing is drawn round either: the page under them goes dark and
 * the controls sit on it. Their words and keys are white, grey and the danger red, the same over
 * any feature; [Ramp] only carries those.
 */

/** What slides down with the control centre, page pixels from the top of the canvas: everything it draws, and the drag's measure. */
private const val PANEL = 420f

/**
 * The two levels' left edges and the six keys' centres, 32 clear of either side: two columns 110
 * apart, three rows 110 apart, the middle row on the levels' middle (227).
 */
private const val VOLUME_X = 32
private const val DISPLAY_X = 116
private const val KEYS_LEFT = 296f
private const val KEYS_RIGHT = 406f
private const val KEYS_TOP = 117f
private const val KEYS_MID = 227f
private const val KEYS_BOTTOM = 337f

/** A level's segments: eight, 56 x 22, 30 apart, the top one at y 86, the bottom one ending at 318. */
private const val SEGMENTS = 8
private const val SEG_TOP = 86f
private const val SEG_W = 56f
private const val SEG_H = 22f
private const val SEG_PITCH = 30f
private const val SEG_SPAN = SEG_PITCH * (SEGMENTS - 1) + SEG_H
/** A level's glyph's top, 18 under its last segment. */
private const val LEVEL_GLYPH = 336f

/**
 * The control centre, with the room's pull and state ([ControlState]): the page
 * under it dimmed to 10% (black at 90%) and what it holds sliding down from the top. Sound and
 * Display as two levels of eight stacked segments (56 x 22, 8 apart, lit white from the bottom,
 * from y 86 to 318) with their glyphs under them at 336; six round keys of 84 to their right,
 * 110 apart both ways, centred on 117, 227 and 337: Wi-Fi and Bluetooth, the tailnet and Sound,
 * the battery and Power. Wi-Fi, Bluetooth, the tailnet and Sound are white with a black glyph
 * when on and a grey ring when off; Power a red ring; the battery its level as a ring (white,
 * green while it charges, red when low) round the percent. Everything 32 px clear of the sides.
 * A tap or a drag on a level sets it (a muted volume's segments are grey); a tap on Wi-Fi,
 * Bluetooth or the tailnet flips it and a long press opens its page; Sound mutes; the battery
 * opens Battery; Power offers Sleep, Restart and Power off through the sheet. A tap on the dark
 * closes it, a drag on what it holds pushes it back. The wheel does nothing while it is open.
 *
 * It was 34 px lower, with four keys and a top bar of its own over the dark, until the top bar
 * went (2026-10-01): the tailnet and the battery it showed are keys here now.
 */
@Composable
internal fun BeamControlCenter(
    state: ControlState,
    device: DeviceStatus,
    volume: Int,
    brightness: Int,
    muted: Boolean,
    r: Ramp,
    onVolume: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onBluetooth: (Boolean) -> Unit,
    onTailnet: (Boolean) -> Unit,
    onOpenWifi: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onOpenTailnet: () -> Unit,
    onOpenBattery: () -> Unit,
    onPower: (PowerAction) -> Unit,
) {
    val sheet = LocalSheet.current
    // Whether it is on screen at all; the number itself is read in draw and layer blocks only.
    val showing by remember(state) { derivedStateOf { state.shown.value > 0f } }
    Box(Modifier.fillMaxSize()) {
        if (showing) Box(Modifier.fillMaxSize().drawBehind { drawRect(r.ground, alpha = 0.9f * state.shown.value) }.tappable { state.close() })
        Box(
            Modifier.fillMaxWidth().height(PANEL.px)
                .graphicsLayer { translationY = size.height * (state.shown.value - 1f) }
                .onSizeChanged { state.height = it.height }
                .controlDrag(state),
        ) {
            LevelKey(VOLUME_X, if (muted) Glyphs.SoundOff else Glyphs.Sound, volume, r, faded = muted) { if (muted) onMute(false); onVolume(it) }
            LevelKey(DISPLAY_X, Glyphs.Display, brightness, r, faded = false, onChange = onBrightness)
            RoundKey(KEYS_LEFT, KEYS_TOP, Glyphs.Wifi, if (device.wifiEnabled) r.ink else null, r.sub, r, onClick = {
                Links.touched(); onWifi(!device.wifiEnabled); Cues.play(if (device.wifiEnabled) Sound.SwitchOff else Sound.SwitchOn)
            }, onLongClick = onOpenWifi)
            RoundKey(KEYS_RIGHT, KEYS_TOP, Glyphs.Bluetooth, if (device.bluetoothOn) r.ink else null, r.sub, r, onClick = {
                onBluetooth(!device.bluetoothOn); Cues.play(if (device.bluetoothOn) Sound.SwitchOff else Sound.SwitchOn)
            }, onLongClick = onOpenBluetooth)
            RoundKey(KEYS_LEFT, KEYS_MID, Glyphs.Tailnet, if (device.tailnetUp) r.ink else null, r.sub, r, onClick = {
                onTailnet(!device.tailnetUp); Cues.play(if (device.tailnetUp) Sound.SwitchOff else Sound.SwitchOn)
            }, onLongClick = onOpenTailnet)
            // Muting sounds its switch before the gain goes, unmuting after it is back: both are heard.
            RoundKey(KEYS_RIGHT, KEYS_MID, if (muted) Glyphs.SoundOff else Glyphs.Sound, if (muted) null else r.ink, r.sub, r, onClick = {
                if (muted) { onMute(false); Cues.play(Sound.SwitchOn) } else { Cues.play(Sound.SwitchOff); onMute(true) }
            })
            BatteryKey(KEYS_LEFT, KEYS_BOTTOM, device, r, onClick = onOpenBattery)
            RoundKey(KEYS_RIGHT, KEYS_BOTTOM, Glyphs.Power, null, r.danger, r, onClick = {
                sheet.show(listOf(
                    SheetAction("sleep", r.ink, Glyphs.Moon) { state.close(null); onPower(PowerAction.Sleep) },
                    SheetAction("restart", r.ink, Glyphs.Restart) { Cues.play(Sound.Commit); onPower(PowerAction.Reboot) },
                    SheetAction("power off", DANGER, Glyphs.Power) { Cues.play(Sound.Destroy); onPower(PowerAction.PowerOff) },
                ))
            })
        }
    }
    if (state.open) WheelTarget { false }
}

/**
 * A level of the control centre at x [x]: eight segments of 56 x 22 from y 86, 30 apart, lit
 * white from the bottom (grey when [faded]), the rest faint; its glyph 32 in white (grey when
 * faded) under them at y 336. A tap or a drag on it, or 16 px past either end, sets it; a
 * segment tapped lights up to itself.
 */
@Composable
private fun LevelKey(x: Int, glyph: ImageVector, value: Int, r: Ramp, faded: Boolean, onChange: (Int) -> Unit) {
    val latest = rememberUpdatedState(onChange)
    val at = { y: Float, u: Float -> (100 * (1 - (y / u - 16f) / SEG_SPAN)).roundToInt().coerceIn(0, 100) }
    val lit = ceil(value.coerceIn(0, 100) * SEGMENTS / 100f - 0.001f).toInt()
    val on = if (faded) r.sub else r.ink
    Box(
        Modifier.offset(x = x.px, y = (SEG_TOP - 16f).px).size(SEG_W.px, (SEG_SPAN + 32f).px)
            .pointerInput(Unit) { detectTapGestures { latest.value(at(it.y, 1.px.toPx())); Cues.play(Sound.Commit) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { Cues.play(Sound.Commit) }) { c, _ -> c.consume(); latest.value(at(c.position.y, 1.px.toPx())) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / SEG_W
            for (i in 0 until SEGMENTS) {
                // i counts from the bottom.
                val top = 16f + (SEGMENTS - 1 - i) * SEG_PITCH
                drawRoundRect(if (i < lit) on else r.deep, Offset(0f, top * u), Size(SEG_W * u, SEG_H * u), CornerRadius(9f * u))
            }
        }
    }
    Icon(glyph, null, Modifier.offset(x = (x + 12).px, y = LEVEL_GLYPH.px).size(32.px), on)
}

/**
 * A round key of the control centre, 84 across, centred on ([cx], [cy]): filled in [fill]
 * with a black glyph 40 when given (a switch that is on), else a 3 px ring and the glyph in
 * [ring] (a switch that is off, grey; Power, red).
 */
@Composable
private fun RoundKey(cx: Float, cy: Float, glyph: ImageVector, fill: Color?, ring: Color, r: Ramp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier.offset(x = (cx - 42f).px, y = (cy - 42f).px).size(84.px).clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(3.px, ring, CircleShape))
            .held(onClick, onLongClick),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(40.px), fill?.let { r.ground } ?: ring) }
}

/**
 * The battery as a key of the control centre, 84 across, centred on ([cx], [cy]): a 3 px ring
 * faint all round and lit clockwise from the top to the level, the percent in [BeamType.Value]
 * in its middle. White; green while it charges; the danger red, the percent too, at 15% and
 * under off the charger. Not read yet, the faint ring alone. A tap opens Battery.
 */
@Composable
private fun BatteryKey(cx: Float, cy: Float, device: DeviceStatus, r: Ramp, onClick: () -> Unit) {
    val pct = device.batteryPct
    val low = pct in 0..15 && !device.charging
    val lit = when {
        device.charging -> Ink.Ok
        low -> r.danger
        else -> r.ink
    }
    Box(
        Modifier.offset(x = (cx - 42f).px, y = (cy - 42f).px).size(84.px).clip(CircleShape).held(onClick, null),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.px.toPx()
            val inset = Offset(w / 2, w / 2)
            val ring = Size(size.width - w, size.height - w)
            drawArc(r.deep, 0f, 360f, false, inset, ring, style = Stroke(w))
            if (pct >= 0) drawArc(lit, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, inset, ring, style = Stroke(w, cap = StrokeCap.Round))
        }
        if (pct >= 0) Text("$pct", style = BeamType.Value, color = if (low) r.danger else r.ink, maxLines = 1)
    }
}

/**
 * The sheet any screen asks for through [LocalSheet] (a borrowed page, the control centre's
 * Power), drawn as the kit's [Sheet] in [r]: its words lowercase, an action in [DANGER] in
 * danger, the rest in the hue. What it asks, when it says
 * ([SheetState.show]'s question and line), the kit sets over the words. It goes when what it
 * asked about goes ([under]).
 */
@Composable
internal fun BeamSheetHost(state: SheetState, under: Any?, r: Ramp) {
    LaunchedEffect(under) { state.close() }
    val spec = state.current ?: return
    val bands = spec.actions.map { a ->
        SheetBand(a.word.lowercase(), if (a.hue == DANGER) DANGER else null) { state.act(a) }
    }
    Sheet(r, bands, onCancel = state::dismiss, question = spec.question, line = spec.line)
}
