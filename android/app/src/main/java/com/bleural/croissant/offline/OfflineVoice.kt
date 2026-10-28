package com.bleural.croissant.offline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.LanguagePair
import io.github.yinvoker.foxlet.ExternalModel
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.ModelFiles
import io.github.yinvoker.foxlet.ModelRetention
import io.github.yinvoker.foxlet.Threading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import io.github.yinvoker.foxlet.LanguagePair as FoxPair

/** Where the Translator's local mode is: off, loading its engines, ready, or failed to load. */
sealed interface LocalState {
    data object Off : LocalState
    /** Since [at] (`SystemClock.elapsedRealtime`), so a loader drawn later still knows how far along it is. */
    data class Loading(val at: Long) : LocalState
    data object Ready : LocalState
    data class Failed(val message: String) : LocalState
}

/**
 * The Translator's local mode: Parakeet TDT 0.6B v3
 * through sherpa-onnx hears, Bergamot through Foxlet translates, both from the files
 * device/offline-models/push.sh put in `files/offline/`.
 *
 * [on] loads both (about 17 s, nearly all of it onnxruntime setting Parakeet's session
 * up) and they stay resident, ~0.9 GB, until [off]. Of the ten
 * Bergamot directions only the pair's own are kept loaded, one or two: [pair]
 * swaps them, about a second each. Everything that touches an engine takes [lock], so a
 * hold that arrives mid-load or mid-swap waits its turn instead of racing it.
 */
