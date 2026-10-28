package com.bleural.croissant

import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.bleural.croissant.audio.AudioCapture
import com.bleural.croissant.audio.Recorder
import com.bleural.croissant.audio.RecPhase
import com.bleural.croissant.audio.RingBuffer
import com.bleural.croissant.data.RecordingMeta
import com.bleural.croissant.audio.Ringtone
import com.bleural.croissant.ask.AskError
import com.bleural.croissant.ask.Call
import com.bleural.croissant.ask.Calls
import com.bleural.croissant.ask.Chat
import com.bleural.croissant.ask.ChatApi
import com.bleural.croissant.ask.CloudPictures
import com.bleural.croissant.ask.Entry
import com.bleural.croissant.ask.Loop
import com.bleural.croissant.ask.MeteredSpeech
import com.bleural.croissant.ask.Provider
import com.bleural.croissant.ask.Shelf
import com.bleural.croissant.ask.Usage
import com.bleural.croissant.ask.pyTruthy
import com.bleural.croissant.data.ConversationsStore
import com.bleural.croissant.data.Generated
import com.bleural.croissant.data.HoldPrompt
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.Stores
import com.bleural.croissant.data.Timer
import com.bleural.croissant.data.TimerPlan
import com.bleural.croissant.data.ToolExecutor
import com.bleural.croissant.data.Turn
import com.bleural.croissant.data.TurnState
import com.bleural.croissant.net.GenerationFetcher
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.Protocol
import com.bleural.croissant.net.SessionClient
import com.bleural.croissant.net.WebSearch
import com.bleural.croissant.offline.LocalState
import com.bleural.croissant.offline.OfflineVoice
import com.bleural.croissant.offline.SpeechGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL_ID = "voice"
        private const val NOTIF_ID = 1

        /**
         * How long the button must be held before it counts as "talk" rather than a short
         * press. Purely a gesture-discrimination number: because the ring buffer reaches
         * backwards in time, no audio is lost regardless of what this is set to. Keep it
         * short enough that the UI feels responsive.
         */
        const val HOLD_THRESHOLD_MS = 500L

        /** Audio kept from *before* the press, so an early speaker is not clipped. */
        const val PREROLL_MS = 400

        /** Threshold + pre-roll + margin. At 32 kB/s this whole buffer is ~45 kB. */
        private const val RING_MS = 1400

        /** From [TimerReceiver]: an alarm fired or the device booted; look at the timers. */
        const val ACTION_TIMERS = "com.bleural.croissant.TIMERS"

        /**
         * The Translator's local mode on or off (extra `on`), for driving it over adb as
         * root before a screen has its switch: `am start-foreground-service -a
         * com.bleural.croissant.LOCAL --ez on true com.bleural.croissant/.VoiceService`.
         */
        const val ACTION_LOCAL = "com.bleural.croissant.LOCAL"

        /** A hold with no words in it, as the server says it. */
        private const val NO_SPEECH = "No speech detected"
    }

    inner class LocalBinder : Binder() {
        val service: VoiceService get() = this@VoiceService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    /**
     * Mic level 0..1 for the Ask screen's meter, from every 20 ms chunk (roughly -60..0
     * dBFS) **while an utterance is being sent**, and a flat 0 the rest of the time.
     *
     * The microphone is open for the life of the service, so this used to be written 50
     * times a second for ever, whether or not anything was listening. The Ask panel's
     * meter reads it in a draw, so the whole panel was redrawn 50 times a second with a
     * finished answer sitting on it and nothing moving: 10.8 ms of RenderThread a frame
     * for a 6 px bar reading room noise (PERFORMANCE.md). Writing the same 0 repeatedly
     * costs nothing, because a StateFlow does not emit a value equal to the one it holds.
     */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private var smoothed = 0f

    /** Invoked when the button was tapped rather than held (wake/sleep in M5). */
    var onShortPress: (() -> Unit)? = null

    /** The camera screen's shot, shown to the chat model with every hold while it is on screen; null clears it. */
    @Volatile private var photo: ByteArray? = null
    /** The attached photo's file, when it is already in the library; null for an unsaved shot. */
    @Volatile private var photoFile: java.io.File? = null
    /** The Transform page is open: the next hold makes a picture instead of asking. */
    @Volatile private var transformMode = false
    /** The Translator is open, in this pair: the next hold is translated instead of asked. */
    @Volatile private var translatePair: LanguagePair? = null
    /** A page that takes a hold's words is open (an Atelier app, an agent's conversation): the next hold comes back as words alone, for it. */
    @Volatile private var dictateMode = false
    /** Where a dictated hold's words go once the server has settled them: Core's, to the app or the agent's session. */
    var onDictated: ((String) -> Unit)? = null
    /** Whether that page has somewhere for a hold's words to go now (an app always; an agent's session once it is open and not running or asking). */
    var canDictate: () -> Boolean = { true }
    /** A hold on that page with nowhere to go: refused, no socket opened. */
    var onDictateRefused: (() -> Unit)? = null
    /** A hold that asks, a photo hold included: its whole prompt and tools at its time (Core, data/AskPrompt.kt). */
    var holdPrompt: (photo: Boolean, now: OffsetDateTime) -> HoldPrompt? = { _, _ -> null }
    /** The chat model a hold's words go to, made at its press (Core): ready, or why there is none. */
    var holdChat: () -> HoldChat = { HoldChat.NoModel }
    /** What a hold's or a tape's words are heard by, settled at its start (Core): the Mac's, a cloud model here, or why there is none. */
    var holdSpeech: () -> HoldSpeech = { HoldSpeech.Mac(Prefs.modelPicks(this)[ModelSlot.Stt.key]) }
    /** What a picture for a slot is made by, settled as it is asked for (Core): the Mac's, a cloud model here, or why there is none. */
    var holdPictures: (ModelSlot) -> HoldPictures = { HoldPictures.Mac }
    /** Whether the picture model a Transform goes to wants the words made a description first (a local one, Core). */
    var wantsDescription: () -> Boolean = { true }
    /**
     * An Atelier app's tool, run with its page up unless [show] is false (a hold taken over, Core):
     * its reply as JSON, or null when [name] is no app's.
     */
    var appTool: (suspend (name: String, args: JSONObject, show: Boolean) -> String?)? = null
    /** Model calls outside a hold (Core's: the model test, an app's `ask()`): the device does not sleep under one. */
    var working: () -> Boolean = { false }

    /**
     * The conversation a hold on Ask continues; null when the next hold starts a new one.
     * Set by the first hold on Ask and by History, cleared by
     * leaving Ask and by "+".
     */
    private val _askConv = MutableStateFlow<String?>(null)
    val askConv: StateFlow<String?> = _askConv

    /**
     * Leaving Ask closes its conversation: coming back is a new question on a clean page
     * (2026-09-18), and the next hold, from anywhere, starts a new conversation.
     *
     * ~~Anything still in flight is abandoned~~ (until 2026-10-06): an answer on its way
     * now finishes where it is and lands in its conversation whether or not Ask is up.
     * Abandoning it is how a picture was once made for nobody.
     */
    fun clearExchange() = openConversation(null)

    /** Ask shows [id], from History, or a blank page for a new conversation (null, "+"). */
    fun openConversation(id: String?) {
        if (_state.value.phase == Phase.Listening) return
        _askConv.value = id
        val s = _state.value
        if ((s.phase == Phase.Idle || s.phase == Phase.Error) && s.translate == null && !s.transform && !s.dictate) _state.value = VoiceState()
    }

    /** History's delete: the conversations go; an answer still coming for one of them lands nowhere. */
    fun deleteConversations(ids: Set<String>) {
        conversations.delete(ids)
        if (_askConv.value in ids) _askConv.value = null
    }

    fun attachPhoto(jpeg: ByteArray?, file: java.io.File? = null) {
        photo = jpeg
        photoFile = file
        Log.i(TAG, if (jpeg == null) "photo detached" else "photo attached, ${jpeg.size / 1024} kB")
        // A fresh shot (or none) must not sit over the previous question's text.
        if (_state.value.phase == Phase.Idle || _state.value.phase == Phase.Error) _state.value = VoiceState()
    }

    /**
     * The photo page's Transform button (ui/beam/Photos.kt): while that page is
     * open a hold is a transform, a picture made from the attached photo, never an answer in
     * words.
     *
     * Arriving there clears a finished exchange the way a fresh shot does, so the page
     * starts blank and its own `generation` cannot be confused with an older one.
     */
    fun setTransform(on: Boolean) {
        transformMode = on
        Log.i(TAG, if (on) "transform mode on" else "transform mode off")
        if (on && (_state.value.phase == Phase.Idle || _state.value.phase == Phase.Error)) _state.value = VoiceState()
    }

    /**
     * The Translator is open in [pair], or closed (null): while it is open, a hold is
     * translated rather than asked, and what comes back is kept with its pair.
     *
     * Opening it clears a finished exchange, as the Transform page does, so the screen
     * starts on its own last translation rather than an answer from Ask. Closing it
     * clears one of its own that has finished; one still in flight is let run, and
     * clears itself once it is kept (see AnswerDone).
     */
    fun setTranslate(pair: LanguagePair?) {
        translatePair = pair
        if (pair != null) local.pair(pair)
        val s = _state.value
        val settled = s.phase == Phase.Idle || s.phase == Phase.Error
        if (settled && (pair != null || s.translate != null)) _state.value = VoiceState()
    }

    /**
     * A page that takes a hold's words is open, or gone: while it is, a hold's words are all
     * there is, handed to [onDictated] as the server settles them. No photo, no conversation,
     * no turn, no model: the page keeps what they are for.
     */
    fun setDictate(on: Boolean) {
        dictateMode = on
        val s = _state.value
        if ((s.phase == Phase.Idle || s.phase == Phase.Error) && (on || s.dictate)) _state.value = VoiceState()
    }

    private val ring = RingBuffer(RING_MS * AudioCapture.BYTES_PER_MS)
    private val timers by lazy { Stores.timers(this) }

    /** The tape recorder: fed every capture chunk, writes while recording. */
    val recorder by lazy { Recorder(Stores.recordings(this)) }
    /** Sends a recording for transcription on a tap of Transcribe, never on its own: to the Mac, or to a cloud model from here. */
    val transcriber by lazy {
        Transcriber(Stores.recordings(this), scope, speech = { holdSpeech() }) { Prefs.serverUrl(this) }
    }
    private val translations by lazy { Stores.translations(this) }

    /**
     * The Translator's local mode: while it is on, a hold
     * on the Translator is heard and translated here, on the device, and no socket is
     * opened. Holds anywhere else still go to the server.
     */
    val local by lazy { OfflineVoice(this, scope) }
    /**
     * A hold's audio kept here, pre-roll first, while it is held (the offline Translator's, or
     * one a cloud speech model hears at release); null otherwise. Guarded by [sendLock].
     */
    private var localPcm: java.io.ByteArrayOutputStream? = null
    private var localJob: Job? = null

    /** Local mode on (both engines loaded, [pair]'s directions with them) or off (all unloaded). */
    fun setLocal(on: Boolean, pair: LanguagePair) {
        Log.i(TAG, if (on) "local mode on ($pair)" else "local mode off")
        if (on) local.on(pair) else local.off()
    }
    private val tools by lazy { ToolExecutor(Stores.reminders(this), Stores.events(this), timers, Stores.recordings(this)) }
    /** The pictures the server is making, and the schedule that goes to collect them. */
    private val generated by lazy { Stores.generated(this) }
    val generations by lazy { GenerationFetcher(generated, scope, picks = { Prefs.modelPicks(this) }) { Prefs.server(this) } }
    /** The pictures a cloud model makes from here, straight onto the same shelf, each counted (ask/Pictures.kt). */
    private val shelf by lazy { Shelf(generated, scope, Stores.ledger(this), ::toJpeg, log = { Log.i(TAG, it) }) }
    private val conversations by lazy { Stores.conversations(this) }

    /** The timers that have gone off and not been dismissed; the activity draws them over everything. */
    private val _ringing = MutableStateFlow<List<Timer>>(emptyList())
    val ringing: StateFlow<List<Timer>> = _ringing
    private var tone: Ringtone? = null
    private var ringLock: PowerManager.WakeLock? = null
    private var capture: AudioCapture? = null

    /** Anything else listening to the microphone: the tuner, while its page is up. Called on the capture thread. */
    @Volatile var tap: ((ByteArray, Int) -> Unit)? = null
    private var session: SessionClient? = null
    private var holdJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var idle: IdleWatch? = null

    /** Guards the ordering of (snapshot, live chunks) so the stream cannot interleave. */
    private val sendLock = Any()
    private var streaming = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // Keeps the CPU alive so capture survives the screen going off. This is the
        // battery cost of the always-hot design; revisit it before anything else if
        // standby drain turns out to hurt.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "croissant:voice")
            .also { it.acquire() }

        capture = AudioCapture { buf, n ->
            // Whether this chunk is going anywhere, read under the lock that owns the
            // answer, and passed on so the meter can go quiet when nothing is listening.
            val live = synchronized(sendLock) {
                ring.write(buf, n)
                if (streaming) {
                    session?.sendAudio(buf, n)
                    localPcm?.write(buf, 0, n)
                }
                streaming
            }
            recorder.feed(buf, n)
            tap?.invoke(buf, n)
            meter(buf, n, live)
        }.also { it.start() }

        Log.i(TAG, "service up; mic hot")

        // Rest and Sleep: the mic goes off with the screen, the wake lock once still for the Sleep time.
        idle = IdleWatch(this, busy = ::busy, onStage = ::idleStage).apply {
            setSleepAfter(Prefs.sleep(this@VoiceService))
            setScreen((getSystemService(POWER_SERVICE) as PowerManager).isInteractive)
        }

        // The battery log: a sample at every screen on and off and every plug and unplug
        // (none of these four reach a receiver declared in the manifest), one now, and
        // the interval alarm.
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        BatteryTracker.sample(this, "start")
        BatteryTracker.sync(this)

        // Every change to the timers (voice, the screens, a purge) replaces the one
        // exact alarm; the first collection is the process start, boot included.
        scope.launch { timers.items.collect { syncTimers() } }

        // Pictures still expected, and deletions the Mac has not heard about, from the
        // process start rather than from the first time a screen asks for one: both are
        // waiting for a link that dropped, and a reboot is exactly when that happened.
        generations.watch()
        // A picture this device was making when the process went will not come: it says so, and has Try again.
        scope.launch {
            for (g in generated.list()) if (g.c1 && g.pending && !shelf.has(g.id)) generated.fail(g.id, "Cut short")
        }
        // A turn still asking was cut short by the process going: nothing will answer it now.
        scope.launch {
            for (c in conversations.list()) for (t in c.turns) if (t.state == TurnState.Asking) {
                if (t.question.isBlank()) conversations.drop(t.id)
                else conversations.update(t.id) { it.copy(state = TurnState.Failed, error = "Cut short") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TIMERS) syncTimers()
        if (intent?.action == ACTION_LOCAL) setLocal(intent.getBooleanExtra("on", false), Prefs.translatorPair(this))
        return START_STICKY
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> { BatteryTracker.sample(c, "plug"); return }
                Intent.ACTION_POWER_DISCONNECTED -> { BatteryTracker.sample(c, "unplug"); return }
            }
            val on = i.action == Intent.ACTION_SCREEN_ON
            idle?.setScreen(on)
            BatteryTracker.sample(c, if (on) "on" else "off")
            // Every sleep and wake, whatever caused it: the button, Power, the timeout.
            Cues.play(if (on) Sound.ScreenWake else Sound.ScreenSleep)
        }
    }

    /** Settings > Device > Battery > Sleep, in ms or [Idle.NEVER]. */
    fun setSleep(ms: Int) { idle?.setSleepAfter(ms) }

    /**
     * Anything that must keep the mic: a hold or an answer (one taken over too), the model test or
     * an app's `ask()`, a recording, the tuner, a timer ringing.
     */
    private fun busy(): Boolean {
        val p = _state.value.phase
        return (p != Phase.Idle && p != Phase.Error) || streaming || answering.get() > 0 || working() ||
            recorder.state.value.phase != RecPhase.Idle || tap != null || _ringing.value.isNotEmpty() || updating
    }

    /** An update installing (Core): nothing rests or sleeps until it is done. */
    @Volatile private var updating = false

    fun holdAwake(on: Boolean) {
        updating = on
        if (on) idle?.used()
    }

    /**
     * Resting: the mic stops and what the ring held goes with it, so the next hold's pre-roll
     * is never minutes old. Asleep: the mic and the wake lock both go. Awake: both come back.
     */
    private fun idleStage(s: IdleWatch.Stage) {
        when (s) {
            IdleWatch.Stage.Awake -> {
                wakeLock?.let { if (!it.isHeld) it.acquire() }
                capture?.start()
            }
            IdleWatch.Stage.Resting, IdleWatch.Stage.Asleep -> {
                capture?.stop()
                synchronized(sendLock) { ring.clear() }
                if (s == IdleWatch.Stage.Asleep) wakeLock?.let { if (it.isHeld) it.release() }
            }
        }
        BatteryTracker.sample(this, s.name.lowercase())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        idle?.close()
        holdJob?.cancel()
        session?.abort()
        local.off()
        capture?.stop()
        // The process going: what was recorded is kept and says it was cut short.
        recorder.stop(RecordingMeta.INTERRUPTED)
        tone?.stop()
        ringLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- timers ------------------------------------------------------------

    /** Ring what is due, schedule the next. Idempotent; called from several threads. */
    @Synchronized
    private fun syncTimers() {
        val plan = TimerPlan.at(timers.list(), System.currentTimeMillis())
        TimerAlarms.set(this, plan.nextAt)
        val new = plan.due.filter { d -> _ringing.value.none { it.id == d.id } }
        if (new.isEmpty()) return
        Log.i(TAG, "ringing ${new.map { it.id }}")
        _ringing.update { it + new }
        if (tone == null) {
            // Turns the screen on and holds it while the sound plays (the flags are
            // deprecated, but they are still the one way an app lights the panel).
            @Suppress("DEPRECATION")
            ringLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "croissant:timer")
                .also { it.acquire(10 * 60 * 1000L) }
            tone = Ringtone(this, onFinished = ::soundOver).also { it.start() }
        }
    }

    /**
     * The ringtone has played its four times without a dismissal: the sound stops but
     * the ringing page stays until a tap or a press, and the screen may now time out.
     */
    @Synchronized
    private fun soundOver() {
        tone = null
        ringLock?.let { if (it.isHeld) it.release() }; ringLock = null
        if (_ringing.value.isNotEmpty()) Log.i(TAG, "sound over, still ringing ${_ringing.value.map { it.id }}")
    }

    /** A tap or a side-button press: silence, and the fired timers leave the file. */
    @Synchronized
    fun dismissRing() {
        val fired = _ringing.value
        if (fired.isEmpty()) return
        tone?.stop(); tone = null
        ringLock?.let { if (it.isHeld) it.release() }; ringLock = null
        _ringing.value = emptyList()
        fired.forEach { runCatching { timers.cancel(it.id) } }
        Log.i(TAG, "dismissed ${fired.map { it.id }}")
        Cues.play(Sound.RingingDismiss)
    }

    /**
     * RMS of one PCM16 chunk, mapped to 0..1 with a fast attack and a slow decay, or a
     * single 0 when [live] is false and the meter has something to come down from.
     */
    private fun meter(buf: ByteArray, n: Int, live: Boolean) {
        if (!live) {
            if (smoothed != 0f) { smoothed = 0f; _level.value = 0f }
            return
        }
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xff or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
        val db = 20 * log10(rms / 32768.0 + 1e-9)
        val lin = ((db + 60) / 55).toFloat().coerceIn(0f, 1f)
        smoothed = if (lin > smoothed) lin else smoothed * 0.8f + lin * 0.2f
        _level.value = smoothed
    }

    // ---- push to talk ------------------------------------------------------

    fun pttDown() {
        // A press from a quiet mic starts it now rather than when the screen-on broadcast lands.
        idle?.used()
        val s = _state.value
        if (s.phase != Phase.Idle && s.phase != Phase.Error && !canTakeOver(s)) return
        holdJob?.cancel()
        holdJob = null
        holdJob = scope.launch {
            delay(HOLD_THRESHOLD_MS)
            beginUtterance()
        }
    }

    fun pttUp() {
        val wasHold = holdJob?.isActive != true
        holdJob?.cancel()
        holdJob = null
        if (streaming) {
            endUtterance()
        } else if (!wasHold) {
            // Released before the threshold: not speech, so nothing was ever sent.
            onShortPress?.invoke()
        }
    }

    /**
     * One hold, as its press settled it: what it is, its turn and conversation, what hears it
     * ([speech], a cloud model here; null, the Mac's) and what its words will be answered with
     * (the model, the prompt and tools, the prior turns, the photo). Its release, its `final`
     * (its words, wherever heard) and its first text on [SystemClock.elapsedRealtime] (0 until
     * each happens) make the "hold timed" line.
     *
     * Once its words are in, the C1 answers it ([answering]), and a new hold may take the page
     * over ([detach]): from there it carries on with its own copy of the exchange and writes
     * its turn into its conversation when done, the page none the wiser.
     */
    private inner class Hold(
        val turn: String?, val conv: String?, val chat: HoldChat.Ready?, val prompt: HoldPrompt?,
        val prior: List<Pair<String, String>>, val jpeg: ByteArray?, val photoFile: java.io.File?,
        val transform: Boolean, val translate: LanguagePair?, val dictate: Boolean, val speech: HoldSpeech.Cloud?,
        val pictures: HoldPictures?,
    ) {
        @Volatile var releasedAt = 0L
        @Volatile var finalAt = 0L
        @Volatile var firstTextAt = 0L
        @Volatile var answering = false
        /** Its tool calls so far, for each line's id. */
        val calls = AtomicInteger()

        private var own: VoiceState? = null

        /** Whether it is still the exchange on the page. */
        val shown: Boolean @Synchronized get() = own == null

        /** Its exchange as it stands, on the page or not. */
        val state: VoiceState @Synchronized get() = own ?: _state.value

        /** [f] on its exchange: the page's while it is shown, else its own copy. */
        @Synchronized
        fun update(f: (VoiceState) -> VoiceState) {
            val o = own
            if (o != null) own = f(o) else _state.update(f)
        }

        /** A new hold takes the page: from here this one goes on with its own copy. */
        @Synchronized
        fun detach() {
            own = _state.value
        }
    }

    /** The hold on the page, from its press until it ends or a new one takes over. */
    @Volatile private var hold: Hold? = null

    /** Holds the C1 is answering, on the page or taken over: the device does not rest under one. */
    private val answering = AtomicInteger()

    /**
     * An answer is still coming and a hold could start something new over it: on its own
     * conversation's page the hold waits for it (2026-10-06), anywhere else the hold is a new
     * conversation and the answer goes on behind it ([detach]). Before its words are in, the
     * socket is still the hold's, and a press waits.
     */
    private fun canTakeOver(s: VoiceState): Boolean {
        val h = hold ?: return false
        return h.answering && h.turn != null && s.turn == h.turn && h.conv != _askConv.value
    }

    /** The hold in flight goes on off the page, and the page is the new hold's. */
    private fun detach() {
        val h = hold ?: return
        Log.i(TAG, "${h.turn} goes on behind a new hold")
        h.detach()
        hold = null
        _state.value = VoiceState()
    }

    private fun beginUtterance() {
        // The page may have been taken since the press: then the press waits, as it would have then.
        val s = _state.value
        if (s.phase != Phase.Idle && s.phase != Phase.Error && !canTakeOver(s)) return
        if (canTakeOver(s)) detach()
        val server = Prefs.server(this)
        // The Translator sends no photo: a hold there is only ever words to carry across.
        val translate = translatePair
        if (translate != null && local.state.value != LocalState.Off) {
            beginLocal(translate)
            return
        }
        val dictate = dictateMode && translate == null
        // A page that takes the words needs somewhere for them now, or the hold is refused before anything is sent.
        if (dictate && !canDictate()) {
            Log.i(TAG, "dictate refused: nothing on the page to take it")
            onDictateRefused?.invoke()
            return
        }
        // What hears the words: a cloud model hears them here at the release, the Mac's needs the server.
        val speech = holdSpeech()
        when (speech) {
            is HoldSpeech.NoKey -> return refuse("no key for ${speech.provider}", translate, dictate, noKey = speech.provider)
            is HoldSpeech.Mac -> if (!server.set) return refuse(VoiceState.NO_SERVER, translate, dictate)
            is HoldSpeech.Cloud -> Unit
        }
        val cloud = speech as? HoldSpeech.Cloud
        // Dictation sends no photo either: its holds are words for the page.
        val jpeg = if (translate != null || dictate) null else photo
        // A transform without a photo is not a transform: it would make nothing, so it is an
        // ordinary question.
        val transform = transformMode && jpeg != null
        // A transform's picture model, settled now too: one with no key stops here.
        val pictures = if (transform) holdPictures(ModelSlot.ImageToImage) else null
        if (pictures is HoldPictures.NoKey) return refuse("no key for ${pictures.provider}", translate, dictate, noKey = pictures.provider)
        // The model the words will go to, settled now, so a hold with none stops here and the
        // connection to its provider opens while the words are said. Dictation asks no model,
        // nor a Transform whose picture model takes the words as they are (any but the Mac's local one).
        val chat = if (dictate || (transform && !(pictures == HoldPictures.Mac && wantsDescription()))) null else holdChat()
        when (chat) {
            is HoldChat.NoKey -> return refuse("no key for ${chat.provider}", translate, dictate, noKey = chat.provider)
            HoldChat.NoModel -> return refuse(VoiceState.NO_MODEL, translate, dictate)
            else -> Unit
        }
        val ready = chat as HoldChat.Ready?
        ready?.let { Chat.warm(it.entry.provider) }
        // A hold on Ask continues Ask's conversation; a photo hold is a conversation of its
        // own; a transform has a turn but no conversation; the Translator and dictation keep
        // nothing of the kind.
        val turn = if (translate != null || dictate) null else ConversationsStore.newTurnId()
        val conv = when {
            turn == null || transform -> null
            jpeg != null -> ConversationsStore.newConvId()
            else -> _askConv.value ?: ConversationsStore.newConvId().also { _askConv.value = it }
        }
        val prior = conv?.let { conversations.prior(it) }.orEmpty()
        val askedAt = System.currentTimeMillis()
        if (turn != null && conv != null) conversations.begin(conv, Turn(turn, askedAt, "", photo = photoFile?.name))
        // The prompt and the tools at the press: on `final` only the words are added. A
        // picture, a translation or dictation has fixed prompts of its own, or none.
        val prompt = if (transform || dictate || translate != null) null else holdPrompt(jpeg != null, OffsetDateTime.now())
        val h = Hold(turn, conv, ready, prompt, prior, jpeg, photoFile, transform, translate, dictate, cloud, pictures)
        hold = h
        _state.value = VoiceState(phase = Phase.Listening, withPhoto = jpeg != null,
            askedAt = askedAt, transform = transform, translate = translate, dictate = dictate,
            conv = conv, turn = turn)
        val what = (if (jpeg != null) " with a ${jpeg.size / 1024} kB photo" else "") +
            (if (transform) " (transform)" else "") +
            (if (translate != null) " (translate $translate)" else "") +
            (if (dictate) " (dictate)" else "") +
            (ready?.let { " on ${it.entry.id}" } ?: "") +
            (if (prompt != null) " (prompt ${prompt.tag})" else "")

        // A cloud model hears nothing until the release (no live words: each pass is billed),
        // so no socket: the audio is kept here, pre-roll first, as the offline Translator keeps it.
        if (cloud != null) {
            synchronized(sendLock) {
                localPcm = java.io.ByteArrayOutputStream().apply { write(ring.snapshot()) }
                streaming = true
            }
            Log.i(TAG, "utterance begun, heard by ${cloud.entry.id}$what")
            return
        }
        val client = SessionClient(server.url)
        session = client
        client.connect(object : SessionClient.Listener {
            override fun onOpen() = Unit

            /**
             * Whether this socket is still the one being listened to. One let go at `final`, or
             * aborted by an exchange abandoned ([clearExchange]), comes back through onClosed or
             * onFailure like any other; without this it would write an error onto a page that
             * has moved on.
             */
            private val mine: Boolean get() = session === client

            override fun onMessage(msg: Protocol.Incoming) {
                if (!mine) return
                when (msg) {
                    is Protocol.Incoming.Partial ->
                        h.update { it.copy(transcript = msg.text) }
                    is Protocol.Incoming.Final -> heard(h, msg.text)
                    // Only dictation waits for it: any other hold let the socket go at `final`.
                    Protocol.Incoming.AnswerDone -> dictated(h)
                    is Protocol.Incoming.Failure -> {
                        failTurn(h, msg.message)
                        fail(msg.message)
                    }
                    is Protocol.Incoming.Unknown ->
                        Log.w(TAG, "unhandled frame: ${msg.raw.take(200)}")
                    // A picture's and a recording's frames never arrive on a hold's socket.
                    is Protocol.Incoming.Generation, is Protocol.Incoming.Progress, is Protocol.Incoming.Segments,
                    is Protocol.Incoming.Done -> Log.w(TAG, "unexpected frame on a hold's socket: $msg")
                }
            }

            override fun onClosed() {
                if (!mine) return
                if (_state.value.phase != Phase.Idle && _state.value.phase != Phase.Error) lost(h)
            }

            override fun onFailure(kind: SessionClient.FailureKind, message: String) {
                if (!mine) return
                lost(h)
            }
        })

        // OkHttp queues frames until the socket opens, so ordering here is what the
        // server sees. Snapshot and arm under the same lock the capture thread uses,
        // otherwise a chunk could slip in before or be lost between the two.
        synchronized(sendLock) {
            client.sendText(Protocol.start(
                AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS,
                from = translate?.from?.code,
                stt = (speech as HoldSpeech.Mac).pick,
                partials = Prefs.liveWords(this),
                conv = conv,
            ))
            val preroll = ring.snapshot()
            client.sendAudio(preroll, preroll.size)
            streaming = true
        }
        Log.i(TAG, "utterance begun -> ${server.url}$what")
    }

    /**
     * A hold stopped at its press with [why] (no server, no model, no key): no socket, nothing
     * kept. On Ask it is said over the conversation open there.
     */
    private fun refuse(why: String, translate: LanguagePair?, dictate: Boolean, noKey: String? = null) {
        Log.i(TAG, "hold refused: $why")
        _state.value = VoiceState(phase = Phase.Error, error = why, askedAt = System.currentTimeMillis(),
            translate = translate, dictate = dictate, noKey = noKey,
            conv = if (translate == null && !dictate) _askConv.value else null)
    }

    /**
     * The words are in. Dictation hands them on at `answer_done` (or at once, heard here);
     * every other hold is the C1's from here: the socket goes, and its answer runs in the
     * service's scope, to its end whatever the page does.
     */
    private fun heard(h: Hold, text: String) {
        if (h.finalAt == 0L) h.finalAt = SystemClock.elapsedRealtime()
        h.update { it.copy(transcript = text, phase = Phase.Answering) }
        h.turn?.let { t -> conversations.update(t) { it.copy(question = text) } }
        if (h.dictate) return
        session?.close()
        session = null
        h.answering = true
        answering.incrementAndGet()
        guarded(h) {
            try {
                when {
                    h.transform -> transform(h, text)
                    h.translate != null -> translate(h, text)
                    else -> ask(h, text)
                }
            } finally {
                answering.decrementAndGet()
            }
        }
    }

    /**
     * [block] for [h] in the service's scope. What it throws that nothing caught (a store's
     * write, a picture's start) fails the hold, not the app, which is HOME.
     */
    private fun guarded(h: Hold, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "the hold failed", e)
            failed(h, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Dictation's words, handed to the open app or agent's conversation ([onDictated]) as the server closes the exchange, or once heard here. */
    private fun dictated(h: Hold) {
        logTimed(h)
        val words = _state.value.transcript
        // Handed over before this exchange lets go of them, so the page goes from heard to
        // sending in one frame, with no empty card between.
        if (words.isNotBlank()) onDictated?.invoke(words)
        session?.close()
        session = null
        hold = null
        // The words are the page's now (an agent's session's sending), not this exchange's.
        _state.value = VoiceState()
    }

    /**
     * Ask's answer, a photo hold's too: Ask's loop on the hold's model, with the prompt, the
     * tools and the conversation settled at the press. The first round streams to the page;
     * later rounds are held back until they end without a call (ask/Loop.kt).
     */
    private suspend fun ask(h: Hold, question: String) {
        val c = h.chat ?: return failed(h, VoiceState.NO_MODEL)
        val p = h.prompt ?: return failed(h, VoiceState.NO_MODEL)
        val rounds = mutableListOf<Usage>()
        val t0 = SystemClock.elapsedRealtime()
        try {
            Loop(c.chat) { Log.i(TAG, it) }.answer(question, p.system, h.prior, p.tools, execute = { run(h, c, it) },
                image = h.jpeg, stats = rounds) { text(h, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${c.entry.id} failed: ${e.message}")
            return failed(h, said(e, c.entry))
        } finally {
            if (rounds.isNotEmpty()) {
                val r = Loop.rates(rounds)
                Log.i(TAG, "answered by ${c.entry.id} in %.2f s, %d round(s), prompt tokens %s: %d tok at %.0f tok/s, out %d tok at %.1f tok/s (prompt %s)"
                    .format(Locale.ROOT, (SystemClock.elapsedRealtime() - t0) / 1000.0, rounds.size, rounds.map { it.promptTokens },
                        r.promptTokens, r.prefill, r.outTokens, r.decode, p.tag))
            }
        }
        finished(h)
    }

    /** A piece of the answer, as it comes: on the page, or in the hold's own copy. */
    private fun text(h: Hold, piece: String) {
        if (h.firstTextAt == 0L) h.firstTextAt = SystemClock.elapsedRealtime()
        h.update { it.withText(piece) }
    }

    /**
     * One of the model's calls: the web search on the server while it lists it, a picture by
     * the `generate` frame, a tape's question here, the rest the device's own tools.
     */
    private suspend fun run(h: Hold, c: HoldChat.Ready, call: Call): Any? = when (call.name) {
        "web_search" -> search(call.args)
        "image_generate" -> picture(h, call.args)
        "tape_ask" -> c.calls.tapeAsk(call.args) { device(h, "tape_read", it) }
        else -> device(h, call.name, call.args)
    }

    /**
     * A device tool (the agenda, the timers, an Atelier app's, `tape_read` for `tape_ask`),
     * its line on Ask while it runs. The stores are synchronised, so calls cannot interleave
     * badly even if the model issues several. What it throws goes back to the model as its
     * error (ask/Loop.kt).
     */
    private suspend fun device(h: Hold, name: String, args: JSONObject): Any {
        val id = "c" + h.calls.incrementAndGet()
        h.update { it.withTool(ToolCall(id, ToolExecutor.label(name, args), ToolExecutor.value(name, args))) }
        var failed = true
        try {
            val result: Any = appTool?.invoke(name, args, h.shown)?.let { JSONTokener(it).nextValue() }
                ?: withContext(Dispatchers.IO) { tools.run(name, args) }
            Log.i(TAG, "tool $name -> ${result.toString().take(200)}")
            failed = false
            return result
        } catch (e: Exception) {
            if (e !is CancellationException) Log.w(TAG, "tool $name failed: ${e.message}")
            throw e
        } finally {
            h.update { s -> s.updateTool(id) { it.copy(running = false, failed = failed) } }
            Cues.play(Sound.ToolRan)
        }
    }

    /** `web_search`: the server's digest as text (net/Search.kt), run unseen as it always was. */
    private suspend fun search(args: JSONObject): Any {
        val query = args.opt("query").takeIf(::pyTruthy)?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return JSONObject().put("error", "query is required")
        return WebSearch.digest(Prefs.server(this), query)
    }

    /**
     * `image_generate`: the picture asked of the picture model under an id made here (a cloud
     * one's on the [shelf], the Mac's by [GenerationFetcher.start]), and the model answered at
     * once, since a picture takes tens of seconds and the hold is over in five. Ask shows the
     * line where it happened; the line never spins, there is nothing to wait for.
     */
    private fun picture(h: Hold, args: JSONObject): Any {
        val prompt = args.opt("prompt").takeIf(::pyTruthy)?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return JSONObject().put("error", "prompt is required")
        val id = when (val m = holdPictures(ModelSlot.TextToImage)) {
            is HoldPictures.Cloud -> shelf.start(m.pictures, m.entry.id, prompt)
            is HoldPictures.NoKey -> return JSONObject().put("error", "no key for ${m.provider}")
            HoldPictures.Mac -> generations.start(prompt)
        }
        Log.i(TAG, "generating $id: ${prompt.take(120)}")
        h.update { it.withTool(ToolCall(id, "Generating image", prompt, running = false, generation = id)).copy(generation = id) }
        return JSONObject().put("id", id).put("status", "started")
    }

    /**
     * The photo page's Transform button: a picture from the photo, always, no answer. A
     * picture model that wants a description of the finished picture (a local one) gets the
     * words made one by the chat model first; an editing model at a provider takes them as
     * said. The photo it worked from is kept there and then (2026-09-18): the shot on the
     * camera screen has not been saved yet, and without this a picture in the shelf could
     * outlive the photo behind it.
     */
    private suspend fun transform(h: Hold, said: String) {
        val jpeg = h.jpeg ?: return failed(h, "there is no photo to work from")
        val c = h.chat
        val prompt = if (c == null) said else try {
            c.calls.describe(said, jpeg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the description pass failed: ${e.message}")
            return failed(h, said(e, c.entry))
        }
        if (prompt.isEmpty()) return failed(h, "Could not describe the picture")
        Log.i(TAG, "transform: ${prompt.take(160)}")
        val kept = h.photoFile ?: runCatching { Stores.photos(this).save(jpeg) }
            .onFailure { e -> Log.e(TAG, "could not keep the photo", e) }
            .getOrNull()
        val p = h.pictures
        val id = if (p is HoldPictures.Cloud) shelf.start(p.pictures, p.entry.id, prompt, jpeg, kept?.name)
            else generations.start(prompt, jpeg, kept?.name)
        h.update {
            it.withTool(ToolCall(id, "Transforming photo", prompt, running = false, generation = id))
                .copy(keptPhoto = kept?.name ?: it.keptPhoto, generation = id)
        }
        finished(h)
    }

    /** The Translator: what was said, in the pair's other language, streamed as an answer is. */
    private suspend fun translate(h: Hold, said: String) {
        val c = h.chat ?: return failed(h, VoiceState.NO_MODEL)
        val pair = h.translate ?: return
        try {
            c.calls.translate(said, pair.from, pair.to) { text(h, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the translation failed: ${e.message}")
            return failed(h, said(e, c.entry))
        }
        finished(h)
    }

    /** Why a model call on [entry] failed, as Ask says it ([whyFailed]), the server's machine by its name. */
    private fun said(e: Exception, entry: Entry): String {
        val server = Prefs.server(this)
        return whyFailed(e, entry.provider, server.name.ifBlank { server.host })
    }

    /** The answer is in: the turn kept whole in its conversation, a translation under its pair. */
    private fun finished(h: Hold) {
        val s = h.state
        h.turn?.let { t ->
            conversations.update(t) {
                it.copy(question = s.transcript.ifBlank { it.question }, parts = settled(s.parts), state = TurnState.Done, error = null)
            }
        }
        keepTranslation(s)
        logTimed(h)
        // Finished after the Translator closed: kept, so it leaves the page.
        h.update { if (it.translate != null && translatePair == null) VoiceState() else it.copy(phase = Phase.Idle) }
        if (hold === h) hold = null
    }

    /**
     * The shelf's Try again: [item]'s picture made again from its prompt, from [photo] when there
     * is one, by the picture model picked now: a cloud one from here, the Mac's by the `generate`
     * frame ([GenerationFetcher.again]). The old row goes where it was made: one of the server's
     * is told to it, the C1's only leaves the shelf. No key: why, on the old row.
     */
    suspend fun pictureAgain(item: Generated, photo: ByteArray?) {
        val m = holdPictures(if (photo != null) ModelSlot.ImageToImage else ModelSlot.TextToImage)
        if (m == HoldPictures.Mac) {
            generations.again(item, photo)
            return
        }
        withContext(Dispatchers.IO) {
            val prompt = item.prompt.trim()
            when {
                m is HoldPictures.NoKey -> generated.fail(item.id, "no key for ${m.provider}")
                prompt.isEmpty() -> generated.fail(item.id, "Nothing was asked for this one")
                m is HoldPictures.Cloud -> {
                    val id = shelf.start(m.pictures, m.entry.id, prompt, photo, named = if (photo != null) item.source else null)
                    Log.i(TAG, "${item.id} asked again as $id on ${m.entry.id}")
                    forgetPicture(item.id)
                    generated.delete(item.id)
                }
            }
        }
    }

    /** Stop or delete [id]: one being made here is dropped here; the server is told only of its own. */
    fun forgetPicture(id: String) {
        if (shelf.stop(id) || generated.get(id)?.c1 == true) return
        generations.forget(id)
    }

    /** A provider's picture (PNG, JPEG, WebP) as the JPEG the shelf keeps, at the server's quality, 92. */
    private fun toJpeg(image: ByteArray): ByteArray {
        val b = BitmapFactory.decodeByteArray(image, 0, image.size) ?: throw java.io.IOException("the picture could not be read")
        try {
            return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            b.recycle()
        }
    }

    /** The model call failed: a hold with nothing heard leaves the record, any other keeps why; the page says why whatever the store does. */
    private fun failed(h: Hold, why: String) {
        val s = h.state
        h.turn?.let { t ->
            runCatching {
                if (s.transcript.isBlank()) conversations.drop(t)
                else conversations.update(t) { it.copy(question = s.transcript, parts = settled(s.parts), state = TurnState.Failed, error = why) }
            }.onFailure { e -> Log.w(TAG, "could not keep the failed turn", e) }
        }
        h.update { it.copy(phase = Phase.Error, error = why) }
        if (hold === h) hold = null
    }

    /** Tool lines as kept: none still running. */
    private fun settled(parts: List<Part>) = parts.map { p -> if (p is Part.Tool) Part.Tool(p.call.copy(running = false)) else p }

    /** The server said no before the words came back: a hold with nothing heard leaves the record, any other keeps why. */
    private fun failTurn(h: Hold, message: String) {
        val t = h.turn ?: return
        val s = _state.value
        if (s.transcript.isBlank()) conversations.drop(t)
        else conversations.update(t) { it.copy(question = s.transcript, state = TurnState.Failed, error = message) }
    }

    /** The socket went before the words came back: nothing will come of the hold, the server did not answer for speech. */
    private fun lost(h: Hold) {
        val server = Prefs.server(this)
        h.turn?.let { conversations.drop(it) }
        fail("${server.name.ifBlank { server.host }} did not answer, so nothing was heard")
    }

    /** A Translator hold that came back with words: kept under its pair for the saved list. */
    private fun keepTranslation(s: VoiceState) {
        val pair = s.translate ?: return
        if (s.transcript.isBlank() || s.answer.isBlank()) return
        runCatching { translations.add(pair, s.transcript, s.answer) }
            .onFailure { e -> Log.w(TAG, "could not keep the translation", e) }
    }

    /**
     * A hold on the Translator in local mode: the audio is kept here, pre-roll first, as it
     * would have been sent, and [finishLocal] takes it from there on release. A pair the
     * device cannot hear, or engines that are not ready, fail at once.
     */
    private fun beginLocal(translate: LanguagePair) {
        val why = when (val st = local.state.value) {
            is LocalState.Failed -> st.message
            is LocalState.Loading -> "Still loading"
            else -> if (OfflineVoice.hears(translate.from)) null else "${translate.from.word} can't be heard offline"
        }
        if (why != null) {
            _state.value = VoiceState(phase = Phase.Error, error = why, askedAt = System.currentTimeMillis(), translate = translate)
            Log.i(TAG, "local hold refused: $why")
            return
        }
        _state.value = VoiceState(phase = Phase.Listening, askedAt = System.currentTimeMillis(), translate = translate)
        synchronized(sendLock) {
            localPcm = java.io.ByteArrayOutputStream().apply { write(ring.snapshot()) }
            streaming = true
        }
        Log.i(TAG, "local utterance begun (translate $translate)")
    }

    /**
     * Release, in local mode: the server's silence gate, then Parakeet, then Bergamot, with
     * the same phases an online hold goes through, so each design draws it as it does one.
     * No partials: the words appear together, about three seconds after release.
     */
    private fun finishLocal(bytes: ByteArray) {
        val translate = _state.value.translate ?: return
        _state.update { it.copy(phase = Phase.Transcribing) }
        localJob = scope.launch {
            try {
                val pcm = ShortArray(bytes.size / 2) { (bytes[2 * it].toInt() and 0xff or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
                val levels = SpeechGate.levels(pcm)
                Log.i(TAG, "local: ${pcm.size / 16} ms, active ${"%.1f".format(levels.active)} floor ${"%.1f".format(levels.floor)} dBFS")
                val said = if (levels.speech) local.hear(pcm) else ""
                if (said.isEmpty()) { fail("No speech detected"); return@launch }
                _state.update { it.copy(transcript = said, phase = Phase.Answering) }
                val heard = local.translate(translate, said)
                _state.update { it.withText(heard) }
                keepTranslation(_state.value)
                _state.update { it.copy(phase = Phase.Idle) }
                // Finished after the Translator closed: kept, so it leaves the page.
                if (translatePair == null) _state.value = VoiceState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "local hold failed", e)
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }


    private fun endUtterance() {
        val pcm = synchronized(sendLock) {
            streaming = false
            localPcm.also { localPcm = null }
        }
        val h = hold
        val cloud = h?.speech
        if (pcm != null && cloud == null) {
            Log.i(TAG, "local utterance ended")
            finishLocal(pcm.toByteArray())
            return
        }
        h?.releasedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = Phase.Transcribing) }
        if (pcm != null && h != null && cloud != null) {
            Log.i(TAG, "utterance ended, ${pcm.size() / 1024} kB to ${cloud.entry.id}")
            hearHere(h, cloud, pcm.toByteArray())
            return
        }
        session?.sendText(Protocol.end())
        Log.i(TAG, "utterance ended")
    }

    /**
     * The release, on a cloud speech model: the hold's audio heard here, the silence gate first
     * (ask/Speech.kt), the Translator's in its language, every pass metered; then the hold goes
     * on as one the Mac heard does at `final`. Nothing heard, or the provider failing, ends it as
     * the server's error would.
     */
    private fun hearHere(h: Hold, cloud: HoldSpeech.Cloud, pcm: ByteArray) {
        guarded(h) {
            val words = try {
                cloud.speech.hear(pcm, h.translate?.from?.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${cloud.entry.id} did not hear the hold: ${e.message}")
                val why = said(e, cloud.entry)
                failTurn(h, why)
                fail(why)
                return@guarded
            }
            if (words.isEmpty()) {
                failTurn(h, NO_SPEECH)
                fail(NO_SPEECH)
                return@guarded
            }
            heard(h, words)
            if (h.dictate) dictated(h)
        }
    }

    /**
     * One line per hold that came back whole: what it was, the chat model it ran on ("none"
     * for one that asked none), and from the release to `final` and to the first text ("none"
     * for a hold that answers in no words: a picture, dictation). Grep `hold timed`.
     */
    private fun logTimed(h: Hold) {
        val r = h.releasedAt.takeIf { it > 0 } ?: return
        val kind = when {
            h.transform -> "transform"
            h.translate != null -> "translate"
            h.dictate -> "dictate"
            h.jpeg != null -> "photo"
            else -> "ask"
        }
        fun since(t: Long) = if (t >= r) "${t - r} ms" else "none"
        Log.i(TAG, "hold timed: $kind, chat ${h.chat?.entry?.id ?: "none"}, " +
            "release to final ${since(h.finalAt)}, to first text ${since(h.firstTextAt)}")
    }

    private fun fail(message: String) {
        // Only the FIRST failure is meaningful. abort() below cancels the socket,
        // which re-enters this through onFailure; without this guard a useful
        // message like "No speech detected" gets overwritten by the
        // "Socket closed" that our own cancellation produced.
        if (_state.value.phase == Phase.Error) return
        synchronized(sendLock) { streaming = false }
        session?.abort()
        session = null
        hold = null
        _state.update { it.copy(phase = Phase.Error, error = message) }
    }

    // ---- plumbing ----------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Croissant")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}

/**
 * The chat model a hold's words go to, made at its press by Core: ready to call, or why there
 * is none, which Ask says at the press.
 */
sealed interface HoldChat {
    /** [chat] metered into the ledger, [calls] its single calls under their fixed prompts (ask/Calls.kt). */
    class Ready(val entry: Entry, val chat: ChatApi, val calls: Calls) : HoldChat
    /** The model picked is [provider]'s, and it has no key kept. */
    class NoKey(val provider: String) : HoldChat
    /** None picked, and no default among what the providers list. */
    data object NoModel : HoldChat
}

/**
 * What a hold's or a tape's words are heard by, settled at its start by Core: the Mac's, or a
 * cloud model of the C1's providers, or why there is none, which Ask says at the press.
 */
sealed interface HoldSpeech {
    /** The server's, over its socket: [pick] the model asked for (one on the Mac), null for its default. */
    class Mac(val pick: String?) : HoldSpeech
    /** [entry] at its provider, called from here: [speech] metered into the ledger (ask/Speech.kt). */
    class Cloud(val entry: Entry, val speech: MeteredSpeech) : HoldSpeech
    /** The model picked is [provider]'s, and it has no key kept. */
    class NoKey(val provider: String) : HoldSpeech
}

/**
 * What a picture is made by, settled by Core when it is asked for: the Mac's, or a cloud model
 * of the C1's providers, or why there is none.
 */
sealed interface HoldPictures {
    /** The server's, by its `generate` frame, with the device's picks (net/Generations.kt). */
    data object Mac : HoldPictures
    /** [entry] at its provider, called from here (ask/Pictures.kt), onto the shelf. */
    class Cloud(val entry: Entry, val pictures: CloudPictures) : HoldPictures
    /** The model picked is [provider]'s, and it has no key kept. */
    class NoKey(val provider: String) : HoldPictures
}

/**
 * Why a call to [p] failed, as Ask says it: a provider that did not answer, by name and, on the
 * server's machine, where ("Ollama on" [place] "did not answer"); else its own words.
 */
internal fun whyFailed(e: Exception, p: Provider, place: String?): String {
    if (e is AskError) return "${p.title}: " + e.message.orEmpty().replace(Regex("^\\d{3}: "), "")
    if (e !is java.io.IOException) return e.message ?: e.javaClass.simpleName
    return p.title + (if (p.mac && !place.isNullOrBlank()) " on $place" else "") + " did not answer"
}
