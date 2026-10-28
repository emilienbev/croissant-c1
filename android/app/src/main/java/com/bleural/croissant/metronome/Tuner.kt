package com.bleural.croissant.metronome

import android.content.Context
import android.os.SystemClock
import com.bleural.croissant.Prefs
import com.bleural.croissant.audio.AudioCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A tuning: its name and its strings, low to high, as MIDI notes. */
class Tuning(val name: String, val strings: IntArray)

enum class Instrument(val word: String, val tunings: List<Tuning>) {
    Guitar("guitar", listOf(
        Tuning("standard", intArrayOf(40, 45, 50, 55, 59, 64)),
        Tuning("drop D", intArrayOf(38, 45, 50, 55, 59, 64)),
        Tuning("DADGAD", intArrayOf(38, 45, 50, 55, 57, 62)),
        Tuning("open G", intArrayOf(38, 43, 50, 55, 59, 62)),
    )),
    Bass("bass", listOf(
        Tuning("standard", intArrayOf(28, 33, 38, 43)),
        Tuning("drop D", intArrayOf(26, 33, 38, 43)),
        Tuning("5 string", intArrayOf(23, 28, 33, 38, 43)),
    )),
    Ukulele("ukulele", listOf(
        Tuning("standard", intArrayOf(67, 60, 64, 69)),
        Tuning("low G", intArrayOf(55, 60, 64, 69)),
    )),
}

/** What the tuner hears: the pitch, the string it is nearest (or held to), and how far off it is. */
data class Reading(val hz: Float, val string: Int, val cents: Float)

data class TunerState(
    val instrument: Instrument = Instrument.Guitar,
    /** The tuning chosen for each instrument, by its index in [Instrument.tunings]. */
    val tunings: List<Int> = List(Instrument.entries.size) { 0 },
    /** The string a tap held the tuner to, or null to follow the nearest. */
    val locked: Int? = null,
    val reading: Reading? = null,
) {
    val tuning: Tuning get() = instrument.tunings[tunings[instrument.ordinal]]
}

/** A MIDI note's name without its octave, and its octave: 40 is E and 2. */
fun noteName(midi: Int): String = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12]
fun noteOctave(midi: Int): Int = midi / 12 - 1

/**
 * The tuner: the always-hot microphone's chunks ([feed], from the service's capture thread
 * while listening) through YIN, the autocorrelation pitch tracker (de Cheveigné and Kawahara,
 * 2002), 2048 samples at 16 kHz analysed every 60 ms on a thread of its own. That window
 * reaches down to a 5-string bass's low B (31 Hz). A4 is 440.
 *
 * The note shown is a string's, never the chromatic nearest: the nearest string of the
 * tuning, or the one a tap held it to, with the cents from it. Quiet input clears the
 * reading after [HOLD_MS], so a string ringing out does not jump to noise.
 */