class OfflineVoice(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "offline")
    private val app = context.applicationContext

    private val _state = MutableStateFlow<LocalState>(LocalState.Off)
    val state: StateFlow<LocalState> = _state

    private val lock = Mutex()
    private var asr: OfflineRecognizer? = null
    private var foxlet: Foxlet? = null
    private var models: Map<String, ExternalModel> = emptyMap()
    /** The pair whose directions are resident. */
    private var warm: LanguagePair? = null
    /** Bumped by every [on] and [off]: a load that finishes after it has been overtaken lets go at once. */
    @Volatile private var generation = 0

    /** Whether push.sh has been run: the files [on] needs are all there. */
    fun installed(): Boolean =
        STT.all { File(dir, "stt/$it").isFile } && DIRECTIONS.all { File(dir, "mt/$it").isDirectory }

    /** Load both engines and [pair]'s directions; nothing if they are loaded or loading. */
    fun on(pair: LanguagePair) {
        if (_state.value is LocalState.Loading || _state.value == LocalState.Ready) return
        val gen = ++generation
        _state.value = LocalState.Loading(SystemClock.elapsedRealtime())
        scope.launch(Dispatchers.Default) {
            lock.withLock {
                if (gen != generation) return@withLock
                val t = SystemClock.elapsedRealtime()
                val failure = runCatching {
                    check(installed()) { "Models not installed" }
                    load()
                    swapTo(pair)
                }.exceptionOrNull()
                when {
                    gen != generation -> unload()
                    failure != null -> {
                        Log.e(TAG, "could not load", failure)
                        unload()
                        _state.value = LocalState.Failed(failure.message ?: failure.javaClass.simpleName)
                    }
                    else -> {
                        Log.i(TAG, "ready in ${SystemClock.elapsedRealtime() - t} ms")
                        _state.value = LocalState.Ready
                    }
                }
            }
        }
    }

    /** Unload both. A load in flight finishes its native part (it cannot be stopped) and then lets go. */
    fun off() {
        generation++
        _state.value = LocalState.Off
        scope.launch(Dispatchers.Default) { lock.withLock { unload() } }
    }

    /** The Translator's pair changed: keep its directions loaded instead of the last one's. */
    fun pair(pair: LanguagePair) {
        if (_state.value != LocalState.Ready) return
        scope.launch(Dispatchers.Default) {
            lock.withLock {
                if (foxlet != null) runCatching { swapTo(pair) }.onFailure { Log.w(TAG, "could not load $pair", it) }
            }
        }
    }

    /** What was said in [pcm] (16 kHz mono PCM16), "" for nothing. The caller gates silence first. */
    suspend fun hear(pcm: ShortArray): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val r = checkNotNull(asr) { "Offline mode is off" }
            val t = SystemClock.elapsedRealtime()
            val stream = r.createStream()
            try {
                stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, RATE)
                r.decode(stream)
                r.getResult(stream).text.trim().also {
                    Log.i(TAG, "heard ${pcm.size / (RATE / 1000)} ms in ${SystemClock.elapsedRealtime() - t} ms")
                }
            } finally {
                stream.release()
            }
        }
    }

    /** [text] carried from [pair]'s `from` to its `to`, through English when neither is English. */
    suspend fun translate(pair: LanguagePair, text: String): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val f = checkNotNull(foxlet) { "Offline mode is off" }
            if (warm != pair) swapTo(pair)
            val t = SystemClock.elapsedRealtime()
            val route = route(pair)
            val out = if (route.size == 1) f.translator.translate(text, route[0])
            else f.translator.translatePivot(text, route[0], route[1])
            Log.i(TAG, "translated $pair in ${SystemClock.elapsedRealtime() - t} ms")
            out.trim()
        }
    }

    // ---- under the lock -----------------------------------------------------

    private suspend fun load() {
        val stt = File(dir, "stt")
        val t = SystemClock.elapsedRealtime()
        asr = OfflineRecognizer(null, OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(stt, "enc.saved.onnx").path,
                    decoder = File(stt, "decoder.int8.onnx").path,
                    joiner = File(stt, "joiner.int8.onnx").path,
                ),
                tokens = File(stt, "tokens.txt").path,
                modelType = "nemo_transducer",
                // The four 2.2 GHz cores; eight is no faster.
                numThreads = 4,
            ),
        ))
        Log.i(TAG, "Parakeet loaded in ${SystemClock.elapsedRealtime() - t} ms")
        foxlet = Foxlet.create(app) {
            // Foxlet's own store, unused: every model here is the app's own files.
            models { directory = File(dir, "foxlet") }
            translation {
                threading = Threading.Fixed(1)
                retention = ModelRetention.UntilShutdown
            }
        }
        models = DIRECTIONS.associateWith { d ->
            ExternalModel(FoxPair(d.take(2), d.drop(2)), ModelFiles.fromDirectory(File(dir, "mt/$d")))
        }
    }

    /** Let go of every direction and load [pair]'s: Foxlet loads a model on its first sentence. */
    private suspend fun swapTo(pair: LanguagePair) {
        val f = foxlet ?: return
        if (warm == pair) return
        val t = SystemClock.elapsedRealtime()
        f.translator.unloadModels()
        warm = null
        for (m in route(pair)) f.translator.translate("Hello", m)
        warm = pair
        Log.i(TAG, "directions for $pair loaded in ${SystemClock.elapsedRealtime() - t} ms")
    }

    private suspend fun unload() {
        asr?.release()
        asr = null
        foxlet?.let { runCatching { it.shutdown() } }
        foxlet = null
        models = emptyMap()
        warm = null
    }

    private fun route(pair: LanguagePair): List<ExternalModel> {
        val (a, b) = pair.from.code to pair.to.code
        return if (a == "en" || b == "en") listOf(models.getValue("$a$b"))
        else listOf(models.getValue("${a}en"), models.getValue("en$b"))
    }

    companion object {
        /**
         * What a loader aims at: the 17.4 s measured in the app, rounded up to an
         * optimistic 18.
         */
        const val LOAD_TARGET_MS = 18_000L

        /** Whether [l] can be heard: Parakeet has no Japanese. */
        fun hears(l: Language) = l != Language.Japanese

        private const val TAG = "OfflineVoice"
        private const val RATE = 16000
        private val STT = listOf("enc.saved.onnx", "enc.saved.onnx.data", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        private val DIRECTIONS = Language.entries.filter { it != Language.English }
            .flatMap { listOf("en${it.code}", "${it.code}en") }
    }
}
