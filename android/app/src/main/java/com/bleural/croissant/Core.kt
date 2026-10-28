package com.bleural.croissant

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bleural.croissant.ask.AskProviders
import com.bleural.croissant.ask.Calls
import com.bleural.croissant.ask.Chat
import com.bleural.croissant.ask.ChatApi
import com.bleural.croissant.ask.CloudPictures
import com.bleural.croissant.ask.Costs
import com.bleural.croissant.ask.Entry
import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.KeystoreSealer
import com.bleural.croissant.ask.Kind
import com.bleural.croissant.ask.Ledger
import com.bleural.croissant.ask.Listing
import com.bleural.croissant.ask.ListingsStore
import com.bleural.croissant.ask.Metered
import com.bleural.croissant.ask.MeteredSpeech
import com.bleural.croissant.ask.ModelTest
import com.bleural.croissant.ask.Prices
import com.bleural.croissant.ask.Provider
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.ask.Providers
import com.bleural.croissant.ask.ProvidersStore
import com.bleural.croissant.ask.Speech
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Links
import com.bleural.croissant.audio.Recorder
import com.bleural.croissant.audio.RecorderState
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.atelier.Atelier
import com.bleural.croissant.atelier.BadApp
import com.bleural.croissant.atelier.DeviceBridge
import com.bleural.croissant.bluetooth.BluetoothRepo
import com.bleural.croissant.data.AskCatalog
import com.bleural.croissant.data.AskPrompt
import com.bleural.croissant.data.BenchCase
import com.bleural.croissant.data.BenchGroup
import com.bleural.croissant.data.BenchScore
import com.bleural.croissant.data.BenchScoresStore
import com.bleural.croissant.data.HoldPrompt
import com.bleural.croissant.data.Prompts
import com.bleural.croissant.data.PromptsStore
import com.bleural.croissant.data.ToolSpec
import com.bleural.croissant.data.ToolExecutor
import com.bleural.croissant.data.BatteryLog
import com.bleural.croissant.data.ConversationsStore
import com.bleural.croissant.data.EventsStore
import com.bleural.croissant.data.Generated
import com.bleural.croissant.data.GeneratedStore
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.PhotosStore
import com.bleural.croissant.data.RecordingsStore
import com.bleural.croissant.data.RemindersStore
import com.bleural.croissant.data.Stores
import com.bleural.croissant.data.Timer
import com.bleural.croissant.data.TimersStore
import com.bleural.croissant.data.TranslationsStore
import com.bleural.croissant.offline.LocalState
import com.bleural.croissant.offline.OfflineVoice
import com.bleural.croissant.esim.ActivationCode
import com.bleural.croissant.esim.EsimRepo
import com.bleural.croissant.esim.EsimTask
import com.bleural.croissant.metronome.Metronome
import com.bleural.croissant.metronome.Tuner
import com.bleural.croissant.net.ModelEntry
import com.bleural.croissant.net.ModelList
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.BenchRun
import com.bleural.croissant.net.BenchState
import com.bleural.croissant.net.Models
import com.bleural.croissant.net.ServerModels
import com.bleural.croissant.net.Usage
import com.bleural.croissant.net.UsageClient
import com.bleural.croissant.send.Addresses
import com.bleural.croissant.served.Config
import com.bleural.croissant.served.ConfigServer
import com.bleural.croissant.served.PhonePage
import com.bleural.croissant.served.Way
import com.bleural.croissant.served.Ways
import com.bleural.croissant.setup.Gesture
import com.bleural.croissant.update.UpdatePhase
import com.bleural.croissant.update.Updater
import com.bleural.croissant.setup.ServerFinder
import com.bleural.croissant.setup.SetupUi
import com.bleural.croissant.setup.Step
import com.bleural.croissant.setup.Zone
import com.bleural.croissant.setup.Zones
import com.bleural.croissant.tailscale.TailscaleRepo
import com.bleural.croissant.weather.WeatherSource
import com.bleural.croissant.wifi.WifiQr
import com.bleural.croissant.wifi.WifiRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device: everything the app can do, and nothing about how it looks. The service and
 * its state, the repos, the stores, the settings as they are persisted and applied, and
 * what the side button means. One instance, owned by [MainActivity], handed whole to
 * whichever [UiShell] is installed. It holds that UI too ([ui]), built from [look] by the
 * factory it is given, because Settings can swap it while the device keeps running.
 *
 * The rule that keeps the UI swappable: **this file never imports `com.bleural.croissant.ui`**.
 * A UI reads what it wants off here and calls what it likes; nothing here knows a screen
 * exists, except through the handful of calls on [UiShell].
 *
 * It holds the Activity because nearly all of this is Activity-scoped anyway: prefs,
 * device policy, the window's keep-awake flag. The app is one activity that is never
 * recreated (HOME, lock task, no config changes), so there is nothing to outlive. It
 * observes that lifecycle itself rather than having five callbacks forwarded to it by hand.
 */
