package com.bleural.croissant.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.bleural.croissant.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import kotlin.math.roundToInt

/** Beats over the note that gets the beat: 7/8. The tempo counts that note. */
data class TimeSig(val beats: Int, val note: Int) {
    override fun toString() = "$beats/$note"

    companion object {
        val BEATS = 1..16
        val NOTES = listOf(2, 4, 8, 16)
        val COMMON = listOf(TimeSig(2, 4), TimeSig(3, 4), TimeSig(4, 4), TimeSig(5, 4), TimeSig(6, 4),
            TimeSig(3, 8), TimeSig(5, 8), TimeSig(6, 8), TimeSig(7, 8), TimeSig(12, 8))
    }
}

/** How many clicks to a beat, in the order a tap steps them. */
enum class Subdivision(val per: Int) { Quarter(1), Eighths(2), Triplets(3), Sixteenths(4) }

data class MetronomeState(
    val bpm: Int = 96,
    val sig: TimeSig = TimeSig(4, 4),
    val sub: Subdivision = Subdivision.Quarter,
    val click: Click = Click.Click,
    val playing: Boolean = false,
)

/**
 * The metronome: its settings, kept in prefs, and the engine that plays them.
 *
 * The clicks are mixed sample-accurately into one [AudioTrack] fed by its own thread, so
 * the timing is the audio clock's, not a scheduler's: every pulse lands on the frame it was
 * computed for, whatever the UI or the GC is doing. A change of tempo, signature or sound
 * takes effect from the next pulse. It keeps playing with the screen off (the service's
 * wake lock keeps the CPU); what stops it is the UI leaving its screen.
 *
 * The beat being heard is read off the track's playback head ([beatNow]), so the dots on
 * screen follow what comes out of the speaker rather than what was last written.
 *
 * Practice is the time spent playing, per day, kept as one line in prefs ([Prefs.practiceLog]):
 * a run is credited to the day it stops on.
 */
class Metronome(private val ctx: Context) {
    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 208
        private const val TAG = "Metronome"
        private const val RATE = Clicks.RATE
        /** 10 ms written at a time: how late a change of tempo can be, on top of the track's own buffer. */
        private const val BLOCK = RATE / 100
        /** A tap this long after the last starts a new tap tempo. */
        private const val TAP_RESET_MS = 2000L
        /** Three years of days played is all the log keeps: a few tens of kilobytes at most. */
        private const val KEEP_DAYS = 1100
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MetronomeState> = _state

    /** Linear gain, the Sound level on the ringtone's curve. Written by Core, read by the mixer. */
    @Volatile var gain = 1f

    @Volatile private var player: Player? = null

    fun toggle() = if (_state.value.playing) stop() else start()

    fun start() {
        if (player != null) return
        startedAt = SystemClock.elapsedRealtime()
        player = Player().also { it.start() }
        _state.value = _state.value.copy(playing = true)
    }

    fun stop() {
        val p = player ?: return
        player = null
        p.finish()
        addPractice(SystemClock.elapsedRealtime() - startedAt)
        _state.value = _state.value.copy(playing = false)
    }

    fun setBpm(v: Int) = update { it.copy(bpm = v.coerceIn(MIN_BPM, MAX_BPM)) }
    fun nudge(d: Int) = setBpm(_state.value.bpm + d)
    fun setSig(s: TimeSig) = update { it.copy(sig = s) }
    fun nextSub() = update { it.copy(sub = Subdivision.entries[(it.sub.ordinal + 1) % Subdivision.entries.size]) }
    fun setClick(c: Click) = update { it.copy(click = c) }

