package com.bleural.croissant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One battery sample: when, the percent, whether a charger was in, whether the screen
 * was on, the gauge's millivolts and microamps, the temperature in tenths of a degree,
 * and why it was taken ("tick" for the interval, "plug", "unplug", "on", "off",
 * "boot", "start").
 */
data class BatterySample(
    val at: Long,
    val pct: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
    val mV: Int = 0,
    val uA: Int = 0,
    val tempC10: Int = 0,
    val why: String = "tick",
)

/**
 * The battery log, in three stages (2026-10-18): an append-only JSON Lines file, one
 * sample per line; beside it `battery-days.jsonl`, one [BatteryStats.Day] per line; and
 * `battery-months.jsonl`, one [BatteryStats.Month] per line. A calendar day stays raw
 * until it ended [BatteryStats.RAW_DAYS] days ago, so the 24 h and 7 d charts read raw
 * samples; then it is folded into its day and its samples leave the raw file, which so
 * holds 7 to 8 days at any interval. A month's days stay until it ended
 * [BatteryStats.DAY_DAYS] days ago, then add up to its month and leave the days file.
 * (Until 2026-10-18 a month stayed raw for 30 days and folded straight into its month;
 * those months are kept as they are.)
 *
 * Appending writes one line, and the check for a fold is one comparison against the
 * moment the next fold falls due. The fold runs on [background] (a minimum-priority
 * thread), about once a day: it writes the summary, writes what remains to a temporary
 * file outside the lock, then (for the raw file) under the lock adds whatever was appended
 * meanwhile and swaps the file in. A summary written before a crash is replaced by the
 * next fold of the same day or month. A line that does not parse is skipped, never fatal.
 */
