package com.bleural.croissant.metronome

import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The metronome's sounds, in the order its sound page lists them. The first three are synthesised here; the
 * others are recorded from code by `sounds/metronome.py` into `assets/metronome/<asset>/`.
 */
enum class Click(val word: String, val asset: String? = null) {
    Click("Click"),
    Woodblock("Woodblock"),
    HiHat("Hi-hat"),
    Tine("Tine", "tine"),
    Chime("Chime", "chime"),
    Bamboo("Bamboo", "bamboo"),
}

/** Which pulse a sound is played for: the bar's first beat, any other beat, or a subdivision between beats. */
enum class Pulse(val gain: Float) { Accent(1f), Beat(0.72f), Sub(0.42f) }

/**
 * The sounds, each rendered once per [Pulse] at [RATE] into a float array the engine mixes
 * in. Click, Woodblock and Hi-hat are synthesised: a few milliseconds of sine, noise and an
 * envelope. The others are 16-bit WAVs, one per pulse, read from the assets. Either way the
 * accent is pitched up as well as louder, so the one is heard as the one even at a low
 * volume, and every pulse is brought to its [Pulse.gain].
 */
internal object Clicks {
    const val RATE = 48_000

    private val cache = HashMap<Pair<Click, Pulse>, FloatArray>()

    @Synchronized
    fun of(assets: AssetManager, c: Click, p: Pulse): FloatArray = cache.getOrPut(c to p) { render(assets, c, p) }

    private fun render(assets: AssetManager, c: Click, p: Pulse): FloatArray {
        val up = if (p == Pulse.Accent) 1.35 else 1.0
        val out = if (c.asset != null) recorded(assets, c.asset, p) else when (c) {
            Click.Click -> synth(0.045) { t, _ ->
                sin(2 * PI * 1800 * up * t) * exp(-t / 0.006) + noise() * exp(-t / 0.0015) * 0.6
            }
            Click.Woodblock -> synth(0.12) { t, _ ->
                val f = 820 * up
                sin(2 * PI * f * t) * exp(-t / 0.02) + 0.35 * sin(2 * PI * f * 2.72 * t) * exp(-t / 0.008)
            }
            Click.HiHat -> {
                val tau = if (p == Pulse.Accent) 0.032 else 0.02
                var x1 = 0.0; var y1 = 0.0
                synth(0.15) { t, _ ->
                    // White noise through a first difference, twice: most of it above 5 kHz.
                    val x = noise()
                    val y = x - x1; val z = y - y1
                    x1 = x; y1 = y
                    z * exp(-t / tau)
                }
            }
            else -> error("$c has no synth")
        }
        normalise(out, 0.9f * p.gain)
        return out
    }

    /** `assets/metronome/<name>/<pulse>.wav`: 16-bit mono at [RATE]. Silence if it cannot be read. */
    private fun recorded(assets: AssetManager, name: String, p: Pulse): FloatArray = runCatching {
        pcm16(assets.open("metronome/$name/${p.name.lowercase()}.wav").use { it.readBytes() })
    }.getOrElse { Log.w("Clicks", "$name/$p: $it"); FloatArray(1) }

    /** A 16-bit mono WAV's samples: its RIFF chunks after the 12-byte header, to "data". */
    fun pcm16(wav: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= b.limit()) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "data") return FloatArray(minOf(size, b.limit() - at - 8) / 2) { b.getShort(at + 8 + 2 * it) / 32768f }
            at += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    private val rng = Random(7)
    private fun noise() = rng.nextDouble(-1.0, 1.0)

    private inline fun synth(seconds: Double, f: (t: Double, length: Double) -> Double): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i -> f(i.toDouble() / RATE, seconds).toFloat() }
    }

    private fun normalise(a: FloatArray, peak: Float) {
        var m = 1e-6f
        for (v in a) m = max(m, kotlin.math.abs(v))
        val k = peak / m
        for (i in a.indices) a[i] *= k
    }
}