class Tuner(private val ctx: Context) {
    companion object {
        private const val SR = AudioCapture.SAMPLE_RATE
        private const val N = 2048
        private const val W = N / 2
        private const val TAU_MIN = SR / 1400
        private const val TAU_MAX = SR / 28
        private const val THRESHOLD = 0.15f
        private const val QUIET = 0.006f
        private const val HOLD_MS = 700L
        /** Chunks of 20 ms between analyses. */
        private const val EVERY = 3

        fun hzOf(midi: Int): Float = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<TunerState> = _state

    fun setInstrument(i: Instrument) = update { it.copy(instrument = i, locked = null, reading = null) }

    /** The current instrument's tuning, by its index in [Instrument.tunings]. */
    fun setTuning(i: Int) = update {
        val list = it.tunings.toMutableList()
        list[it.instrument.ordinal] = i.coerceIn(0, it.instrument.tunings.lastIndex)
        it.copy(tunings = list, locked = null, reading = null)
    }

    /** A tap on a string: hold the tuner to it, or let go if it was held already. */
    fun lock(string: Int) {
        _state.value = _state.value.let { it.copy(locked = if (it.locked == string) null else string) }
    }

    private inline fun update(f: (TunerState) -> TunerState) {
        val s = f(_state.value)
        _state.value = s
        Prefs.setTuner(ctx, "${s.instrument.name},${s.tunings.joinToString(",")}")
    }

    private fun load(): TunerState = runCatching {
        val p = Prefs.tuner(ctx)!!.split(',')
        val inst = Instrument.valueOf(p[0])
        val t = Instrument.entries.mapIndexed { i, ins -> p[i + 1].toInt().coerceIn(0, ins.tunings.lastIndex) }
        TunerState(inst, t)
    }.getOrDefault(TunerState())

    // ---- listening ---------------------------------------------------------

    private val ring = FloatArray(N)
    private var chunks = 0
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tuner").apply { isDaemon = true } }
    @Volatile private var listening = false
    private var heardAt = 0L
    private var smoothCents = 0f
    private var smoothString = -1

    fun listen(on: Boolean) {
        listening = on
        if (!on) _state.value = _state.value.copy(reading = null)
    }

    /** One capture chunk, 16-bit little-endian mono at 16 kHz. Called on the capture thread. */
    fun feed(buf: ByteArray, n: Int) {
        if (!listening) return
        val count = n / 2
        System.arraycopy(ring, count, ring, 0, N - count)
        for (i in 0 until count) {
            val v = (buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)
            ring[N - count + i] = v / 32768f
        }
        if (++chunks % EVERY != 0 || !busy.compareAndSet(false, true)) return
        val frame = ring.copyOf()
        worker.execute {
            try { heard(yin(frame)) } finally { busy.set(false) }
        }
    }

    private fun heard(hz: Float?) {
        if (!listening) return
        val now = SystemClock.uptimeMillis()
        val s = _state.value
        if (hz == null) {
            if (s.reading != null && now - heardAt > HOLD_MS) _state.value = s.copy(reading = null)
            return
        }
        heardAt = now
        val strings = s.tuning.strings
        val string = s.locked?.coerceIn(0, strings.lastIndex)
            ?: strings.indices.minBy { abs(centsOff(hz, strings[it])) }
        val cents = centsOff(hz, strings[string])
        // Settles the needle without lagging a real change: a new string or a jump starts afresh.
        smoothCents = if (string == smoothString && abs(cents - smoothCents) < 40f) smoothCents * 0.55f + cents * 0.45f else cents
        smoothString = string
        _state.value = s.copy(reading = Reading(hz, string, smoothCents))
    }

    private fun centsOff(hz: Float, midi: Int) = (1200.0 * ln(hz / hzOf(midi)) / ln(2.0)).toFloat()

    /** YIN over [x]: the frequency, or null when it is too quiet or nothing is periodic enough. */
    private fun yin(x: FloatArray): Float? {
        var e = 0f
        for (v in x) e += v * v
        if (sqrt(e / x.size) < QUIET) return null
        val d = FloatArray(TAU_MAX + 2)
        for (tau in 1..TAU_MAX + 1) {
            var sum = 0f
            for (j in 0 until W) { val diff = x[j] - x[j + tau]; sum += diff * diff }
            d[tau] = sum
        }
        // The cumulative mean normalised difference.
        d[0] = 1f
        var running = 0f
        for (tau in 1..TAU_MAX + 1) {
            running += d[tau]
            d[tau] = if (running > 0f) d[tau] * tau / running else 1f
        }
        var tau = TAU_MIN
        while (tau <= TAU_MAX) {
            if (d[tau] < THRESHOLD) {
                while (tau + 1 <= TAU_MAX && d[tau + 1] < d[tau]) tau++
                break
            }
            tau++
        }
        if (tau > TAU_MAX) return null
        // A parabola through the minimum and its neighbours for the fraction of a sample.
        val a = d[tau - 1]; val b = d[tau]; val c = d[tau + 1]
        val den = a - 2 * b + c
        val t = if (den != 0f) tau + (a - c) / (2 * den) else tau.toFloat()
        val hz = SR / t
        return if (hz.isFinite() && hz > 0f) (hz * 100).roundToInt() / 100f else null
    }
}