class BatteryLog(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val background: (() -> Unit) -> Unit = { job -> Thread(job, "battery-fold").apply { priority = Thread.MIN_PRIORITY }.start() },
) {
    private val daysFile = File(file.parentFile, file.nameWithoutExtension + "-days.jsonl")
    private val monthsFile = File(file.parentFile, file.nameWithoutExtension + "-months.jsonl")
    private val _items = MutableStateFlow<List<BatterySample>>(emptyList())
    val items: StateFlow<List<BatterySample>> = _items
    private val _days = MutableStateFlow<List<BatteryStats.Day>>(emptyList())
    val days: StateFlow<List<BatteryStats.Day>> = _days
    private val _months = MutableStateFlow<List<BatteryStats.Month>>(emptyList())
    val months: StateFlow<List<BatteryStats.Month>> = _months

    /** When the next fold may run; never while both the raw log and the days are empty. */
    @Volatile private var due = Long.MAX_VALUE
    private val folding = AtomicBoolean(false)

    init { load() }

    fun list(): List<BatterySample> = _items.value

    fun append(s: BatterySample) {
        synchronized(this) {
            file.parentFile?.mkdirs()
            file.appendText(encode(s).toString() + "\n")
            if (_items.value.isEmpty()) due = minOf(due, BatteryStats.dayDue(s.at, zone()))
            _items.value = _items.value + s
        }
        foldIfDue()
    }

    private fun load() {
        synchronized(this) {
            _items.value = readLines(file) { decode(it) }.sortedBy { it.at }
        }
        _days.value = readLines(daysFile) { decodeDay(it) }.sortedBy { it.date }
        _months.value = readLines(monthsFile) { decodeMonth(it) }.sortedBy { it.month }
        due = nextDue()
        foldIfDue()
    }

    private fun nextDue(): Long {
        val z = zone()
        val raw = _items.value.firstOrNull()?.let { BatteryStats.dayDue(it.at, z) } ?: Long.MAX_VALUE
        val day = _days.value.firstOrNull()?.let { BatteryStats.monthDue(YearMonth.from(it.date), z) } ?: Long.MAX_VALUE
        return minOf(raw, day)
    }

    private fun foldIfDue() {
        if (clock() < due || !folding.compareAndSet(false, true)) return
        background {
            try { runCatching { fold() }.onFailure { due = clock() + 86_400_000L } } finally { folding.set(false) }
        }
    }

    /** Folds every day that is due, oldest first, then every month. */
    private fun fold() {
        while (true) {
            val snap = _items.value
            val first = snap.firstOrNull() ?: break
            val z = zone()
            if (clock() < BatteryStats.dayDue(first.at, z)) break
            val date = BatteryStats.dateOf(first.at, z)
            val end = BatteryStats.dayEnd(date, z)
            val inDay = snap.takeWhile { it.at < end }
            val summary = BatteryStats.day(snap, date, z)
            _days.value = keep(daysFile, _days.value, summary, { it.date }, ::encodeDay)

            val rest = snap.drop(inDay.size)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rest.joinToString("") { encode(it).toString() + "\n" })
            synchronized(this) {
                val added = _items.value.drop(snap.size)
                if (added.isNotEmpty()) tmp.appendText(added.joinToString("") { encode(it).toString() + "\n" })
                if (!tmp.renameTo(file)) throw ToolError("could not write ${file.name}")
                _items.value = rest + added
            }
        }
        while (true) {
            val days = _days.value
            val first = days.firstOrNull() ?: break
            val month = YearMonth.from(first.date)
            if (clock() < BatteryStats.monthDue(month, zone())) break
            val inMonth = days.takeWhile { YearMonth.from(it.date) == month }
            _months.value = keep(monthsFile, _months.value, BatteryStats.month(inMonth, month), { it.month }, ::encodeMonth)
            val rest = days.drop(inMonth.size)
            writeAtomically(daysFile, rest.joinToString("") { encodeDay(it).toString() + "\n" })
            _days.value = rest
        }
        due = nextDue()
    }

    /** [list] with [summary] in it, replacing one of the same key, written to [f]: one line appended when it is the newest. */
    private fun <T, K : Comparable<K>> keep(f: File, list: List<T>, summary: T, key: (T) -> K, enc: (T) -> JSONObject): List<T> {
        val out = (list.filter { key(it) != key(summary) } + summary).sortedBy(key)
        if (out.size == list.size + 1 && out.last() == summary) {
            f.parentFile?.mkdirs()
            f.appendText(enc(summary).toString() + "\n")
        } else writeAtomically(f, out.joinToString("") { enc(it).toString() + "\n" })
        return out
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        target.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(target)) throw ToolError("could not write ${target.name}")
    }

    private fun <T> readLines(f: File, parse: (JSONObject) -> T): List<T> = runCatching {
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { line -> runCatching { parse(JSONObject(line)) }.getOrNull() }
    }.getOrDefault(emptyList())

    companion object {
        fun encode(s: BatterySample): JSONObject = JSONObject()
            .put("at", s.at).put("pct", s.pct).put("plug", s.plugged).put("on", s.screenOn)
            .put("mv", s.mV).put("ua", s.uA).put("t", s.tempC10).put("why", s.why)

        fun decode(o: JSONObject) = BatterySample(
            at = o.getLong("at"), pct = o.getInt("pct"), plugged = o.getBoolean("plug"), screenOn = o.getBoolean("on"),
            mV = o.optInt("mv"), uA = o.optInt("ua"), tempC10 = o.optInt("t"), why = o.optString("why", "tick"),
        )

        private fun JSONObject.putRate(key: String, r: BatteryStats.Rate) = put(key + "_pct", r.pct).put(key + "_ms", r.ms)
        private fun JSONObject.rate(key: String) = BatteryStats.Rate(getInt(key + "_pct"), getLong(key + "_ms"))

        private fun JSONObject.putTotals(t: BatteryStats.Totals) = put("samples", t.samples).put("from", t.from).put("to", t.to)
            .putRate("on", t.screenOn).putRate("off", t.screenOff).putRate("charge", t.charge).putRate("runs", t.runs)
            .put("used", t.usedPct).put("screen_on_ms", t.screenOnMs)

        private fun JSONObject.totals() = BatteryStats.Totals(
            samples = getInt("samples"), from = getLong("from"), to = getLong("to"),
            screenOn = rate("on"), screenOff = rate("off"), charge = rate("charge"), runs = rate("runs"),
            usedPct = getInt("used"), screenOnMs = getLong("screen_on_ms"),
        )

        fun encodeDay(d: BatteryStats.Day): JSONObject = JSONObject().put("day", d.date.toString()).putTotals(d.totals)
        fun decodeDay(o: JSONObject) = BatteryStats.Day(LocalDate.parse(o.getString("day")), o.totals())

        fun encodeMonth(m: BatteryStats.Month): JSONObject = JSONObject().put("month", m.month.toString()).putTotals(m.totals)
        fun decodeMonth(o: JSONObject) = BatteryStats.Month(YearMonth.parse(o.getString("month")), o.totals())
    }
}
