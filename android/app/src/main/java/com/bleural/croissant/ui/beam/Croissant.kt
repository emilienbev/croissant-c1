package com.bleural.croissant.ui.beam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/*
 * The croissant mascot (2026-10-27, its aerodynamic ears from 2026-10-28: lower and swept back, so
 * a move can perk them up), drawn in Compose in whatever pose its rig gives it (CroissantRig.kt).
 * The drawing's own units are its file's 480 x 640 pixels: five parts, each a white fill in a part
 * matrix, and two eyes on the face's skewed plane. The file outlines each part with a ring baked
 * from a 6.17 unit stroke; here the outline is a live stroke on the fill instead, so it is the same
 * width on screen at any size.
 */

/** A part: its fill's outline and the matrix the file places it with. */
private class Part(d: String, a: Float, b: Float, c: Float, dd: Float, e: Float, f: Float) {
    val path: Path = PathParser().parsePathString(d).toPath()
    val matrix = affine(a, b, c, dd, e, f)
}

/** The SVG matrix(a, b, c, d, e, f) as Compose's 4 x 4. */
private fun affine(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = Matrix().apply {
    values[Matrix.ScaleX] = a; values[Matrix.SkewY] = b
    values[Matrix.SkewX] = c; values[Matrix.ScaleY] = d
    values[Matrix.TranslateX] = e; values[Matrix.TranslateY] = f
}

private object Art {
    val EAR_L = Part("M259.535,146.509C273.626,147.706 302.697,225.825 293.286,252.523C284.545,277.319 255.336,318.957 227.009,300.871C207.095,288.156 233.472,144.295 259.535,146.509Z", 0.640996f, 0.656065f, -0.656065f, 0.640996f, 160.527f, -105.653f)
    val SIDE_L = Part("M327.432,142.857C329.71,143.93 345.42,192.277 333.015,224.594C318.098,263.458 274.09,287.258 270.772,285.576C230.826,265.326 193.425,196.871 194.702,177.763C195.487,166.02 251.569,107.127 327.432,142.857Z", 0.916562f, -0.0348104f, 0.0348104f, 0.916562f, -94.8784f, 98.6431f)
    val BODY = Part("M270.169,144.784C290.833,146.936 345.338,174.827 365.133,220.08C370.494,232.335 376.987,317.396 359.068,329.56C301.763,368.46 196.588,361.411 177.389,345.99C144.837,319.843 120.713,271.887 118.915,255.283C116.972,237.327 198.083,137.276 270.169,144.784Z", 0.911913f, 0.0985506f, -0.0985506f, 0.911913f, 45.0978f, 33.9586f)
    val EAR_R = Part("M259.535,146.509C280.191,144.277 316.683,225.028 307.272,251.725C298.531,276.521 244.063,291.468 215.736,273.381C193.607,259.252 238.88,148.742 259.535,146.509Z", 0.741487f, 0.539902f, -0.539902f, 0.741487f, 284.738f, -64.986f)
    val SIDE_R = Part("M341.933,136.689C345.289,138.269 351.504,162.762 350.477,186.16C349.969,197.742 346.311,207.255 342.363,217.136C327.159,255.198 263.836,284.472 257.759,281.391C209.401,256.876 196.42,193.642 194.702,177.763C193.436,166.062 266.07,100.959 341.933,136.689Z", 0.911913f, 0.0985506f, -0.0985506f, 0.911913f, 103.15f, 95.3191f)

    /** The croissant's foot in the file's pixels: a squash flattens the body onto it. */
    val FOOT = Offset(248f, 415f)

    /** The file scales the whole croissant by this and moves it; the parts sit inside. */
    val OUTER = affine(1.09025f, 0f, 0f, 1.09025f, -23.1109f, -3.26173f)
    const val OUTER_SCALE = 1.09025f
    /** Every part's own scale in its matrix, so a stroke in part units comes out even on screen. */
    const val PART_SCALE = 0.9172f

    // The hinges, in the croissant's units (inside OUTER): each ear turns on its base, hidden
    // behind its side, and stretches along its long axis (degrees clockwise from up); each side
    // turns where it meets the body.
    val EAR_L_PIVOT = Offset(133.84f, 241.07f)
    const val EAR_L_AXIS = 49.57f
    val EAR_R_PIVOT = Offset(325.96f, 280.48f)
    const val EAR_R_AXIS = 36.69f
    val SIDE_L_PIVOT = Offset(186.3f, 278.2f)
    val SIDE_R_PIVOT = Offset(282.6f, 314.8f)

    /** The eyes' planes: the face's skew, one matrix per eye, and the pupil in its units. */
    val EYES = listOf(
        affine(0.652877f, -0.383632f, 0.52038f, 0.885599f, -110.726f, 99.0941f),
        affine(0.652877f, -0.383632f, 0.52038f, 0.885599f, -172.546f, 90.2333f),
    )
    val PUPIL = Offset(236.072f, 374.672f)
    const val PUPIL_RX = 5.256f
    const val PUPIL_RY = 10.7f

    // The drawing's extent in its file: the sides at either end, the foot. TOP is where the
    // left ear's tip was before the aerodynamic ears, kept so the body stays where the pages put
    // it (2026-10-28); the tip now rests 26 units below it.
    const val LEFT = 75f
    const val RIGHT = 420f
    const val TOP = 138f
    const val BOTTOM = 415f
}

/** The croissant's width in the file's pixels, which [croissant]'s width scales from. */
internal const val CROISSANT_ART_WIDTH = Art.RIGHT - Art.LEFT

/** Its height, from [Art.TOP] to its foot. */
internal const val CROISSANT_ART_HEIGHT = Art.BOTTOM - Art.TOP

/**
 * The croissant in [pose], centred on [cx] with [Art.TOP] at [top], [width] page
 * pixels wide, its outline [inkPx] page pixels on screen. [fill] inside each part, [line] the
 * outline and the eyes.
 */
internal fun DrawScope.croissant(pose: CroissantPose, cx: Float, top: Float, width: Float, inkPx: Float, fill: Color, line: Color) {
    val k = width / CROISSANT_ART_WIDTH
    val stroke = Stroke(width = inkPx / (k * Art.OUTER_SCALE * Art.PART_SCALE), join = StrokeJoin.Round)
    fun DrawScope.part(p: Part) = withTransform({ transform(p.matrix) }) {
        drawPath(p.path, fill)
        drawPath(p.path, line, style = stroke)
    }
    withTransform({
        translate(cx - (Art.LEFT + Art.RIGHT) / 2 * k, top - Art.TOP * k)
        scale(k, k, Offset.Zero)
        translate(0f, pose.hoverY)
        scale(1f / kotlin.math.sqrt(pose.squash), pose.squash, Art.FOOT)
        transform(Art.OUTER)
    }) {
        // Back to front, as the file draws them: left ear, left side, body, right ear, right side, eyes.
        withTransform({ rotate(pose.sideL, Art.SIDE_L_PIVOT) }) {
            withTransform({
                translate(0f, pose.earLDown)
                ear(pose.earL, pose.earLStretch, Art.EAR_L_PIVOT, Art.EAR_L_AXIS)
            }) { part(Art.EAR_L) }
            part(Art.SIDE_L)
        }
        part(Art.BODY)
        withTransform({ rotate(pose.sideR, Art.SIDE_R_PIVOT) }) {
            withTransform({ ear(pose.earR, pose.earRStretch, Art.EAR_R_PIVOT, Art.EAR_R_AXIS) }) { part(Art.EAR_R) }
            part(Art.SIDE_R)
        }
        for (m in Art.EYES) {
            withTransform({
                transform(m)
                translate(Art.PUPIL.x + pose.eyeX, Art.PUPIL.y + pose.eyeY)
                scale(pose.eyeScaleX, pose.eyeScaleY, Offset.Zero)
            }) { drawOval(line, Offset(-Art.PUPIL_RX, -Art.PUPIL_RY), Size(2 * Art.PUPIL_RX, 2 * Art.PUPIL_RY)) }
        }
    }
}

/** An ear [turn]ed on its [pivot] and stretched by [stretch] along its long [axis]. */
private fun DrawTransform.ear(turn: Float, stretch: Float, pivot: Offset, axis: Float) {
    rotate(turn, pivot)
    translate(pivot.x, pivot.y)
    rotate(axis, Offset.Zero)
    scale(1f, stretch, Offset.Zero)
    rotate(-axis, Offset.Zero)
    translate(-pivot.x, -pivot.y)
}
