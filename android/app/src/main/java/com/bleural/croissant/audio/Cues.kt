package com.bleural.croissant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bleural.croissant.Prefs
import java.io.File

/**
 * Every interaction the app has, silent ones included. Call sites fire their event
 * unconditionally; [Cues.cueFor] decides whether anything comes out. Adding a sound
 * is changing one null there.
 *
 * Two rules keep it automatic for anything added later: the effect sounds, not the
 * widget, and one interaction makes one sound. A tap that navigates sounds because
 * the push or pop fired; a tap that stays on the page is [Commit].
 */
enum class Sound {
    // Interaction
    /** The wheel moved the highlight. */
    WheelMoved,
    /** A wheel step that moved nothing: the end of a list, free-scrolling text, a page that swallows it. */
    WheelStill,
    /** A tap that did something and stayed on the page. */
    Commit,
    /** Into a screen. */
    ScreenIn,
    /** Into the Home menu. */
    MenuIn,
    /** A sheet comes up. */
    SheetIn,
    /** Back by tap, chevron, or the wheel up at the top. */
    ScreenOut,
    /** Out of the Home menu. */
    MenuOut,
    /** A sheet dismissed without acting. */
    SheetDismiss,
    /** Back by the edge drag: the page is already moving under the thumb. */
    EdgeBack,
    /** A switch turned on, or a password shown. */
    SwitchOn,
    /** A switch turned off, or a password hidden. */
    SwitchOff,
    /** A reminder ticked done. */
    ReminderDone,
    /** A reminder unticked. */
    ReminderUndone,
    /** A destructive thing confirmed: photo deleted, timer cancelled, network forgotten, signed out, power off, exit kiosk. */
    Destroy,
    /** A text field takes focus. */
    FieldFocus,
    /** A long press; the sheet it opens speaks for it. */
    LongPress,
    /** The control centre pulled down from the top bar. */
    PanelIn,
    /** The control centre put away: pushed up, or the scrim tapped. */
    PanelOut,

    // App events
    /** A hold is confirmed and listening starts. */
    ListeningStart,
    /** The button came up and listening ends. */
    ListeningEnd,
    /** An answer finished arriving. */
    AnswerArrived,
    /** A forwarded tool call ran. */
    ToolRan,
    /** An exchange failed. */
    ExchangeFailed,
    /** A link came up: Wi-Fi or the tailnet on its own, or a profile landing on the eSIM. */
    LinkUp,
    /** Wi-Fi or the tailnet dropped on its own, not from a tap. */
    LinkDown,
    /** A Wi-Fi join did not connect. */
    JoinFailed,
    /** The camera shutter. */
    Shutter,
    /** A shot kept. */
    PhotoSaved,
    /** A shot discarded. */
    PhotoDiscarded,
    /** A timer started; the page going back speaks for it. */
    TimerStarted,
    /** A ringing timer dismissed. */
    RingingDismiss,
    /** The screen goes to sleep. */
    ScreenSleep,
    /** The screen wakes. */
    ScreenWake,
}

/**
 * Every sound the interface makes, on one [SoundPool].
 *
 * Call sites fire a [Sound], [cueFor] names the [Cue], and the sound pack decides what
 * the cue sounds like. Every pack ships in the APK, `assets/sounds/<pack>/`, rendered by
 * `sounds/earshot.py`; which one plays is a setting ([Prefs.soundPack]), [DEFAULT_PACK]
 * until one is picked. The installer picks it by leaving [PACK_FILE] beside the font.
 *
 * A SoundPool is what Android's own AudioService uses for its touch sounds, and it
 * is the right shape for this: each clip is decoded to memory once at start, after
 * which a play is a mixer command with no allocation and no file work, and several
 * can sound at once. [STREAMS] of them, which is more than this interface can
 * plausibly need; past that the pool drops its own oldest voice.
 *
 * Only the pack in use is loaded: one Opus file per cue, each at most 600 ms, under a
 * megabyte of PCM in all. [use] switches it live: the new pack decodes on a pool of its
 * own and is swapped in once every clip is ready, so nothing goes silent in between, and
 * the old pool is released after [TAIL_MS], the longest cue's tail. [Preview] holds every
 * pack at once, for Settings > Device > Tones only while it is up.
 *
 * Nothing here rate limits. The wheel is the only source that can fire faster than
 * a person can act, and it is capped at the source, in [com.bleural.croissant.ui.Wheel],
 * so that the step stays one per selection rather than one per key event.
 *
 * Each file carries its level, matched across cues and packs when it was rendered; it
 * plays at [gain], the Sound level on the same curve the ringtone uses. The activity
 * writes [gain] at start and on every change, so call
 * sites never carry it. At 0 the pool is still handed the play, at silence: simpler
 * than branching, and it keeps the timing identical whether or not anything is
 * audible.
 */
