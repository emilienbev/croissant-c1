package com.bleural.croissant.ui.beam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import com.bleural.croissant.weather.Sky

/*
 * The weather's glyphs, drawn the set's way: a 24 grid, 2 px round lines, for the canvases (Home,
 * the Weather page), in the Weather feature's hue. A laid-out row takes the same shapes from the
 * glyph set (ui/Icons.kt, `skyIcon`).
 */


internal enum class SkyGlyph { Sun, Moon, Cloud, Rain, Snow }

/** The glyph for the sky: rain and storms the rain cloud, snow the snow cloud, cloud and fog the cloud, else the sun by day and the moon by night. */
internal fun skyGlyph(sky: Sky?, day: Boolean): SkyGlyph = when (sky) {
    Sky.Rain, Sky.Storm -> SkyGlyph.Rain
    Sky.Snow -> SkyGlyph.Snow
    Sky.Cloud, Sky.Fog -> SkyGlyph.Cloud
    else -> if (day) SkyGlyph.Sun else SkyGlyph.Moon
}

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private val RAYS = path("M12 2.5v2.3M12 19.2v2.3M2.5 12h2.3M19.2 12h2.3M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6")
private val MOON = path("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z")
private val CLOUD = path("M7 15.5h10a3.8 3.8 0 0 0 .3-7.6A5.5 5.5 0 0 0 6.6 9.6 3 3 0 0 0 7 15.5z")
private val DROPS = path("M9 19l-1 2.5M13 19l-1 2.5M17 19l-1 2.5")
/** The snow cloud's flakes: three round ones where the rain cloud has its drops, staggered as snow falls. */
private val FLAKES = listOf(Offset(8.5f, 19.8f), Offset(12.5f, 21.6f), Offset(16.5f, 19.8f))
private val LINE = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** [g] at [size] px with its top left at ([x], [y]), in [c]. The cloud alone sits 2.5 lower, centred as the rain cloud is with its drops. */
internal fun DrawScope.sky(g: SkyGlyph, x: Float, y: Float, size: Float, c: Color) {
    translate(x, y) {
        scale(size / 24f, pivot = Offset.Zero) {
            when (g) {
                SkyGlyph.Sun -> { drawCircle(c, 4.2f, Offset(12f, 12f)); drawPath(RAYS, c, style = LINE) }
                SkyGlyph.Moon -> drawPath(MOON, c)
                SkyGlyph.Cloud -> translate(0f, 2.5f) { drawPath(CLOUD, c, style = LINE) }
                SkyGlyph.Rain -> { drawPath(CLOUD, c, style = LINE); drawPath(DROPS, c, style = LINE) }
                SkyGlyph.Snow -> { drawPath(CLOUD, c, style = LINE); FLAKES.forEach { drawCircle(c, 1.4f, it) } }
            }
        }
    }
}
