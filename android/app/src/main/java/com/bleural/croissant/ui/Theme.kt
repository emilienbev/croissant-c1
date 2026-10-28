package com.bleural.croissant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bleural.croissant.BuildConfig
import java.io.File

/**
 * Sizes are measured in panel pixels on the 480 x 640 canvas, and the density override of
 * 280 makes 1 dp 1.75 px. `22.px` is 22 of those pixels as a [Dp], so the code reads in the
 * screen's own pixels.
 */
val Int.px: Dp get() = (this / 1.75f).dp

/**
 * How Power Grotesk's letters meet the pixel grid. The font's own hints
 * round vertical strokes to 3 px and horizontal ones to 2 px at the small sizes, so an l
 * or a d reads bold beside an e or an s in the same word.
 */
enum class Hinting {
    /** The font's hints, letters on whole pixels (`TextMotion.Static`). */
    Shipped,
    /** Hinting off, letters at fractional positions (`TextMotion.Animated`). */
    Unhinted,
}

/**
 * The one switch, read by [Grotesk] and so by every style in every design. Unhinted: the
 * most even on the panel, two l's side by side the same (2026-09-24).
 */
val TEXT_HINTING = Hinting.Unhinted

/**
 * Where the installer puts the owner's Power Grotesk (`./croissant font`): the app's
 * device-protected files dir, since the app starts at boot before credential-encrypted
 * storage is unlocked, and the family is read once per process. The font is commercial, so
 * the APK never carries it. A path, not a Context, so the family can be built at top level
 * like every style that starts from it.
 */
private val FONT_DIR = File("/data/user_de/0/${BuildConfig.APPLICATION_ID}/files/fonts")

/** One weight of the owner's Power Grotesk ("Light", "Regular", "Medium"), whether or not it is there. */
internal fun powerGroteskFile(weight: String) = File(FONT_DIR, "PowerGrotesk-$weight.ttf")

/**
 * Power Grotesk in three weights, or the system font when any of the three files is
 * missing. Light only at 20 sp and above: on this 280 dpi panel (1 sp = 1.75 px) its thin
 * strokes fall apart below that, which is what made the first UI's 11 and 13 sp labels
 * look broken.
 */
private val PowerGrotesk: FontFamily = run {
    val files = listOf("Light" to FontWeight.Light, "Regular" to FontWeight.Normal, "Medium" to FontWeight.Medium)
        .map { (name, weight) -> powerGroteskFile(name) to weight }
    val readable = runCatching { files.all { (f, _) -> f.isFile && f.canRead() && f.length() > 0 } }.getOrDefault(false)
    if (readable) FontFamily(files.map { (f, weight) -> Font(f, weight) }) else FontFamily.Default
}

/**
 * What every text style starts from (`Grotesk.copy(...)`): the family and [TEXT_HINTING]'s
 * rasterizing, so neither is ever set style by style.
 */
val Grotesk = TextStyle(
    fontFamily = PowerGrotesk,
    textMotion = if (TEXT_HINTING == Hinting.Unhinted) TextMotion.Animated else TextMotion.Static,
)

/** Near-black ground, warm greys, one danger red. */
object Ink {
    val Background = Color(0xFF0B0B0C)
    val Surface = Color(0xFF161618)
    val Line = Color(0xFF26262A)        // every rule
    val OnBackground = Color(0xFFF2F2F3)
    val Muted = Color(0xFF8E8A87)
    val Danger = Color(0xFFE5484D)
    /** The Ask orange, the R1 shell. Same as [Hue.Ask]. */
    val Accent = Color(0xFFFF4F00)
    /** Connected, charging, the Wi-Fi check. Same as [Hue.Camera]. */
    val Ok = Color(0xFF19B85A)
}

/**
 * One saturated hue per feature: its rail tab, header word, meters, band and action
 * button. Black text sits on a hue only on bands, buttons and the ringing panel;
 * everywhere else the hue sits on black. Settings and every page under it share the
 * neutral one.
 */