    private val auditions = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "audition").apply { isDaemon = true } }

    /** One accent of [c] on its own, to hear it while picking; playing, the next pulse is the preview. */
    fun audition(c: Click) {
        if (player != null) return
        val g = gain
        auditions.execute {
            val snd = Clicks.of(ctx.assets, c, Pulse.Accent).let { a -> FloatArray(a.size) { a[it] * g } }
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(snd.size * 4)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            }.getOrElse { Log.e(TAG, "audition failed", it); return@execute }
            try {
                track.write(snd, 0, snd.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                Thread.sleep(snd.size * 1000L / RATE + 60)
            } catch (e: Exception) {
                Log.e(TAG, "audition failed", e)
            } finally {
                track.release()
            }
        }
    }

    private val taps = ArrayDeque<Long>()

    /** One tap of the tap tempo: from the second tap on, the tempo is the mean of the last four gaps. */
    fun tap() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > TAP_RESET_MS) taps.clear()
        taps.addLast(now)
        while (taps.size > 5) taps.removeFirst()
        if (taps.size < 2) return
        val gap = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        setBpm((60_000 / gap).roundToInt())
    }

    /** The beat of the bar being heard now, 0 first, or -1 when nothing is playing. */
    fun beatNow(): Int = player?.beatNow() ?: -1

    // ---- practice ----------------------------------------------------------

    private var startedAt = 0L

    /** Today's time spent playing, the current run included. */
    fun practiceMs(): Long = practiceLog()[LocalDate.now()] ?: 0L

    /** Every day played and its milliseconds, oldest first, the current run in today's. */
    fun practiceLog(): Map<LocalDate, Long> {
        val log = readLog()
        if (player != null) log.merge(LocalDate.now(), SystemClock.elapsedRealtime() - startedAt, Long::plus)
        return log
    }

    private fun addPractice(ms: Long) {
        val log = readLog()
        log.merge(LocalDate.now(), ms, Long::plus)
        val days = log.entries.toList().takeLast(KEEP_DAYS)
        Prefs.setPracticeLog(ctx, days.joinToString(";") { "${it.key} ${it.value}" })
    }

    /** The log as the prefs keep it, "2026-09-21 1500000;2026-09-22 900000", sorted. */
    private fun readLog(): java.util.TreeMap<LocalDate, Long> {
        val log = java.util.TreeMap<LocalDate, Long>()
        Prefs.practiceLog(ctx).split(';').forEach { e ->
            val (d, ms) = e.split(' ').takeIf { it.size == 2 } ?: return@forEach
            runCatching { log[LocalDate.parse(d)] = ms.toLong() }
        }
        return log
    }

    // ---- settings ----------------------------------------------------------

    private inline fun update(f: (MetronomeState) -> MetronomeState) {
        val s = f(_state.value)
        _state.value = s
        Prefs.setMetronome(ctx, "${s.bpm},${s.sig.beats},${s.sig.note},${s.sub.name},${s.click.name}")
    }

    private fun load(): MetronomeState = runCatching {
        val p = Prefs.metronome(ctx)!!.split(',')
        // A sound since removed (the cowbell 2026-09-22, marimba and beep 2026-10-20) falls back to the click, keeping the rest.
        val click = runCatching { Click.valueOf(p[4]) }.getOrDefault(Click.Click)
        MetronomeState(p[0].toInt(), TimeSig(p[1].toInt(), p[2].toInt()), Subdivision.valueOf(p[3]), click)
    }.getOrDefault(MetronomeState())

    // ---- the engine --------------------------------------------------------

    private inner class Player : Thread("metronome") {
        @Volatile private var running = true
        private val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), BLOCK * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        /** The last few beats written: the frame each starts on and its place in the bar. */
        private val beatFrames = LongArray(8) { Long.MAX_VALUE }
        private val beatIndex = IntArray(8)
        private var beatHead = 0

        fun finish() { running = false }

        fun beatNow(): Int {
            // The track may be released under us by a stop on the way.
            val head = (runCatching { track.playbackHeadPosition }.getOrNull() ?: return -1).toLong() and 0xFFFFFFFFL
            var best = -1; var bestFrame = -1L
            synchronized(beatFrames) {
                for (i in beatFrames.indices) {
                    val f = beatFrames[i]
                    if (f <= head && f > bestFrame) { bestFrame = f; best = beatIndex[i] }
                }
            }
            return best
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(BLOCK)
            // What is sounding: each a sound and how far into it the next block starts.
            val voices = ArrayList<Pair<FloatArray, Int>>()
            var written = 0L
            var next = BLOCK.toDouble()   // the first click a block in, so its attack is not cut
            var inBeat = 0; var inBar = 0
            try {
                track.play()
                while (running) {
                    val s = _state.value
                    buf.fill(0f)
                    // Start every pulse that falls in this block.
                    while (next < written + BLOCK) {
                        val at = (next.roundToInt() - written).toInt().coerceIn(0, BLOCK - 1)
                        val per = s.sub.per
                        if (inBeat >= per) { inBeat = 0; inBar++ }
                        if (inBar >= s.sig.beats) inBar = 0
                        val pulse = when { inBeat > 0 -> Pulse.Sub; inBar == 0 -> Pulse.Accent; else -> Pulse.Beat }
                        voices += Clicks.of(ctx.assets, s.click, pulse) to -at
                        if (inBeat == 0) synchronized(beatFrames) {
                            beatFrames[beatHead] = written + at; beatIndex[beatHead] = inBar
                            beatHead = (beatHead + 1) % beatFrames.size
                        }
                        inBeat++
                        next += RATE * 60.0 / (s.bpm * per)
                    }
                    val g = gain
                    val it = voices.listIterator()
                    while (it.hasNext()) {
                        val (snd, off) = it.next()
                        for (i in maxOf(0, -off) until BLOCK) {
                            val j = off + i
                            if (j >= snd.size) break
                            buf[i] += snd[j] * g
                        }
                        if (off + BLOCK >= snd.size) it.remove() else it.set(snd to off + BLOCK)
                    }
                    track.write(buf, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
                    written += BLOCK
                }
            } catch (e: Exception) {
                Log.e(TAG, "playback failed", e)
            } finally {
                runCatching { track.pause(); track.flush(); track.stop() }
                track.release()
            }
        }
    }
}
