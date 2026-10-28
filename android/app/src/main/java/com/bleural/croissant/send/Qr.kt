package com.bleural.croissant.send

import android.util.Log
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** A QR code as its grid of modules: [side] on a side, true where a module is dark. */
class Code(val side: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * side + x]
}

/**
 * The URL as a code the screen can draw.
 *
 * `Encoder` rather than `QRCodeWriter` because the writer scales its matrix into a
 * pixel bitmap and pads it, and what the screen wants is the bare module grid so it can
 * pick a whole number of pixels per module. A Send URL is about forty characters, which
 * is a version 3 code, 29 modules on a side.
 *
 * Error correction M, not L. L would be a smaller code and the screen is a perfect
 * target with no print noise, but the R1's glass catches a lot of light and a code
 * being read at arm's length under a lamp has to survive a highlight across it.
 */
fun encode(text: String): Code? = try {
    val m = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    val side = m.width
    Code(side, BooleanArray(side * side) { m.get(it % side, it / side).toInt() == 1 })
} catch (e: Exception) {
    Log.w("Qr", "cannot encode: $e"); null
}