object Hue {
    val Ask = Color(0xFFFF4F00)
    /** The cassette's cyan. */
    val Recorder = Color(0xFF22D3EE)
    val Reminders = Color(0xFFF03A2E)
    val Timers = Color(0xFFFFC800)
    val Camera = Color(0xFF19B85A)
    val Calendar = Color(0xFF2F6BFF)
    /** Sky, between the Recorder's cyan and the Calendar's blue (2026-09-29). */
    val Weather = Color(0xFF3AA3F2)
    val Photos = Color(0xFF8B5CFF)
    /** Pink. */
    val Metronome = Color(0xFFFF5FA2)
    /** Lime. */
    val Translator = Color(0xFFA6D608)
    val Settings = Color(0xFFD9D6CF)
    /** Coral, clay: Atelier's (picked 2026-10-15). */
    val Atelier = Color(0xFFFF8F80)
    /** Agents', mint, the one neon none of the others has (provisional, 2026-10-21). */
    val Agents = Color(0xFF4CF2B8)
}

private fun grotesk(size: Int, height: Int, weight: FontWeight, spacing: Double = 0.0) = Grotesk.copy(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = spacing.sp,
)

/**
 * The type scale, its pixel measures divided by 1.75. Nothing on screen is smaller than
 * 15 sp (26 px): the panel is 274 x 366 dp and is read at arm's length. Light only at
 * 20 sp and above. Digits that change would want tabular figures, but Power Grotesk ships
 * no `tnum`, so a changing number is laid out so its jitter falls where it does no harm. Only the header word is
 * uppercase ([Label]); every other small word keeps its normal case in [Caption]
 * (2026-09-16).
 */
object Type {
    /** 190 px. One per screen at most: the soonest timer, a level, the ringing 0:00. */
    val Anchor = grotesk(108, 112, FontWeight.Light, -4.6)
    /** 133 px. The Home clock. */
    val Clock = grotesk(76, 80, FontWeight.Light, -2.9)
    /** 66 px. The boot logo only. */
    val Wordmark = grotesk(38, 42, FontWeight.Light, -0.9)
    /** 49 px. The focused menu band's word. Regular, not the page's Light (2026-09-15, on the device). */
    val Band = grotesk(28, 32, FontWeight.Normal, -0.5)
    /** 35 px Regular. List row titles, page titles, field values, the ringing label. */
    val Title = grotesk(20, 25, FontWeight.Normal, -0.2)
    /** 38 px Light. Row times; the page's 35 grown with the rows (2026-09-16, on the panel). */
    val Time = grotesk(22, 26, FontWeight.Light, -0.3)
    /** 30 px Regular. Row words and day lines: the page's 26 read small on the panel (2026-09-16). */
    val Row = grotesk(17, 22, FontWeight.Normal, -0.1)
    /** 35 px Light at line height 1.28. The answer on Ask. */
    val Answer = grotesk(20, 26, FontWeight.Light, -0.2)
    /** 26 px Regular at line height 1.3. Row words, body, transcript, values, day lines. */
    val Body = grotesk(15, 20, FontWeight.Normal)
    /** 26 px Medium, uppercase, +0.14 em. The header word only. */
    val Label = grotesk(15, 18, FontWeight.Medium, 2.1)
    /**
     * 26 px Medium, +0.04 em, in normal case. The header value, row and band values,
     * field labels, selectors, the tool line, the date on Home, chart numbers. Was
     * ~~uppercase at +0.14 em~~: only the header word is in capitals (2026-09-16).
     */
    val Caption = grotesk(15, 18, FontWeight.Medium, 0.6)
    /**
     * 23 px Regular, untracked. The top bar percent only: the one size under the 15 sp
     * floor, because next to 24 px glyphs a 15 sp number read too big
     * (2026-09-15). Its digits are 16 px tall, the height of the tailnet's dot block.
     */
    val Percent = grotesk(13, 16, FontWeight.Normal)

}

private val Scheme = darkColorScheme(
    primary = Ink.Accent,
    background = Ink.Background,
    surface = Ink.Surface,
    onBackground = Ink.OnBackground,
    onSurface = Ink.OnBackground,
    error = Ink.Danger,
)

private val MaterialType = Typography(
    displaySmall = Type.Wordmark,
    titleLarge = Type.Band,
    titleMedium = Type.Title,
    bodyLarge = Type.Answer,
    bodyMedium = Type.Body,
    labelSmall = Type.Caption,
)

@Composable
fun CroissantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = MaterialType, content = content)
}