class Core(
    private val activity: ComponentActivity,
    private val designFor: (Look) -> UiShell,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "Core"
        /** Two short presses this close together open (or close) the camera; one alone sleeps after it. */
        private const val DOUBLE_PRESS_MS = 300L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- the service ------------------------------------------------------

    // Must be Compose state, not a plain field: the service binds asynchronously, and
    // without this the UI keeps collecting the placeholder flows forever and never
    // renders a transcript or an answer.
    private var service by mutableStateOf<VoiceService?>(null)

    private val idleVoice = MutableStateFlow(VoiceState())
    private val idleLevel = MutableStateFlow(0f)
    private val idleRinging = MutableStateFlow(emptyList<Timer>())
    private val idleRecorder = MutableStateFlow(RecorderState())
    private val idleTranscribe = MutableStateFlow<TranscribeState?>(null)
    private val idleLocal = MutableStateFlow<LocalState>(LocalState.Off)
    private val idleAskConv = MutableStateFlow<String?>(null)

    /**
     * The live state, or a placeholder until the service is bound. Read these from a
     * composable and the read of [service] behind them brings it back when it arrives.
     */
    val voice: StateFlow<VoiceState> get() = service?.state ?: idleVoice
    val level: StateFlow<Float> get() = service?.level ?: idleLevel
    val ringing: StateFlow<List<Timer>> get() = service?.ringing ?: idleRinging
    val recorder: StateFlow<RecorderState> get() = service?.recorder?.state ?: idleRecorder
    val transcribing: StateFlow<TranscribeState?> get() = service?.transcriber?.state ?: idleTranscribe
    /** The Translator's local mode: off, loading, ready, or failed with why. */
    val local: StateFlow<LocalState> get() = service?.local?.state ?: idleLocal
    /**
     * The conversation Ask shows and a hold there continues, from [conversations]; null
     * for a blank page, where the next hold starts one. The turn in flight is [voice]
     * while its `conv` is this one.
     */
    val askConversation: StateFlow<String?> get() = service?.askConv ?: idleAskConv

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as VoiceService.LocalBinder).service
            s.onShortPress = ::onShortPress
            // Straight through, not via the main thread: the page must see "sending" before
            // the service drops the words (Agents.say and Atelier.hear are safe from any thread).
            s.onDictated = { words -> if (atelierWords) atelier.hear(words) else if (agentWords) agents.say(words) }
            s.canDictate = { atelierWords || (agentWords && agents.canTake) }
            s.onDictateRefused = { Cues.play(Sound.ExchangeFailed) }
            s.holdPrompt = { photo, now -> holdPrompt(photo, now) }
            s.holdChat = { holdChat() }
            s.holdSpeech = { holdSpeech() }
            s.holdPictures = { holdPictures(it) }
            // None with no picture model at all: nothing would take a description.
            s.wantsDescription = { modelFor(ModelSlot.ImageToImage)?.let { CloudPictures.wantsDescription(it.local) } ?: false }
            s.appTool = { name, args, show -> atelier.callTool(name, args) { id -> if (show) ui.openAtelierApp(id) } }
            s.working = { bench.state.value is BenchState.Running || appAsks.get() > 0 }
            // A hold's prompt offers the server's tools it runs, and its model is one the providers list.
            if (models == null) scope.launch { refreshModels() }
            scope.launch { refreshListings() }
            if (translatorOpen) s.setTranslate(translatorPair)
            if (atelierWords || agentWords) s.setDictate(true)
            s.holdAwake(updating)
            service = s
            watchPhase(s)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            phaseJob?.cancel()
            service = null
        }
    }

    private var bound = false

    /** Start the service and bind to it. Called once the microphone permission is in hand. */
    fun bind() {
        val intent = Intent(activity, VoiceService::class.java)
        ContextCompat.startForegroundService(activity, intent)
        activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    // ---- device status ----------------------------------------------------

    private val status = DeviceStatusMonitor(activity)
    val device: StateFlow<DeviceStatus> = status.state

    // ---- updates ------------------------------------------------------------

    /** Settings > update (update/Updater.kt). */
    val update = Updater(activity, scope, device)

    /** An install under way: the side button, the wheel, sleep and power are refused until it is done. */
    val updating: Boolean get() = update.ui.value.locked

    // ---- settings: persisted in Prefs, applied to the device ---------------

    var server by mutableStateOf(Prefs.defaultServer())
        private set
    var volume by mutableIntStateOf(0)
        private set
    /** The control centre's sound tile: every sound of the app off, the timer's ringtone excepted. */
    var muted by mutableStateOf(false)
        private set
    var brightness by mutableIntStateOf(0)
        private set
    /** Settings > Device > Battery > Rest: the idle time before the screen and the mic go off. */
    var rest by mutableIntStateOf(Idle.REST_DEFAULT)
        private set
    /** Settings > Device > Battery > Sleep: the time still with the screen off before the R1 suspends. */
    var sleep by mutableIntStateOf(Idle.SLEEP_DEFAULT)
        private set
    var batteryTrack by mutableStateOf(true)
        private set
    var batteryEvery by mutableIntStateOf(BatteryTracker.DEFAULT_EVERY_MS)
        private set

    /** The design and variant chosen in Settings. */
    var look by mutableStateOf(Look.DEFAULT)
        private set

    /**
     * The design on screen. A new one is built whenever [look] changes, so a swap starts
     * from its Home with nothing carried over from the design it replaced; the device under
     * it (the service, the timers, a recording) does not notice.
     */
    var ui: UiShell by mutableStateOf(designFor(Look.DEFAULT))
        private set

    fun saveLook(l: Look) {
        if (l == look) return
        look = l
        Prefs.setLook(activity, l)
        ui = designFor(l)
    }

    /** Settings > Device > Tones: the interface's sound pack, one of [tonePacks] (audio/Cues.kt). */
    var tones by mutableStateOf(Cues.DEFAULT_PACK)
        private set
    /** The packs this build carries. */
    val tonePacks: List<String> by lazy { Cues.packs(activity) }
    private var tonesPreview: Cues.Preview? = null

    /** Picked: saved, and the pack in use from now on (live, once it has decoded). */
    fun saveTones(pack: String) {
        if (pack == tones) return
        tones = pack
        Prefs.setSoundPack(activity, pack)
        Cues.use(activity, pack)
    }

    /** A pack's phrase is playing on the Tones page: its key reads pause. */
    var tonesPlaying by mutableStateOf(false)
        private set

    /** The Tones page is up (true) or gone: every pack is decoded for [playTones] only while it is. */
    fun previewTones(on: Boolean) {
        if (on) { if (tonesPreview == null) tonesPreview = Cues.Preview(activity) { tonesPlaying = false } }
        else { tonesPreview?.release(); tonesPreview = null; tonesPlaying = false }
    }

    /** [pack]'s phrase, cutting off the last one; nothing outside the Tones page. */
    fun playTones(pack: String) {
        val p = tonesPreview ?: return
        p.hear(pack)
        tonesPlaying = true
    }

    /** The phrase cut off. */
    fun stopTones() {
        tonesPreview?.stop()
        tonesPlaying = false
    }

    /** Asked once per start (a binder call), cleared when the owner is released from Power. */
    var isOwner by mutableStateOf(false)
        private set

    fun saveServer(s: Prefs.Server) {
        Prefs.setServer(activity, s)
        server = Prefs.server(activity)
        // Another server, another list; the picks stay, and one it does not know is its default.
        models = null
        // A provider on the server's machine is somewhere else now.
        dropClients()
        relist()
    }

    fun saveVolume(pct: Int) {
        volume = pct
        Prefs.setVolume(activity, pct)
        applySound()
    }

    fun saveMuted(on: Boolean) {
        muted = on
        Prefs.setMuted(activity, on)
        applySound()
    }

    /** Tailscale up or down from a switch (the control centre's key), its state shown as soon as the interface has it. */
    fun setTailnet(up: Boolean) {
        tailscale.setUp(up)
        status.recheckTailnet()
    }

    fun saveBrightness(pct: Int) {
        brightness = pct
        Prefs.setBrightness(activity, pct)
        DeviceLevels.applyBrightness(activity, pct)
    }

    fun saveRest(ms: Int) {
        rest = ms
        Prefs.setRest(activity, ms)
        DeviceLevels.applyRest(activity, ms)
    }

    fun saveSleep(ms: Int) {
        sleep = ms
        Prefs.setSleep(activity, ms)
        service?.setSleep(ms)
    }

    fun saveBatteryTrack(on: Boolean) {
        batteryTrack = on
        Prefs.setBatteryTrack(activity, on)
        BatteryTracker.sync(activity)
        if (on) BatteryTracker.sample(activity, "start")
    }

    fun saveBatteryEvery(ms: Int) {
        batteryEvery = ms
        Prefs.setBatteryEvery(activity, ms)
        BatteryTracker.sync(activity)
    }

    /** The Sound level and the mute, applied together: the streams, and the cues' gain. The ringtone reads the level itself. */
    private fun applySound() {
        DeviceLevels.applyVolume(activity, volume, muted)
        Cues.gain = if (muted) 0f else DeviceLevels.gain(volume)
        // The metronome is the feature, not a cue: like the timer's ringtone, it keeps the level through a mute.
        metronome.gain = DeviceLevels.gain(volume)
    }

    // ---- repos and stores --------------------------------------------------

    val wifi by lazy { WifiRepo(activity) }
    val tailscale by lazy { TailscaleRepo({ server.host }, { server.port }, activity.cacheDir) }
    val bluetooth by lazy { BluetoothRepo(activity) }
    val esim by lazy { EsimRepo(activity.applicationContext) }
    /**
     * The weather: here and the places added, each with its forecast, refreshed while the device
     * is awake; the pin, Home's fallback when there is no recent fix; the search for a place to
     * add.
     */
    val weather by lazy { WeatherSource(activity.applicationContext, scope) }

    val reminders: RemindersStore by lazy { Stores.reminders(activity) }
    val events: EventsStore by lazy { Stores.events(activity) }
    val timers: TimersStore by lazy { Stores.timers(activity) }
    val photos: PhotosStore by lazy { Stores.photos(activity) }
    val generated: GeneratedStore by lazy { Stores.generated(activity) }
    val recordings: RecordingsStore by lazy { Stores.recordings(activity) }
    val battery: BatteryLog by lazy { Stores.battery(activity) }
    val translations: TranslationsStore by lazy { Stores.translations(activity) }
    /** Every Ask conversation, for Ask and History. */
    val conversations: ConversationsStore by lazy { Stores.conversations(activity) }

    // ---- start and stop ----------------------------------------------------

    /**
     * Everything the app has to put back on a cold start, before the first frame. Called
     * from `MainActivity.onCreate`; the lifecycle callbacks below arrive on their own once
     * this has registered as an observer.
     */
    fun create() {
        isOwner = Kiosk.isOwner(activity)
        Kiosk.apply(activity)
        server = Prefs.server(activity)
        prompts = promptsStore.prompts
        benchScores = scoresStore.scores
        // Settings > Device levels: persisted in prefs, put back on every start.
        volume = Prefs.volume(activity); muted = Prefs.muted(activity); applySound()
        Cues.load(activity); tones = Cues.pack
        brightness = Prefs.brightness(activity); DeviceLevels.applyBrightness(activity, brightness)
        rest = Prefs.rest(activity); DeviceLevels.applyRest(activity, rest)
        sleep = Prefs.sleep(activity)
        batteryTrack = Prefs.batteryTrack(activity); batteryEvery = Prefs.batteryEvery(activity)
        translatorPair = Prefs.translatorPair(activity)
        _recents.value = Prefs.wheelRecents(activity)
        _pins.value = Prefs.wheelPins(activity)
        _agentPins.value = Prefs.wheelAgents(activity)
        modelPicks = picks()
        serverDefault = Prefs.chatDefault(activity)
        liveWordsSet = Prefs.liveWords(activity)
        acpBridges = Prefs.acpBridges(activity)
        acpBridge = acpBridges.firstOrNull { it.token == Prefs.acpActive(activity) } ?: acpBridges.firstOrNull() ?: Prefs.AcpBridge()
        look = Prefs.look(activity)
        if (look != Look.DEFAULT) ui = designFor(look)
        // Where an update was left: an install to finish comes up locked before the first frame,
        // and the first start of a new version opens on the page that says so.
        update.start()
        if (update.ui.value.phase == UpdatePhase.Updated) ui.openUpdate()
        val finished = Prefs.setupFinished(activity)
        _setup.value = SetupUi(open = !finished, finished = finished, skipped = Prefs.setupSkipped(activity)
            .split(',').mapNotNull { n -> Step.entries.firstOrNull { it.name == n } }.toSet())
        // Direct boot: we come up before user storage is unlocked. Once it is, pick up a
        // server saved by a build that still kept prefs in credential-encrypted storage.
        if (!activity.getSystemService(UserManager::class.java).isUserUnlocked) {
            activity.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    server = Prefs.server(activity)
                    activity.unregisterReceiver(this)
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        scope.launch {
            device.map { it.wifiConnected to it.tailnetUp }.distinctUntilChanged()
                .collect { (wifiUp, tailnetUp) -> Links.report(wifiUp, tailnetUp) }
        }
        // The tailnet up (a start without it, a drop): the server's list and the providers' asked again.
        scope.launch {
            device.map { it.tailnetUp }.distinctUntilChanged().collect { if (it) relist() }
        }
        scope.launch {
            update.ui.map { it.locked }.distinctUntilChanged().collect { locked ->
                awakeForUpdate = locked
                applyAwake()
                service?.holdAwake(locked)
            }
        }
        scope.launch {
            device.map { it.wifiConnected }.distinctUntilChanged().collect { if (it) update.maybeCheck() }
        }
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        asleep = false
        kernelButton.start()
        status.start()
        weather.start()
        update.maybeCheck()
    }

    override fun onStop(owner: LifecycleOwner) {
        asleep = true
        status.stop()
        weather.stop()
    }

    override fun onResume(owner: LifecycleOwner) {
        Kiosk.enterLockTask(activity)
        ui.woke()
    }

    override fun onPause(owner: LifecycleOwner) = ui.settle()

    override fun onDestroy(owner: LifecycleOwner) {
        kernelButton.stop()
        if (bound) runCatching { activity.unbindService(connection) }
        scope.cancel()
    }

    // ---- the exchange ------------------------------------------------------

    /** The phase the sound events last fired for; a plain field, because nothing draws it. */
    private var lastPhase = Phase.Idle
    private var phaseJob: Job? = null

    /**
     * What a change of phase does beyond the screen it is drawn on: its cue, the hold
     * cutting to Ask from wherever the room was, and the screen staying awake while an
     * utterance is in flight, because an answer must not go dark under the user's eyes.
     */
    private fun watchPhase(s: VoiceService) {
        phaseJob?.cancel()
        phaseJob = scope.launch {
            s.state.map { it.step }.distinctUntilChanged().collect { (phase, _) ->
                keepScreenOn(phase != Phase.Idle)
                // The hold cuts to Ask, open, unless it is about the photo on the camera
                // screen, which answers there, or on the Translator, which translates there.
                val held = s.state.value
                // Nor on a page that takes a hold's words (an Atelier app, an agent's conversation).
                if (phase == Phase.Listening && !held.withPhoto && held.translate == null && !held.dictate) ui.cutToAsk()
                // No server, model or key yet: Ask comes up all the same, to say so and offer what to set.
                if (held.refused && held.translate == null && !held.dictate) ui.cutToAsk()
                when {
                    phase == Phase.Error -> Cues.play(Sound.ExchangeFailed)
                    phase == Phase.Listening -> Cues.play(Sound.ListeningStart)
                    lastPhase == Phase.Listening -> Cues.play(Sound.ListeningEnd)
                    lastPhase == Phase.Answering && phase == Phase.Idle -> Cues.play(Sound.AnswerArrived)
                }
                lastPhase = phase
            }
        }
    }

    private var awakeForVoice = false
    private var awakeForUpdate = false

    private fun keepScreenOn(on: Boolean) {
        awakeForVoice = on
        applyAwake()
    }

    /** An utterance in flight or an install under way keeps the screen up; either alone is enough. */
    private fun applyAwake() {
        if (awakeForVoice || awakeForUpdate) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun leaveAsk() {
        service?.clearExchange()
    }

    /** History: a conversation opened on Ask, where the next hold continues it. */
    fun openConversation(id: String) {
        service?.openConversation(id)
    }

    /** Ask's "+": a blank page; the next hold starts a new conversation. */
    fun newConversation() {
        service?.openConversation(null)
    }

    /** History's delete, one or many. */
    fun deleteConversations(ids: Set<String>) {
        service?.deleteConversations(ids)
    }

    fun attachPhoto(jpeg: ByteArray?, file: File?) {
        service?.attachPhoto(jpeg, file)
    }

    /** The Transform page is open or closed: its hold makes a picture rather than answering. */
    fun setTransform(on: Boolean) {
        service?.setTransform(on)
    }

    // ---- the models ----------------------------------------------------------

    /**
     * What the server can run per slot: its `GET /providers`, null until [refreshModels] has
     * asked or while the server cannot be reached. Its own models (the Mac's Whisper, its
     * picture model) are picked from it beside the C1's providers' ([modelList]);
     * chat from the C1's own providers alone. It says which of Ask's tools the server
     * runs ([serverTools]).
     */
    var models by mutableStateOf<ServerModels?>(null)
        private set

    /** The entry picked per slot, by id. A slot missing takes its default. */
    var modelPicks by mutableStateOf(emptyMap<ModelSlot, String>())
        private set

    private fun picks(): Map<ModelSlot, String> = Prefs.modelPicks(activity)
        .mapNotNull { (k, v) -> ModelSlot.entries.firstOrNull { it.key == k }?.let { it to v } }.toMap()

    /**
     * The server's chat default as it last said it, kept (Prefs), so a hold before the network
     * is up, or before the server has answered since the start, still has it.
     */
    private var serverDefault by mutableStateOf<String?>(null)

    /** Ask the server for its list again (a Settings page opening): the list, or why not. Its chat default is kept. */
    suspend fun refreshModels(): Result<ServerModels> = withContext(Dispatchers.IO) {
        runCatching { Models.fetch(server) }
    }.also { r ->
        val m = r.getOrNull() ?: return@also
        models = m
        val d = m.list(ModelSlot.Chat).default
        if (d != serverDefault) { serverDefault = d; Prefs.setChatDefault(activity, d) }
    }

    /**
     * The server's list and the providers' asked again in the background: a server saved, the
     * tailnet up, a hold with no model to run on, so the next one has one.
     */
    private fun relist() {
        scope.launch { refreshModels() }
        scope.launch { refreshListings() }
    }

    /**
     * What a model page offers for [slot]: every model the C1's providers last listed for it,
     * cheapest first within each, with what each costs (ask/Costs.kt); for speech, pictures
     * and the transform the Mac's own first, as the server lists them. Its default is the
     * server's while it is one of these. Read from a page, it is drawn again when a list or a
     * provider changes.
     */
    fun modelList(slot: ModelSlot): ModelList {
        val entries = listed(slot)
        if (slot == ModelSlot.Chat) return costs.list(ModelSlot.Chat, entries, entries.firstOrNull { it.id == chatDefault(entries) })
        val server = models?.list(slot)
        val mac = server?.entries.orEmpty().filter { it.local }
        return ModelList(server?.default?.takeIf { d -> mac.any { it.id == d } }, mac + costs.list(slot, entries, null).entries)
    }

    /** The models the C1's providers can run for [slot]: each ready provider's, as it last listed them. */
    private fun listed(slot: ModelSlot): List<Entry> {
        // Read, so a page reading [modelList] draws again when a provider or its list changes.
        providersChanged
        return Providers.entries(slot, readyProviders(), ::listing)
    }

    /** What a hold that picks no chat model gets: the server's default as it last said it, while the C1's providers list it. */
    private fun chatDefault(entries: List<Entry> = listed(ModelSlot.Chat)): String? =
        serverDefault?.takeIf { d -> entries.any { it.id == d } }

    /**
     * Ollama's list asked again, and any other ready provider's once a day old (a model page
     * opening, the app starting); the prices too, a day old, while a provider is not Ollama.
     */
    suspend fun refreshListings() = coroutineScope {
        val ready = readyProviders()
        val now = System.currentTimeMillis()
        for (p in ready) {
            val at = listing(p.id)?.at ?: 0L
            if (p.kind == Kind.Ollama || now - at > ListingsStore.MAX_AGE_MS) launch { listProvider(p.id) }
        }
        if (ready.any { it.kind != Kind.Ollama }) launch { refreshPrices() }
    }

    /**
     * Pick [id] for [slot], from the next hold on; null goes back to the default.
     * Another speech model puts the words while talking back to its own default.
     */
    fun pickModel(slot: ModelSlot, id: String?) {
        if (slot == ModelSlot.Stt && id != modelPicks[slot]) setLiveWords(null)
        Prefs.setModelPick(activity, slot.key, id)
        modelPicks = picks()
    }

    // ---- providers and keys --------------------------------------------------------

    /** Where the C1 keeps what Ask needs before the first unlock: providers, keys, lists, prices, its count. */
    private val askFiles: File by lazy { activity.createDeviceProtectedStorageContext().filesDir }

    // The C1's own providers, keys and model lists (ask/): every chat call goes from here to
    // its provider, the key only in that provider's header.
    private val askProviders by lazy {
        AskProviders(ProvidersStore(File(askFiles, "providers.json")), KeysStore(File(askFiles, "keys.json"), KeystoreSealer()),
            ListingsStore(File(askFiles, "listings.json")))
    }

    /**
     * Bumped on the main thread at every change to the providers, their keys or their lists,
     * here or from the page on a phone: a page that reads it draws them again.
     */
    var providersChanged by mutableIntStateOf(0)
        private set

    private fun changed() {
        dropClients()
        redraw()
    }

    /** A page reading [providersChanged] drawn again; the clients kept, as one remembers what its model refused. */
    private fun redraw() {
        scope.launch { providersChanged++ }
    }

    /** Every provider added, Ollama first, as reached with the server where it is now (one on the Mac at its host). */
    fun providers(): List<Provider> = askProviders.all(server.host)

    /** The providers that can answer: a key kept, or none needed. */
    fun readyProviders(): List<Provider> = askProviders.ready(server.host)

    /** Add [id] (a preset's name, or one's own with its kind and address), or change it: null when kept, else why not. A new address drops its key. */
    fun saveProvider(id: String, spec: ProviderSpec = ProviderSpec()): String? = askProviders.save(id, spec).also { changed() }

    /** [id] gone, its key and its list with it; Ollama goes back to its preset. */
    fun removeProvider(id: String): String? = askProviders.remove(id).also { changed() }

    /** A key for [id], sealed in the keystore, the provider added if it was not: null when kept, else why not. */
    fun saveKey(id: String, key: String): String? = askProviders.saveKey(id, key).also { changed() }

    /** What [id] was added with (its address and name where they are not its preset's); null for one not added. */
    fun providerSpec(id: String): ProviderSpec? = askProviders.spec(id)

    /** What the server's machine is called, where a provider on it is: the server's name, else its address; null with no server. */
    fun providerPlace(): String? = server.name.ifBlank { server.host }.ifBlank { null }

    /** The kept key's last four characters, for a page; null when none is kept, or for a short one. */
    fun keyHint(id: String): String? = askProviders.hint(id)

    fun hasKey(id: String): Boolean = askProviders.hasKey(id)

    /** What [id] last listed, null before it has been asked. */
    fun listing(id: String): Listing? = askProviders.listing(id)

    /** Ask [id] for its models with its key (Test, a refresh) and keep the list: the list, or why not (the key never in it). */
    suspend fun listProvider(id: String): Result<Listing> = try {
        Result.success(askProviders.list(id, server.host))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }.also { redraw() }

    /**
     * [id] as [spec] with [key] (null: the one kept, while its address stays the same) asked for
     * its models before anything is kept (a key typed, a provider added): kept, the provider, the
     * key and the list, only if that works; else why not, the key never in it, and nothing kept.
     */
    suspend fun tryProvider(id: String, spec: ProviderSpec, key: String?): Result<Listing> = try {
        Result.success(askProviders.tryOut(id, spec, key, server.host))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }.also { changed() }

    // ---- the chat model a call runs on --------------------------------------------

    /** One client per model, kept, as an OpenAI-compatible one remembers what its model refused; dropped at every change to the providers. */
    private val chats = HashMap<String, ChatApi>()
    private val speeches = HashMap<String, MeteredSpeech>()

    private fun dropClients() {
        synchronized(chats) { chats.clear() }
        synchronized(speeches) { speeches.clear() }
    }

    /** What a call to a provider goes with: its key opened, null where it needs none. */
    private class Opened(val key: String?)

    /** [p]'s key for a call; null when it needs one and none is kept, or the keystore can no longer open it. */
    private fun opened(p: Provider): Opened? {
        if (!p.ready(hasKey(p.id))) return null
        return try {
            Opened(askProviders.key(p.id))
        } catch (e: IOException) {
            Log.w(TAG, e.message.orEmpty())
            null
        }
    }

    /**
     * The chat model a hold, a test or an app's `ask()` runs on ([id], `provider:model`; the
     * one in use by default): its client, metered into the ledger, with its single calls; or
     * why there is none: nothing picked or listed, or no key kept for the model's provider
     * (one the keystore can no longer open counts as none). No model asks the server and the
     * providers again, so the next call may have one.
     */
    fun holdChat(id: String? = chatModel): HoldChat {
        val entry = Providers.find(id, providers())
        val pid = id?.substringBefore(':', "").orEmpty()
        if (entry == null) return if (Providers.PRESETS[pid]?.needsKey == true) HoldChat.NoKey(pid) else noModel()
        val p = entry.provider
        if (p.url.isEmpty()) return noModel()
        val key = (opened(p) ?: return HoldChat.NoKey(p.id)).key
        val chat = synchronized(chats) {
            chats.getOrPut("${entry.id} ${p.url}") { Metered(Chat.of(p, entry.model, key) { Log.i(TAG, it) }, entry.id, ledger) }
        }
        return HoldChat.Ready(entry, chat, Calls(chat, entry.id, askCatalog.calls) { Log.i(TAG, it) })
    }

    private fun noModel(): HoldChat = HoldChat.NoModel.also { relist() }

    /** What a hold that cannot run says of it, as Ask does at the press. */
    private fun HoldChat.why(): String? = when (this) {
        is HoldChat.Ready -> null
        is HoldChat.NoKey -> "no key for $provider"
        HoldChat.NoModel -> VoiceState.NO_MODEL
    }

    // ---- the speech model a hold or a tape is heard by ------------------------------

    /**
     * What a hold's or a tape's words are heard by ([id], `provider:model`; the speech pick by
     * default): a cloud model of the C1's providers, heard from here, every pass metered into the
     * ledger; else the Mac's, over the server's socket, [id] its pick there (none: its default);
     * or why not: no key kept for the pick's provider.
     */
    fun holdSpeech(id: String? = modelPicks[ModelSlot.Stt]): HoldSpeech {
        val entry = Providers.find(id, providers())?.takeIf { it.provider.kind != Kind.Ollama } ?: return HoldSpeech.Mac(id)
        val p = entry.provider
        val key = (opened(p) ?: return HoldSpeech.NoKey(p.id)).key
        val speech = synchronized(speeches) {
            speeches.getOrPut("${entry.id} ${p.url}") { MeteredSpeech(Speech.of(p, entry.model, key) { Log.i(TAG, it) }, entry.id, ledger) }
        }
        return HoldSpeech.Cloud(entry, speech)
    }

    // ---- the picture model a picture is made by ------------------------------------

    /**
     * What a picture for [slot] is made by (its pick): a cloud model of the C1's providers, made
     * from here straight onto the shelf, counted; else the Mac's, by the server's `generate`
     * frame; or why not: no key kept for the pick's provider.
     */
    fun holdPictures(slot: ModelSlot): HoldPictures {
        val entry = Providers.find(modelPicks[slot], providers())?.takeIf { it.provider.kind != Kind.Ollama } ?: return HoldPictures.Mac
        val p = entry.provider
        val key = (opened(p) ?: return HoldPictures.NoKey(p.id)).key
        return HoldPictures.Cloud(entry, CloudPictures(p, entry.model, key) { Log.i(TAG, it) })
    }

    // ---- usage and cost ------------------------------------------------------------

    // The C1's own count of its model calls (every chat call is made here and counted) and the
    // prices it is costed at (ask/), fetched a day old by [refreshListings].

    /** One line per model call the C1 makes (usage.jsonl), 60 days of them; the service counts its pictures in the same one. */
    val ledger: Ledger by lazy { Stores.ledger(activity) }

    private val prices by lazy { Prices(File(askFiles, "prices.json")) { Log.i(TAG, it) } }

    /** What the C1's models cost, from models.dev's prices and its own count (ask/Costs.kt). */
    val costs: Costs by lazy { Costs(prices, ledger, ::providers, askProviders::listings) }

    /** models.dev's catalogue again when the kept copy is a day old or missing; offline, the kept copy. */
    suspend fun refreshPrices() = prices.refresh()

    // ---- Ask's prompt ------------------------------------------------------------

    /** Ask's built-in tools, the seed prompts, the fixed parts of a prompt and the model test's cases (data/AskCatalog.kt). */
    val askCatalog: AskCatalog by lazy { AskCatalog.load(activity) }

    /** The chat model a hold runs on: the pick, else the server's default while the C1's providers list it; null with neither. */
    val chatModel: String? get() = modelPicks[ModelSlot.Chat] ?: chatDefault()

    private val promptsStore by lazy {
        PromptsStore(File(activity.createDeviceProtectedStorageContext().filesDir, "prompts.json"), askCatalog)
    }

    /** Every variant of Ask's prompt and the pick per chat model (data/Prompts.kt), as Compose state like [modelPicks]. */
    var prompts by mutableStateOf(Prompts(emptyList(), emptyMap()))
        private set

    private fun <T> editPrompts(edit: PromptsStore.() -> T): T = promptsStore.edit().also { prompts = promptsStore.prompts }

    /** The variant a hold on [entry] gets: its pick, else the one seeded from its suggestion, else "default". */
    fun promptFor(entry: String? = chatModel): String = prompts.resolve(entry, askCatalog.suggested(entry))

    /** What [entry] gets without a pick of its own. */
    fun promptByDefault(entry: String?): String = prompts.byDefault(askCatalog.suggested(entry))

    fun promptPicked(entry: String): Boolean = entry in prompts.picks

    /** Give [entry] the variant [name] from its next hold; null takes it back to what it gets by default. */
    fun pickPrompt(entry: String, name: String?) = editPrompts { pick(entry, name) }

    /** A new variant, empty or a copy of [from]'s text: null when made, else why not. */
    fun addPrompt(name: String, from: String? = null): String? = editPrompts { add(name, from) }

    /** Its test scores follow the new name. */
    fun renamePrompt(name: String, to: String): String? =
        editPrompts { rename(name, to) }.also { if (it == null) editScores { rename(name, to.trim()) } }

    fun savePrompt(name: String, text: String) = editPrompts { save(name, text) }

    /** Refused for "default". Its test scores go with it. */
    fun deletePrompt(name: String): String? = editPrompts { delete(name) }.also { if (it == null) editScores { drop(name) } }

    /** A seed variant back to the catalog's text. */
    fun resetPrompt(name: String) = editPrompts { reset(name) }

    /** The whole system prompt as the next hold on [entry] would send it with [name], the clock now. */
    fun promptPreview(entry: String? = chatModel, name: String = promptFor(entry), photo: Boolean = false): String =
        prompt(photo, OffsetDateTime.now(), name, entry).system

    /** What the prompt lists, in order: the built-in tools on offer, then the enabled apps' ([ToolSpec.app] their app). */
    fun askTools(): List<ToolSpec> = AskPrompt.offered(askCatalog, serverTools, appTools())

    /**
     * The server's own Ask tools, from its last list; null before one, when a hold offers them
     * all. None with no server set: a C1 on cloud models alone offers no web search.
     */
    val serverTools: Set<String>? get() = if (!server.set) emptySet() else models?.tools

    /**
     * A hold's whole prompt and its tools, for the service: the chat model in use, [name] its
     * variant, the clock at [now], from the server's last list. Every hold asks for the list
     * again in the background, so a server restarted with another default model or other
     * tools reaches the next hold, and not only a Settings page.
     */
    fun holdPrompt(photo: Boolean, now: OffsetDateTime, name: String = promptFor()): HoldPrompt {
        scope.launch { refreshModels() }
        return prompt(photo, now, name, chatModel)
    }

    private fun prompt(photo: Boolean, now: OffsetDateTime, name: String, entry: String?): HoldPrompt =
        AskPrompt.hold(askCatalog, name, prompts[name]?.text.orEmpty(), entry, now, photo, serverTools, appTools())

    /** The enabled apps' tools, by their names to the model, each with its app's name. */
    private fun appTools(): List<ToolSpec> = atelier.offeredTools().map { o ->
        ToolSpec(o.wire, o.tool.description, JSONObject(o.tool.parameters), o.app.manifest.name)
    }

    /** The model test (Settings > ask > test, ask/Test.kt), run off the main thread. */
    val bench by lazy { ModelTest(CoroutineScope(scope.coroutineContext + Dispatchers.Default)) }

    /**
     * The test's cases: the catalog's, a feature's together, then each enabled app's tools'
     * examples, a group per app, each expecting its tool.
     */
    fun benchCases(): List<BenchCase> = askCatalog.cases + atelier.offeredTools().flatMap { o ->
        o.tool.examples.map { BenchCase(it.say, o.wire, BenchGroup(o.app.manifest.name, o.app.id), args = it.args) }
    }

    /** The groups on offer, in the test's order, each with its cases: what the test can be asked to run. */
    fun benchGroups(): Map<BenchGroup, List<BenchCase>> = benchCases().groupBy { it.group }

    /**
     * Run the model test on the chat model [entry] with the variant [name], on the cases of
     * [groups] (null, or every group on offer: all of them), through Ask's loop with exactly
     * what a hold on that model has: its whole prompt and its tools. Only this run uses them:
     * no pick changes. The device's tools run on a throwaway copy of the agenda and the
     * timers, made now and gone at the next test; an app's tool answers "done" and opens
     * nothing; the server's are answered at once. A run of every case that ends with a score
     * is the last score for [entry] and [name] ([benchScore]); one on chosen groups, failed or
     * stopped is not.
     */
    fun startBench(entry: String? = chatModel, name: String = promptFor(entry), groups: Set<BenchGroup>? = null) {
        val p = prompt(false, OffsetDateTime.now(), name, entry)
        val all = benchCases()
        val (run, cases) = BenchRun.of(entry, name, all, groups)
        val chat = holdChat(entry)
        if (chat !is HoldChat.Ready) return bench.refuse(run, cases.size, chat.why().orEmpty())
        Log.i(TAG, "model test begun on ${chat.entry.id} (prompt ${p.tag}; ${cases.size} of ${all.size} cases)")
        bench.start(chat.chat, chat.entry, p, run, cases, scratchTools(), costs, log = { Log.i(TAG, it) }) { s ->
            if (run.groups == null) scope.launch { keepScore(run, s.passed, s.total) }
        }
    }

    private val scoresStore by lazy {
        BenchScoresStore(File(activity.createDeviceProtectedStorageContext().filesDir, "scores.json"))
    }

    /** The test's last score per chat model and variant (data/BenchScores.kt), as Compose state like [prompts]. */
    var benchScores by mutableStateOf<List<BenchScore>>(emptyList())
        private set

    private fun <T> editScores(edit: BenchScoresStore.() -> T): T = scoresStore.edit().also { benchScores = scoresStore.scores }

    /** The last run of every case on [entry] with the variant [name] that ended with a score; null when none has. */
    fun benchScore(entry: String?, name: String): BenchScore? = benchScores.firstOrNull { it.entry == entry && it.prompt == name }

    /**
     * Kept for a named model only (a run with no [BenchRun.entry] was on whatever the
     * default was), and a variant still there (one renamed or deleted while it ran keeps none).
     */
    private fun keepScore(run: BenchRun, passed: Int, total: Int) {
        val entry = run.entry ?: return
        if (prompts[run.prompt] == null) return
        editScores { save(BenchScore(entry, run.prompt, passed, total, System.currentTimeMillis())) }
    }

    /** The device's tools for a test: the agenda and timers copied aside, an app's tools answering "done". */
    private fun scratchTools(): (String, JSONObject) -> Any {
        val dir = File(activity.cacheDir, "bench").apply { deleteRecursively(); mkdirs() }
        val files = activity.createDeviceProtectedStorageContext().filesDir
        listOf("reminders.json", "events.json", "timers.json").forEach { n ->
            File(files, n).takeIf { it.isFile }?.copyTo(File(dir, n), overwrite = true)
        }
        val run = ToolExecutor(RemindersStore(File(dir, "reminders.json")), EventsStore(File(dir, "events.json")),
            TimersStore(File(dir, "timers.json")))
        val apps = atelier.offeredTools().map { it.wire }.toSet()
        return { name, args -> if (name in apps) "done" else run.run(name, args) }
    }

    /** The words while the button is held, as turned on or off by hand; null leaves it to the speech model. */
    var liveWordsSet by mutableStateOf<Boolean?>(null)
        private set

    /** Whether a hold shows the words while it is held: as set by hand, else the speech model's default (on locally). */
    val liveWords: Boolean get() = liveWordsSet ?: modelFor(ModelSlot.Stt)?.partials ?: true

    fun setLiveWords(on: Boolean?) {
        Prefs.setLiveWords(activity, on)
        liveWordsSet = on
    }

    /** What a hold in [slot] gets: the pick while it is still offered, else its default. */
    fun modelFor(slot: ModelSlot): ModelEntry? = modelList(slot).resolve(modelPicks[slot])

    /**
     * The last 30 days of use: every job as the C1 counted and priced its calls, with what the
     * server counted of its own while it can be reached (net/Usage.kt). Null until
     * [refreshUsage] has run.
     */
    var usage by mutableStateOf<Usage?>(null)
        private set

    /** Count again (Settings or the Usage page opening): the C1's own, with the server's when it answers. */
    suspend fun refreshUsage(): Result<Usage> = withContext(Dispatchers.IO) {
        runCatching { Usage.joined(runCatching { UsageClient.fetch(server) }.getOrNull(), costs.usage()) }
    }.also { r -> usage = r.getOrNull() ?: usage }

    // ---- the page on a phone -------------------------------------------------------

    // Providers, keys and long prompts edited on a phone (served/): the C1 serves the page
    // itself, on one address, only while the R1's "on a phone" page is open. Each change goes
    // through the calls above, on the main thread, as the R1's own pages make it.

    /** The page being served: the QR's URL, its way, whether a phone has used it and when, whether the other way is up; null while closed. */
    var phonePage by mutableStateOf<PhonePage?>(null)
        private set

    private var configServer: ConfigServer? = null
    private var renewing: Job? = null
    /** Which opening is current: one closed while it was still starting does not keep its server. */
    private var phoneOpening = 0

    /**
     * Serve the page with a fresh code, on the tailnet when Tailscale is up, else Wi-Fi; on Wi-Fi
     * when [way] asks for it and it is up (what is typed then crosses it unencrypted): null when
     * it is served, else why not.
     */
    suspend fun openPhonePage(way: Way? = null): String? {
        closePhonePage()
        val opening = phoneOpening
        val s = ConfigServer(phoneConfig, phoneWays, clock = SystemClock::elapsedRealtime, log = { Log.w(TAG, "phone page: $it") },
            onUsed = { scope.launch { phonePage = phonePage?.copy(used = true, usedAt = System.currentTimeMillis()) } },
            assets = { name -> activity.assets.open("served/$name").use { it.readBytes() } })
        val why = try {
            withContext(Dispatchers.IO) { tailscale.refresh(); s.start(way) }
        } catch (e: CancellationException) {
            s.stop(); throw e
        }
        if (why != null) return why
        if (opening != phoneOpening) { s.stop(); return "the page was closed" }
        configServer = s
        phonePage = PhonePage(s.url.orEmpty(), s.way ?: Way.Wifi, used = false, choice = s.choice)
        // A code nobody scans in time is replaced, and the QR redraws from [phonePage].
        renewing = scope.launch {
            while (true) {
                delay(1_000)
                s.renew()?.let { url -> phonePage = phonePage?.copy(url = url) }
            }
        }
        return null
    }

    /** The R1's page left: the port closes and the code, and any phone's session, with it. */
    fun closePhonePage() {
        phoneOpening++
        renewing?.cancel()
        renewing = null
        configServer?.stop()
        configServer = null
        phonePage = null
    }

    private val phoneWays = object : Ways {
        override fun tailnet(): String? = tailscale.state.value.takeIf { it.running }?.ip
        override fun wifi(): String? = Addresses.lan()
    }

    /** A worker's request, run where the R1's own pages run theirs. */
    private fun <T> onMain(f: () -> T): T = runBlocking(Dispatchers.Main.immediate) { f() }

    private val phoneConfig = object : Config {
        override fun providers() = onMain { this@Core.providers() }
        override fun spec(id: String) = askProviders.spec(id)
        override fun hint(id: String) = keyHint(id)
        override fun hasKey(id: String) = this@Core.hasKey(id)
        override fun listing(id: String) = this@Core.listing(id)
        override fun saveProvider(id: String, spec: ProviderSpec) = onMain { this@Core.saveProvider(id, spec) }
        override fun removeProvider(id: String) = onMain { this@Core.removeProvider(id) }
        override fun saveKey(id: String, key: String) = onMain { this@Core.saveKey(id, key) }
        override fun key(id: String) = askProviders.key(id)
        override suspend fun list(id: String) = listProvider(id)
        override suspend fun tryProvider(id: String, spec: ProviderSpec, key: String?) = this@Core.tryProvider(id, spec, key)
        override fun place() = onMain { providerPlace() }
        override val prompts: Prompts get() = this@Core.prompts
        override fun addPrompt(name: String, from: String?) = onMain { this@Core.addPrompt(name, from) }
        override fun renamePrompt(name: String, to: String) = onMain { this@Core.renamePrompt(name, to) }
        override fun savePrompt(name: String, text: String) = onMain { this@Core.savePrompt(name, text) }
        override fun deletePrompt(name: String) = onMain { this@Core.deletePrompt(name) }
        override fun resetPrompt(name: String) = onMain { this@Core.resetPrompt(name) }
        override fun pickPrompt(entry: String, name: String?) = onMain { this@Core.pickPrompt(entry, name) }
        override val modelPicks: Map<ModelSlot, String> get() = this@Core.modelPicks
        override fun pickModel(slot: ModelSlot, id: String?) = onMain { this@Core.pickModel(slot, id) }
    }

    // ---- Agents ------------------------------------------------------------------

    /** Every computer running acp-bridge that the R1 has paired with, in the order paired. */
    var acpBridges by mutableStateOf(emptyList<Prefs.AcpBridge>())
        private set

    /** The one the Agents page drives, one at a time (2026-10-21); unset until one is paired. */
    var acpBridge by mutableStateOf(Prefs.AcpBridge())
        private set

    /** Its agents and their sessions, asked for while the Agents page is up ([Agents.page]). */
    val agents by lazy { Agents(scope, bridge = { acpBridge }, named = ::nameAcpBridge) }

    /**
     * Pair with the bridge a QR named ([com.bleural.croissant.net.parseAcpLink]) and use it: one
     * paired before with the same token takes its new addresses and keeps its name.
     */
    fun saveAcpBridge(b: Prefs.AcpBridge) {
        val known = acpBridges.firstOrNull { it.token == b.token }
        val kept = b.copy(name = b.name.ifEmpty { known?.name.orEmpty() })
        putAcpBridges(if (known != null) acpBridges.map { if (it.token == b.token) kept else it } else acpBridges + kept)
        useAcpBridge(b.token)
    }

    /** Use the paired bridge with [token]: the Agents page shows its agents and no other's. */
    fun useAcpBridge(token: String) {
        val b = acpBridges.firstOrNull { it.token == token } ?: return
        Prefs.setAcpActive(activity, token)
        val changed = b.token != acpBridge.token || b.hosts != acpBridge.hosts || b.port != acpBridge.port
        acpBridge = b
        if (changed) agents.relinked()
    }

    /** Forget the bridge with [token]; when it was in use, the first left is used, or none. */
    fun forgetAcpBridge(token: String) {
        putAcpBridges(acpBridges.filter { it.token != token })
        if (acpBridge.token != token) return
        val next = acpBridges.firstOrNull()
        if (next != null) useAcpBridge(next.token)
        else { Prefs.setAcpActive(activity, null); acpBridge = Prefs.AcpBridge(); agents.relinked() }
    }

    /** The bridge in use answered as [name]: kept, for the bridges page. */
    private fun nameAcpBridge(name: String) = onMain {
        if (name.isEmpty() || acpBridge.name == name) return@onMain
        val token = acpBridge.token
        putAcpBridges(acpBridges.map { if (it.token == token) it.copy(name = name) else it })
        acpBridge = acpBridge.copy(name = name)
    }

    private fun putAcpBridges(bridges: List<Prefs.AcpBridge>) {
        Prefs.setAcpBridges(activity, bridges)
        acpBridges = bridges
    }

    /**
     * A session's page is up (its [agent], [session] and folder), a new chat's (no [session]),
     * or none is: a hold there is its next prompt, or the new chat's first, as words alone.
     */
    fun agentChat(agent: String?, session: String?, cwd: String?) {
        agentWords = agent != null
        if (agent != null && session == null && cwd != null) agents.blank(agent, cwd) else agents.openChat(agent, session, cwd)
        service?.setDictate(atelierWords || agentWords)
    }

    /** A session's page takes a hold's words: read from the service's thread. */
    @Volatile private var agentWords = false

    // ---- Atelier ---------------------------------------------------------------

    /** Atelier's apps: installed, added from GitHub, and the one open on screen (atelier/Atelier.kt). */
    val atelier by lazy {
        val agent = "croissant/${BuildConfig.VERSION_NAME}"
        // An app's tool cannot take a built-in's name, nor tape_read, which tape_ask calls.
        val reserved = askCatalog.tools.map { it.name }.toSet() + "tape_read"
        Atelier(activity, scope, DeviceBridge(::appAsk, agent, ::playAppCue), agent, reserved).also { a ->
            scope.launch {
                a.wantsWords.collect {
                    atelierWords = it
                    service?.setDictate(it || agentWords)
                }
            }
        }
    }

    /** Apps' `ask()`s under way: the device does not sleep under one (VoiceService.working). */
    private val appAsks = AtomicInteger()

    /** An app's `ask(text)`: one call on the chat model in use, under the app rules (ask/Calls.kt). */
    private suspend fun appAsk(app: String, text: String): String {
        val chat = holdChat()
        if (chat !is HoldChat.Ready) throw BadApp(chat.why().orEmpty())
        appAsks.incrementAndGet()
        try {
            return chat.calls.appAsk(text, app)
        } finally {
            appAsks.decrementAndGet()
        }
    }

    /** The open app takes a hold's words (it has `onWords`): read from the service's thread. */
    @Volatile private var atelierWords = false

    /**
     * An Atelier app's page is on screen ([id]), or none is (null). Its app runs while its
     * page is up; a hold there brings it the words when it has `onWords`, else it is Ask, as
     * on every page.
     */
    fun atelierApp(id: String?) {
        if (atelier.open.value?.app?.id != id) atelier.open(id)
    }

    /** [id] deleted, and gone from the wheel's pins and recents with it. */
    fun deleteAtelierApp(id: String) {
        atelier.delete(id)
        val (r, p) = WheelKeep.dropApp(_recents.value, _pins.value, id)
        saveRecents(r)
        savePins(p)
    }

    // ---- the side wheel's recents and pins ----------------------------------------------

    private val _recents = MutableStateFlow(emptyList<String>())
    /**
     * The places last opened, newest first, as a design keys them ([WheelKeep]; Beam's, its
     * places' keys), kept across a restart: Beam shows three above Ask.
     */
    val recents: StateFlow<List<String>> = _recents

    private val _pins = MutableStateFlow(emptyList<String>())
    /** The Atelier apps pinned to the side wheel, by id, in the order pinned. */
    val pins: StateFlow<List<String>> = _pins

    /** A place was opened: first among the recents. */
    fun opened(key: String) = saveRecents(WheelKeep.opened(_recents.value, key))

    /** Atelier app [id] pinned to the wheel, last, or unpinned. */
    fun pin(id: String, on: Boolean) = savePins(WheelKeep.pinned(_pins.value, id, on))

    private val _agentPins = MutableStateFlow(emptyList<String>())
    /** The agents pinned to the side wheel, by the bridge's id, in the order pinned. */
    val agentPins: StateFlow<List<String>> = _agentPins

    /** Agent [id] pinned to the wheel, last, or unpinned. */
    fun pinAgent(id: String, on: Boolean) {
        val ids = WheelKeep.pinned(_agentPins.value, id, on)
        if (ids == _agentPins.value) return
        _agentPins.value = ids
        Prefs.setWheelAgents(activity, ids)
    }

    private fun saveRecents(keys: List<String>) {
        if (keys == _recents.value) return
        _recents.value = keys
        Prefs.setWheelRecents(activity, keys)
    }

    private fun savePins(ids: List<String>) {
        if (ids == _pins.value) return
        _pins.value = ids
        Prefs.setWheelPins(activity, ids)
    }

    /** An app's `cue(name)`: a few of the device's own sounds; an unknown name plays nothing. */
    private fun playAppCue(name: String) {
        val sound = when (name) {
            "tap" -> Sound.Commit
            "on" -> Sound.SwitchOn
            "off" -> Sound.SwitchOff
            "done" -> Sound.ReminderDone
            "undone" -> Sound.ReminderUndone
            "arrived" -> Sound.AnswerArrived
            "error" -> Sound.ExchangeFailed
            "delete" -> Sound.Destroy
            else -> return
        }
        Cues.play(sound)
    }

    // ---- the translator ----------------------------------------------------

    /** The languages a hold on the Translator goes from and to; swapped or picked on its screen. */
    var translatorPair by mutableStateOf(LanguagePair.DEFAULT)
        private set

    private var translatorOpen = false

    /**
     * The Translator is on screen, or gone. While it is, a hold is translated in
     * [translatorPair] and kept in [translations], and Ask does not come up.
     */
    fun translator(on: Boolean) {
        translatorOpen = on
        service?.setTranslate(if (on) translatorPair else null)
    }

    fun saveTranslatorPair(p: LanguagePair) {
        if (p.from == p.to || p == translatorPair) return
        translatorPair = p
        Prefs.setTranslatorPair(activity, p)
        if (translatorOpen) service?.setTranslate(p)
    }

    fun swapTranslator() = saveTranslatorPair(translatorPair.swapped())

    /**
     * The Translator's local switch: on loads Parakeet and
     * Bergamot (about 17 s) and keeps them until off. Only ever by hand, and not
     * kept across a restart: a process that comes back starts with it off. A pair said in
     * a language the device cannot hear (Japanese) is turned round first.
     */
    fun setLocal(on: Boolean) {
        if (on && !localHears(translatorPair.from)) saveTranslatorPair(translatorPair.swapped())
        service?.setLocal(on, translatorPair)
    }

    /** Whether a hold in [l] can be heard in local mode; a design greys what cannot be. */
    fun localHears(l: Language): Boolean = OfflineVoice.hears(l)

    /** Whether the models are on the device at all (device/offline-models/push.sh). */
    fun localInstalled(): Boolean = service?.local?.installed() ?: false

    // ---- the side button ---------------------------------------------------

    /** True between a DOWN we have acted on and its UP. */
    private var pttPressed = false

    /** The press that silenced a timer is spent: its repeats and its UP must not talk or sleep. */
    private var pttSpent = false

    private val handler = Handler(Looper.getMainLooper())
    private val sleepSoon = Runnable { Kiosk.sleep(activity) }

    /** The activity is stopped, which every sleep does: the screen is off. */
    private var asleep = false

    /** The press in progress is the one that woke the device, heard from the kernel only. */
    private var pressWoke = false

    /**
     * The press that wakes the device never reaches [MainActivity] (the framework swallows
     * it and its repeats), so it is heard here, from the kernel: a hold from sleep asks like
     * any other hold (2026-09-29). Its UP is the kernel's too; any other press is
     * the framework's to deliver.
     */
    private val kernelButton = KernelButton(handler,
        onDown = { if (asleep) { pressWoke = true; pttDown() } },
        onUp = { if (pressWoke) pttUp() },
    )
    private var lastShortPress = 0L

    /** The side button going down: from [MainActivity] while awake, from [kernelButton] for the press that wakes. */
    fun pttDown() {
        // An install takes the whole press too, and nothing comes of it.
        if (updating) { pttSpent = true; return }
        // A ringing timer takes the whole press.
        if (service?.ringing?.value?.isNotEmpty() == true) {
            service?.dismissRing()
            pttSpent = true
            return
        }
        if (pttSpent || pttPressed) return
        pttPressed = true
        pressCaptured = _setup.value.open
        if (pressCaptured) setupDown() else service?.pttDown()
    }

    fun pttUp() {
        if (pttSpent) { pttSpent = false; pttPressed = false; pressWoke = false; return }
        pttPressed = false
        if (pressCaptured) setupUp() else service?.pttUp()
        pressWoke = false
    }

    /**
     * Short press while awake = sleep, but only [DOUBLE_PRESS_MS] after the release, so a
     * second press in that window can be a double press instead: the camera, opened from
     * anywhere and closed again if it is up. While the camera's live preview shows, a
     * short press is the shutter instead. The press that woke the device only wakes it.
     */
    private fun onShortPress() {
        if (pressWoke || updating) return
        // A press that shoots is neither a sleep nor the first half of a double press.
        if (ui.shutterPress()) {
            handler.removeCallbacks(sleepSoon)
            lastShortPress = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastShortPress < DOUBLE_PRESS_MS) {
            handler.removeCallbacks(sleepSoon)
            lastShortPress = 0L
            ui.toggleCamera()
            return
        }
        lastShortPress = now
        handler.postDelayed(sleepSoon, DOUBLE_PRESS_MS)
    }

    // ---- the first setup ---------------------------------------------------

    private val _setup = MutableStateFlow(SetupUi())
    /** Where the first setup is: a design draws it over everything while [SetupUi.open]. */
    val setup: StateFlow<SetupUi> = _setup

    /** Reopen the setup (Settings > Device > setup) at [at]. Nothing is wiped: each step starts from what is set now. */
    fun openSetup(at: Step = Step.Hello) = _setup.update { it.copy(open = true, step = at) }

    /**
     * Leave [SetupUi.step] for the next one; [skip] leaves it for Settings. Past the last,
     * the setup closes and counts as finished.
     */
    fun setupNext(skip: Boolean = false) {
        val s = _setup.value
        val skipped = if (skip) s.skipped + s.step else s.skipped - s.step
        val next = Step.entries.getOrNull(s.step.ordinal + 1)
        _setup.value = if (next == null) s.copy(open = false, finished = true, skipped = skipped, step = Step.Hello)
            else s.copy(step = next, skipped = skipped)
        Prefs.setSetup(activity, _setup.value.finished, skipped.joinToString(",") { it.name })
        if (!_setup.value.open) releaseButton()
    }

    /** One step back, or out of the setup from the first step once it has been finished before. */
    fun setupBack() {
        val s = _setup.value
        val prev = Step.entries.getOrNull(s.step.ordinal - 1)
        _setup.value = when {
            prev != null -> s.copy(step = prev)
            s.finished -> s.copy(open = false)
            else -> s
        }
        if (!_setup.value.open) releaseButton()
    }

    /** The Wi-Fi step, or Settings > Wi-Fi, read a network's QR code. */
    fun joinWifi(qr: WifiQr) {
        Links.touched()
        wifi.join(qr)
    }

    /** The servers on the tailnet, as the Server step lists them; [ServerFinder.search] asks again. */
    val servers by lazy { ServerFinder(tailscale) }

    /** A typed address: whether it answers as a croissant-server, and in how many ms. */
    suspend fun checkServer(host: String, port: Int): Long? = withContext(Dispatchers.IO) { ServerFinder.check(host, port) }

    /** The zone the device is in now, and whether Android is still choosing it (from the mobile network). */
    fun timeZone(): Zone = Zones.of(TimeZone.getDefault().id)

    /** Set the zone by hand; Android stops choosing it. */
    fun saveTimeZone(id: String): Boolean = Kiosk.setTimeZone(activity, id).also { if (it) TimeZone.setDefault(null) }

    // ---- the button during the setup -------------------------------------

    /**
     * While the setup is open the side button is its own: every press is only heard
     * ([gestures]) and does nothing else, no utterance, no camera, no sleep
     * (2026-09-29), so the Hello lesson can show each gesture done without doing it, and no
     * other step is left by a stray press.
     */
    private var pressCaptured = false
    private val _gestures = MutableSharedFlow<Gesture>(extraBufferCapacity = 8)
    val gestures: SharedFlow<Gesture> = _gestures
    private var setupHeld = false
    private val setupHold = Runnable { setupHeld = true; _gestures.tryEmit(Gesture.HoldStart) }
    private val setupPress = Runnable { lastShortPress = 0L; _gestures.tryEmit(Gesture.Press) }

    private fun releaseButton() {
        handler.removeCallbacks(setupHold); handler.removeCallbacks(setupPress); setupHeld = false
    }

    private fun setupDown() {
        setupHeld = false
        handler.postDelayed(setupHold, VoiceService.HOLD_THRESHOLD_MS)
    }

    private fun setupUp() {
        handler.removeCallbacks(setupHold)
        if (setupHeld) { setupHeld = false; _gestures.tryEmit(Gesture.HoldEnd); return }
        if (pressWoke) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShortPress < DOUBLE_PRESS_MS) {
            handler.removeCallbacks(setupPress)
            lastShortPress = 0L
            _gestures.tryEmit(Gesture.Twice)
            return
        }
        lastShortPress = now
        // A second press inside the window makes it the double press instead.
        handler.postDelayed(setupPress, DOUBLE_PRESS_MS)
    }

    // ---- the recorder ------------------------------------------------------

    fun elapsedMs(): Long = service?.recorder?.elapsedMs() ?: 0L

    fun levels(): FloatArray = service?.recorder?.levels() ?: FloatArray(Recorder.HISTORY)

    /** The four buttons; each is one tap that did something, so one [Sound.Commit]. */
    fun record() = recorderDid { it.start() }
    fun pauseRecording() = recorderDid { it.pause() }
    fun resumeRecording() = recorderDid { it.resume() }
    fun stopRecording() = recorderDid { it.stop() }

    private inline fun recorderDid(act: (Recorder) -> Unit) {
        val r = service?.recorder ?: return
        act(r)
        Cues.play(Sound.Commit)
    }

    fun transcribe(file: File) {
        service?.transcriber?.start(file)
    }

    /** A tape's name; blank gives it back its number. */
    fun renameRecording(file: File, name: String) = recordings.rename(file, name)

    fun deleteRecording(file: File) {
        service?.transcriber?.cancel(file)
        recordings.delete(file)
    }

    // ---- timers ------------------------------------------------------------

    fun dismissRing() {
        service?.dismissRing()
    }

    // ---- metronome and tuner -----------------------------------------------

    val metronome by lazy { Metronome(activity.applicationContext) }
    val tuner by lazy { Tuner(activity.applicationContext) }

    /** The tuner's page is up, or gone: the microphone's chunks go to it while it is. */
    fun tune(on: Boolean) {
        tuner.listen(on)
        service?.tap = if (on) tuner::feed else null
    }

    // ---- pictures ----------------------------------------------------------

    /**
     * The pictures being asked for a second time right now, so a page can say so and not
     * take the tap twice. Fetching needs no such set: a page awaits its own fetches.
     */
    var asking by mutableStateOf(emptySet<String>())
        private set

    suspend fun fetchGeneration(id: String) {
        service?.generations?.fetch(id)
    }

    /**
     * Asking for a failed picture again, of the picture model picked now (VoiceService). The
     * photo a transform worked from goes back with the prompt when it is still here, so a
     * retried transform is the same transform rather than the description of one; read on IO,
     * since it is a few hundred kB off the flash.
     */
    fun generateAgain(item: Generated) {
        if (item.id in asking) return
        scope.launch {
            asking = asking + item.id
            try {
                val photo = item.source?.let { name ->
                    withContext(Dispatchers.IO) {
                        photos.list().firstOrNull { it.name == name }
                            ?.let { f -> runCatching { f.readBytes() }.getOrNull() }
                    }
                }
                service?.pictureAgain(item, photo)
            } finally {
                asking = asking - item.id
            }
        }
    }

    /** Stop or delete: one being made here is dropped here, the server told only of its own. */
    fun forgetGeneration(id: String) {
        service?.forgetPicture(id)
    }

    // ---- power -------------------------------------------------------------

    // None of them while an update installs.

    fun sleep() { if (!updating) Kiosk.sleep(activity) }

    fun reboot() { if (!updating) Kiosk.reboot(activity) }

    fun powerOff() { if (!updating) Kiosk.powerOff(activity) }

    fun exitKiosk() {
        if (updating) return
        Kiosk.release(activity)
        isOwner = false
    }

    // ---- links -------------------------------------------------------------

    /**
     * A link carrying an eSIM activation code (`LPA:1$<SM-DP+>$<matching ID>`, what an
     * operator's QR code holds) opens Settings > eSIM > Add profile filled in. Also the
     * way to hand the R1 a code from the Mac: `device/esim/send-code.sh 'LPA:1$...'`,
     * which escapes the dollars so the device's own shell cannot eat them.
     */
    fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        if (!data.startsWith("LPA:", ignoreCase = true)) return
        // A finished download still on its page would hide the form the link fills.
        esim.dismissTask { it.kind == EsimTask.Kind.Add }
        // A link whose code cannot be read still opens the page, with the fields as they
        // were: a shell that ate the code's dollars must not look like nothing happening.
        ActivationCode.parse(data)?.let(esim::fillDraft)
        ui.openEsimAdd()
    }
}