object Cues {
    private const val TAG = "Cues"
    private const val STREAMS = 4
    /** Past the longest cue, 600 ms: an old pool is released after this, so a cue it was playing rings out. */
    private const val TAIL_MS = 800L
    private const val DIR = "sounds"
    const val DEFAULT_PACK = "shrine"
    /** The installer's pick, in the app's device-protected files (installer/croissant/stages.py). */
    const val PACK_FILE = "sound-pack"

    /** One pack decoded on a pool of its own. [ids] is filled before the pool is handed to anyone. */
    private class Bank(val pack: String, val pool: SoundPool, val ids: Map<Cue, Int>)

    /** The pack playing. */
    @Volatile private var bank: Bank? = null
    /** A pack picked and still decoding; it becomes [bank] once ready. Main thread only. */
    private var pending: Bank? = null
    private val main = Handler(Looper.getMainLooper())

    /** The pack in use, or about to be once it has decoded. */
    @Volatile var pack: String = DEFAULT_PACK
        private set

    /** The table: what each [Sound] sounds, or null for silence. */
    fun cueFor(s: Sound): Cue? = when (s) {
        Sound.WheelMoved -> Cue.Step
        Sound.WheelStill -> null
        Sound.Commit -> Cue.Tap
        Sound.ScreenIn -> Cue.Enter
        Sound.MenuIn -> Cue.ListIn
        Sound.SheetIn -> Cue.Open
        Sound.ScreenOut -> Cue.Back
        Sound.MenuOut -> Cue.ListOut
        Sound.SheetDismiss -> Cue.Back
        Sound.EdgeBack -> null
        Sound.SwitchOn -> Cue.On
        Sound.SwitchOff -> Cue.Off
        Sound.ReminderDone -> Cue.On
        Sound.ReminderUndone -> Cue.Off
        Sound.Destroy -> Cue.Destroy
        Sound.FieldFocus -> null
        Sound.LongPress -> null
        Sound.PanelIn -> Cue.PanelIn
        Sound.PanelOut -> Cue.PanelOut

        Sound.ListeningStart -> Cue.Listen
        Sound.ListeningEnd -> Cue.Heard
        Sound.AnswerArrived -> Cue.Arrived
        Sound.ToolRan -> null
        Sound.ExchangeFailed -> Cue.Fail
        Sound.LinkUp -> Cue.LinkUp
        Sound.LinkDown -> Cue.LinkDown
        Sound.JoinFailed -> Cue.Fail
        Sound.Shutter -> Cue.Shutter
        Sound.PhotoSaved -> Cue.Tap
        Sound.PhotoDiscarded -> Cue.Back
        Sound.TimerStarted -> null
        Sound.RingingDismiss -> null
        Sound.ScreenSleep -> null
        Sound.ScreenWake -> null
    }

    /** Linear gain, 0..1, from `DeviceLevels.gain(volume)`. Written on the main thread, read from any. */
    @Volatile
    var gain = 0f

    /** The packs this build carries. */
    fun packs(ctx: Context): List<String> = ctx.assets.list(DIR).orEmpty().sorted()

    /**
     * Decode the chosen pack. Called once from the activity's onCreate; a pool then lives
     * until [use] replaces it, and the process on this device lives as long as it is awake.
     * Loading is asynchronous, and a play before the first pack is ready is a no-op rather
     * than a fault.
     */
    fun load(ctx: Context) {
        if (bank != null || pending != null) return
        adoptInstallerPack(ctx)
        use(ctx, Prefs.soundPack(ctx) ?: DEFAULT_PACK)
    }

