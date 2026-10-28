package com.bleural.croissant.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import android.util.Log
import com.bleural.croissant.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The weather for every place: here, where the device is, and the
 * places the user added; Open-Meteo, keyless, all of them in one request. [forecasts] starts
 * from what was kept in prefs, so Home has weather straight after a restart. The places, the
 * pin and the forecasts are kept in prefs; the search for a place to add is [search].
 *
 * It runs only while the device is awake ([start] and [stop] follow the activity): on every
 * wake it fetches if the oldest forecast is [EVERY_MS] old, then again every [EVERY_MS], never
 * sooner than [FLOOR_MS] after the last try. A failed fetch keeps what there was. A place just
 * added is fetched at once, on its own.
 *
 * Location, without GMS: the newest last-known fix of any provider; when the one kept is
 * older than [FIX_STALE_MS] (or there is none), one single request (the network provider if
 * the device has one, else GPS) for at most [FIX_WAIT_MS], no more than every [FLOOR_MS].
 * Never held. The R1 has only GPS.
 */
class WeatherSource(private val ctx: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "Weather"
        const val EVERY_MS = 30 * 60_000L
        const val FLOOR_MS = 10 * 60_000L
        /** A fix this old is asked for again; the weather needs about a kilometre, and the R1 mostly sits on a desk. */
        const val FIX_STALE_MS = 3 * 3600_000L
        /** A cold GPS indoors can take minutes; this is the most one request is left open. */
        const val FIX_WAIT_MS = 3 * 60_000L
        /** A new fix this far from the one the forecast is for fetches again at once. */
        const val MOVED_M = 2000f
        /** How long the search waits for the typing to pause. */
        const val SEARCH_WAIT_MS = 300L
        private const val URL = "https://api.open-meteo.com/v1/forecast"
        private const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"
    }

    /** Where here's forecast is for, and when that fix was taken (wall-clock ms). */
    private data class Fix(val lat: Double, val lon: Double, val at: Long) {
        override fun toString() = "%.4f,%.4f,%d".format(Locale.ROOT, lat, lon, at)
        /** Two decimals, about a kilometre: what is sent and what is logged. */
        val short get() = "%.2f,%.2f".format(Locale.ROOT, lat, lon)
        companion object {
            fun parse(s: String?): Fix? = s?.split(',')?.takeIf { it.size == 3 }?.let {
                val lat = it[0].toDoubleOrNull() ?: return null
                val lon = it[1].toDoubleOrNull() ?: return null
                Fix(lat, lon, it[2].toLongOrNull() ?: 0L)
            }
            fun of(l: Location) = Fix(l.latitude, l.longitude, l.time)
        }
    }

    /** The kept answers, by place id: each place's forecast object as Open-Meteo sent it, and when it came. */
    private val kept: JSONObject = runCatching { JSONObject(Prefs.forecasts(ctx) ?: "{}") }.getOrDefault(JSONObject())

    private var fix: Fix? = Fix.parse(Prefs.weatherPlace(ctx))

    private val _forecasts = MutableStateFlow(
        Forecasts(
            places = listOf(here()) + Place.fromJson(Prefs.weatherPlaces(ctx)),
            weather = emptyMap(),
            pinned = Prefs.weatherPin(ctx),
            fixAt = fix?.at ?: 0L,
        ).let { f -> f.copy(weather = f.places.mapNotNull { p -> keptWeather(p.id)?.let { p.id to it } }.toMap()) },
    )
    /** Every place and its forecast, the pin, and when the last fix was (the fallback rule is [Forecasts.home]). */
    val forecasts: StateFlow<Forecasts> = _forecasts

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    /** What the last [search] found, newest query only. */
    val found: StateFlow<List<Found>> = _found

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var job: Job? = null
    private var lastTry = 0L
    private var fixJob: Job? = null
    private var lastFix = 0L
    private var searchJob: Job? = null
    /** One fetch at a time, so two answers never race to be kept. */
    private val fetching = Mutex()

    // ---- the places --------------------------------------------------------------------

    /** Adds [f] after the others and fetches its forecast at once; a place already there is left as it is. Returns its id. */
    fun add(f: Found): String {
        val p = f.toPlace()
        val now = _forecasts.value
        if (now.places.none { it.id == p.id }) {
            update(now.copy(places = now.places + p))
            scope.launch { fetch(listOf(p)) }
        }
        return p.id
    }

    /** Removes an added place (here stays), its pin and its forecast with it. */
    fun remove(id: String) {
        if (id == Place.HERE) return
        val now = _forecasts.value
        kept.remove(id)
        Prefs.setForecasts(ctx, kept.toString())
        update(now.copy(places = now.places.filterNot { it.id == id }, weather = now.weather - id, pinned = now.pinned.takeIf { it != id }))
    }

    /** Pins an added place to Home (unpinning any other), or unpins with null. Here cannot be pinned: it is Home's already. */
    fun pin(id: String?) {
        if (id == Place.HERE) return
        val now = _forecasts.value
        if (id != null && now.places.none { it.id == id }) return
        update(now.copy(pinned = id))
    }

    private fun update(f: Forecasts) {
        _forecasts.value = f
        Prefs.setWeatherPlaces(ctx, Place.toJson(f.places.drop(1)), f.pinned)
    }

    /**
     * Looks for places called [query], as the user types: it waits [SEARCH_WAIT_MS] for the
     * typing to pause, asks off the main thread, and only the last query's answer lands in
     * [found]. Under two letters finds nothing (the geocoder answers nothing to one).
     */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) { _found.value = emptyList(); return }
        searchJob = scope.launch {
            delay(SEARCH_WAIT_MS)
            val url = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", q).addQueryParameter("count", "6")
                .addQueryParameter("language", "en").addQueryParameter("format", "json").build()
            val body = withContext(Dispatchers.IO) { get(url.toString(), "search") } ?: return@launch
            _found.value = withContext(Dispatchers.Default) { Found.parse(body) }
        }
    }

    // ---- the loop ----------------------------------------------------------------------

    /** The device is awake: fetch if due, then keep to the half hour. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                refresh()
                val due = maxOf(oldest() + EVERY_MS, lastTry + FLOOR_MS)
                delay((due - System.currentTimeMillis()).coerceAtLeast(60_000L))
            }
        }
    }

    /** The device is going to sleep: nothing more is started (a fix already asked for runs out on its own). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** When the stalest forecast of a place that can have one came; 0 when one has none, or when no place can have one yet. */
    private fun oldest(): Long {
        val f = _forecasts.value
        return f.places.filter { !it.here || fix != null }.minOfOrNull { f.weather[it.id]?.fetchedAt ?: 0L } ?: 0L
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        lastKnown()?.takeIf { fix == null || it.at > fix!!.at }?.let(::keep)
        val was = fix
        if (now - oldest() >= EVERY_MS && now - lastTry >= FLOOR_MS) {
            lastTry = now
            scope.launch { fetch(_forecasts.value.places) }
        }
        if (was != null && now - was.at < FIX_STALE_MS) return
        if (fixJob?.isActive == true || now - lastFix < FLOOR_MS) return
        lastFix = now
        // Not the loop's own job: a glance at the screen is shorter than a GPS start, so the one
        // request is left to end by itself ([FIX_WAIT_MS]) rather than with the screen.
        fixJob = scope.launch {
            val got = locate() ?: return@launch
            keep(got)
            if (was == null || distance(was, got) > MOVED_M) {
                lastTry = System.currentTimeMillis()
                fetch(listOf(_forecasts.value.places.first()))
            }
        }
    }

    /** A new fix: kept, here moved to it, and named when the device can name it. */
    private fun keep(f: Fix) {
        val moved = fix?.let { distance(it, f) > MOVED_M } ?: true
        fix = f
        Prefs.setWeatherPlace(ctx, f.toString())
        val now = _forecasts.value
        _forecasts.value = now.copy(places = listOf(here()) + now.places.drop(1), fixAt = f.at)
        if (moved || Prefs.hereName(ctx) == null) name(f)
    }

    /** Here as a place: the last fix's coordinates, and its locality when there is one. */
    private fun here(): Place = Forecasts.here(Prefs.hereName(ctx).orEmpty(), fix?.lat ?: Double.NaN, fix?.lon ?: Double.NaN)

    /**
     * The locality of [f], from the platform's geocoder when the device has one (the R1 has
     * none: no GMS, no NLP, so here stays "here"). No web service is asked for this.
     */
    private fun name(f: Fix) {
        if (!Geocoder.isPresent()) return
        runCatching {
            Geocoder(ctx, Locale.UK).getFromLocation(f.lat, f.lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(found: MutableList<android.location.Address>) {
                    val town = found.firstOrNull()?.locality?.takeIf { it.isNotBlank() } ?: return
                    scope.launch {
                        Prefs.setHereName(ctx, town)
                        val now = _forecasts.value
                        _forecasts.value = now.copy(places = listOf(here()) + now.places.drop(1))
                    }
                }
                override fun onError(message: String?) { Log.i(TAG, "no locality: $message") }
            })
        }
    }

    /** The newest fix any provider still has, or null. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(): Fix? = runCatching {
        lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let(Fix::of)
    }.getOrNull()

    /** One fix, from the network provider if there is one, else GPS; null when none comes in [FIX_WAIT_MS]. */
    @SuppressLint("MissingPermission")
    private suspend fun locate(): Fix? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in lm.allProviders && lm.isProviderEnabled(it) }
        if (provider == null) { Log.i(TAG, "no location provider"); return null }
        val t0 = SystemClock.elapsedRealtime()
        var listener: LocationListener? = null
        val got = try {
            withTimeoutOrNull(FIX_WAIT_MS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    listener = LocationListener { l -> if (cont.isActive) cont.resume(l) }
                    val req = LocationRequest.Builder(1000L).setMaxUpdates(1).setDurationMillis(FIX_WAIT_MS).build()
                    try {
                        lm.requestLocationUpdates(provider, req, ctx.mainExecutor, listener!!)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "no location permission", e)
                        cont.resume(null)
                    }
                }
            }
        } finally {
            // Fixed, timed out or put to sleep: the request ends here.
            listener?.let { lm.removeUpdates(it) }
        }
        val s = (SystemClock.elapsedRealtime() - t0) / 1000
        if (got == null) { Log.i(TAG, "no fix from $provider in $s s"); return null }
        return Fix.of(got).also { Log.i(TAG, "fix from $provider in $s s: ${it.short}") }
    }

    private fun distance(a: Fix, b: Fix): Float {
        val out = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out)
        return out[0]
    }

    // ---- fetching ----------------------------------------------------------------------

    /**
     * Fetches the forecast for [places] in one request (here only once it has a fix); each
     * answer that reads replaces that place's forecast, is kept, and lands in [forecasts].
     */
    private suspend fun fetch(places: List<Place>) = fetching.withLock {
        val asked = places.filter { !it.lat.isNaN() && !it.lon.isNaN() }
        if (asked.isEmpty()) return@withLock
        val url = URL +
            "?latitude=" + asked.joinToString(",") { "%.2f".format(Locale.ROOT, it.lat) } +
            "&longitude=" + asked.joinToString(",") { "%.2f".format(Locale.ROOT, it.lon) } +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&timezone=auto&forecast_days=7&forecast_hours=48"
        val what = asked.joinToString(" ") { if (it.here) "here" else it.id }
        val at = System.currentTimeMillis()
        val body = withContext(Dispatchers.IO) { get(url, "fetch $what") } ?: return@withLock
        // One place is answered with an object, several with an array of them in the same order.
        val answers = withContext(Dispatchers.Default) {
            runCatching {
                if (body.trimStart().startsWith("[")) JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                else listOf(JSONObject(body))
            }.getOrDefault(emptyList())
        }
        val got = asked.zip(answers).mapNotNull { (p, o) -> Weather.parse(o, at)?.let { Triple(p.id, o, it) } }
        if (got.isEmpty()) { Log.w(TAG, "fetch $what failed: unreadable answer"); return@withLock }
        Log.i(TAG, "fetch $what ok: " + got.joinToString(" ") { "${it.third.temp}° code ${it.third.code}" })
        val now = _forecasts.value
        // A place removed while its answer was on the way is not brought back.
        val still = got.filter { (id) -> now.places.any { it.id == id } }
        still.forEach { (id, o) -> kept.put(id, JSONObject().put("at", at).put("f", o)) }
        _forecasts.value = now.copy(weather = now.weather + still.associate { it.first to it.third })
        val json = kept.toString()
        withContext(Dispatchers.IO) { Prefs.setForecasts(ctx, json) }
    }

    /** [url]'s body, or null (logged as [what]) when it does not come. */
    private fun get(url: String, what: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "$what failed: HTTP ${r.code}"); null }
        }
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: $e")
        null
    }

    /** A kept forecast, read again. */
    private fun keptWeather(id: String): Weather? {
        val o = kept.optJSONObject(id) ?: return null
        return Weather.parse(o.optJSONObject("f") ?: return null, o.optLong("at"))
    }
}