    /**
     * Make [name] the pack in use, [DEFAULT_PACK] if this build does not carry it. The one
     * playing goes on until the new one has decoded; a pick overtaken before then is dropped.
     * Main thread. Saving the pick is the caller's.
     */
    fun use(ctx: Context, name: String) {
        val p = name.takeIf { it in packs(ctx) } ?: DEFAULT_PACK
        if (p == (pending ?: bank)?.pack) return
        pack = p
        pending?.pool?.release()
        pending = null
        if (p == bank?.pack) return
        pending = decode(ctx, p, STREAMS) { b ->
            if (pending !== b) return@decode
            pending = null
            val old = bank
            bank = b
            old?.let { main.postDelayed({ it.pool.release() }, TAIL_MS) }
        }
    }

    /**
     * [pack] on a new pool of [streams] voices; [ready] runs on the main thread once every
     * clip has loaded or failed. Called on the main thread, whose looper the pool posts its
     * completions to, so none can arrive before the last load is issued.
     */
    private fun decode(ctx: Context, pack: String, streams: Int, ready: (Bank) -> Unit): Bank {
        val p = SoundPool.Builder()
            .setMaxStreams(streams)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .build()
        val ids = HashMap<Cue, Int>()
        val has = ctx.assets.list("$DIR/$pack").orEmpty().toSet()
        for (cue in Cue.entries) {
            // A pack may leave a cue out, and the cue is silent there: Zen has nothing for Ask.
            if ("${cue.file}.ogg" !in has) continue
            // SoundPool takes its own copy of the descriptor, so it closes here.
            runCatching { ctx.assets.openFd("$DIR/$pack/${cue.file}.ogg").use { ids[cue] = p.load(it, 1) } }
                .onFailure { Log.w(TAG, "$pack/${cue.file}: $it") }
        }
        val b = Bank(pack, p, ids)
        var left = ids.size
        p.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) Log.w(TAG, "$pack sample $id failed to load: $status")
            if (--left == 0) ready(b)
        }
        if (left == 0) main.post { ready(b) }
        return b
    }

    /** The installer's pick, left as a file beside the font: taken into the settings, then deleted. */
    private fun adoptInstallerPack(ctx: Context) {
        val f = File(ctx.createDeviceProtectedStorageContext().filesDir, PACK_FILE)
        if (!f.exists()) return
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it in packs(ctx) }?.let { Prefs.setSoundPack(ctx, it) }
        f.delete()
    }

    /** Fire [s]. Safe from any thread: a SoundPool play is a mixer command. */
    fun play(s: Sound) {
        play(cueFor(s) ?: return)
    }

    private fun play(cue: Cue) {
        val b = bank ?: return
        val id = b.ids[cue] ?: return
        b.pool.play(id, gain, gain, 1, 0, 1f)
    }

    /**
     * Every pack decoded at once, so Settings > Device > Tones can play one without making it
     * the one in use: every pack's clips, a few kilobytes each, held only while that page is up. [hear]
     * plays [PHRASE], a user scrolling, going in, a switch on and off and back out, and calls
     * [onEnd] once its last cue has rung out; [stop] cuts it off, and [onEnd] is not called.
     * At [gain], like every cue. Main thread; [release] when the page goes.
     */
    class Preview(ctx: Context, private val onEnd: () -> Unit) {
        private val banks = packs(ctx).associateWith { decode(ctx, it, PREVIEW_STREAMS) {} }
        // A handler of its own, so stopping clears this preview's phrase and nothing else.
        private val handler = Handler(Looper.getMainLooper())
        private val playing = ArrayList<Pair<SoundPool, Int>>()

        fun hear(pack: String) {
            stop()
            val b = banks[pack] ?: return
            for ((at, cue) in PHRASE) handler.postDelayed({
                val id = b.ids[cue] ?: return@postDelayed
                val stream = b.pool.play(id, gain, gain, 1, 0, 1f)
                if (stream != 0) playing += b.pool to stream
            }, at)
            handler.postDelayed({ playing.clear(); onEnd() }, PHRASE.last().first + TAIL_MS)
        }

        fun stop() {
            handler.removeCallbacksAndMessages(null)
            for ((pool, stream) in playing) pool.stop(stream)
            playing.clear()
        }

        fun release() {
            stop()
            banks.values.forEach { it.pool.release() }
        }
    }

    private const val PREVIEW_STREAMS = 4
    /** The preview, ms from its start: two steps, in, a switch on and off, back out. */
    private val PHRASE = listOf(0L to Cue.Step, 140L to Cue.Step, 360L to Cue.Enter, 760L to Cue.On, 1060L to Cue.Off, 1400L to Cue.Back)
}
