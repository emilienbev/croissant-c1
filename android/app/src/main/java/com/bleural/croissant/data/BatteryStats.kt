package com.bleural.croissant.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The maths behind Settings > Device > Battery, pure so it can be tested on the JVM. Times are epoch milliseconds; the
 * clock hours and days come from [ZoneId].
 */
object BatteryStats {
    /**
     * The raw log keeps a calendar day until it ended this many days ago, so the 24 h and
     * 7 d charts stay on raw samples; then the day is folded into a [Day] (2026-10-18).
     */
    const val RAW_DAYS = 7
    /** The days of a calendar month stay until it ended this many days ago, then fold into its [Month]. */
    const val DAY_DAYS = 90
    /** A discharge run counts towards the average once it is this long and this deep. */
    const val RUN_MIN_MS = 30 * 60_000L
    const val RUN_MIN_DROP = 5
    /** A gap this long between samples (the device was off) ends a run. */
    const val RUN_GAP_MS = 3 * 3_600_000L
    const val LOW = 15

    enum class Window(val label: String, val days: Int) { Day("24 h", 1), Week("7 d", 7), Month("30 d", 30) }

    /** One bar: null when the bucket has no sample. [plugged] only means anything by the hour. */
    data class Bar(val value: Int?, val plugged: Boolean = false)

    /**
     * What the chart draws: the bars, the labels as (bar index, text), whether the
     * bars are levels (by the hour) or percent used (by the day), the mean of the drawn
     * bars for the orange line, and the average life over the window in minutes, null
     * until a run qualifies.
     */
    data class Chart(val bars: List<Bar>, val labels: List<Pair<Int, String>>, val levels: Boolean, val mean: Int?, val lifeMinutes: Long?)

    /** A stretch on battery: from the first unplugged sample to the last before a charger, a rise or a gap. */
    data class Run(val start: Long, val end: Long, val from: Int, val to: Int) {
        val drop get() = from - to
        val ms get() = end - start
        val qualifies get() = ms >= RUN_MIN_MS && drop >= RUN_MIN_DROP
    }

    /**
     * The window's chart: levels by the hour over 24 h and over 7 d (168 bars, which the
     * page shows a day at a time and drags back through, 2026-10-18), percent used by
     * the day over 30 d.
     */
    fun chart(samples: List<BatterySample>, window: Window, now: Long, zone: ZoneId, days: List<Day> = emptyList()): Chart {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val levels = window != Window.Month
        val (bars, labels) = if (levels) hourly(samples, at, window.days * 24) else daily(samples, at, window.days, days)
        return Chart(bars, labels, levels, mean(bars), lifeMinutes(samples, now - window.days * 86_400_000L, days))
    }

    /** The mean of the bars that have a value, null when none has. */
    fun mean(bars: List<Bar>): Int? {
        val drawn = bars.mapNotNull { it.value }
        return if (drawn.isEmpty()) null else drawn.average().toInt()
    }

    /**
     * [hours] bars ending with the current clock hour: the last sample in each hour.
     * Labels at the clock hours 0, 6, 12, 18; over more than a day, midnight takes the
     * weekday instead, so a day dragged back to says which it is.
     */
    fun hourly(samples: List<BatterySample>, now: ZonedDateTime, hours: Int = 24): Pair<List<Bar>, List<Pair<Int, String>>> {
        val first = now.truncatedTo(ChronoUnit.HOURS).minusHours(hours - 1L)
        val bars = ArrayList<Bar>(hours); val labels = ArrayList<Pair<Int, String>>()
        var j = 0
        for (i in 0 until hours) {
            val h = first.plusHours(i.toLong())
            val a = h.toInstant().toEpochMilli(); val b = h.plusHours(1).toInstant().toEpochMilli()
            while (j < samples.size && samples[j].at < a) j++
            var last: BatterySample? = null
            while (j < samples.size && samples[j].at < b) last = samples[j++]
            bars += if (last == null) Bar(null) else Bar(last.pct, last.plugged)
            if (h.hour % 6 == 0) labels += i to if (h.hour == 0 && hours > 24) h.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) else h.hour.toString()
        }
        return bars to labels
    }

    /**
     * One bar per day ending today: the percent used on battery, the drops between
     * consecutive unplugged samples credited to the later sample's day; null for a day
     * with no sample at all. A day already folded out of the raw log reads its [Day]'s
     * percent used. Labels: the day of the month under every seventh bar.
     */
    fun daily(samples: List<BatterySample>, now: ZonedDateTime, days: Int, folded: List<Day> = emptyList()): Pair<List<Bar>, List<Pair<Int, String>>> {
        val today = now.toLocalDate()
        val firstDay = today.minusDays(days - 1L)
        val used = IntArray(days); val seen = BooleanArray(days)
        for (d in folded) {
            val i = ChronoUnit.DAYS.between(firstDay, d.date).toInt()
            if (i in 0 until days) { seen[i] = true; used[i] += d.totals.usedPct }
        }
        var prev: BatterySample? = null
        for (s in samples) {
            val d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(s.at), now.zone).toLocalDate()
            val i = ChronoUnit.DAYS.between(firstDay, d).toInt()
            if (i in 0 until days) {
                seen[i] = true
                val p = prev
                if (p != null && !p.plugged && !s.plugged && s.pct < p.pct && s.at - p.at < RUN_GAP_MS) used[i] += p.pct - s.pct
            }
            prev = s
        }
        val bars = (0 until days).map { if (seen[it]) Bar(used[it].coerceAtMost(100)) else Bar(null) }
        val labels = (0 until days).mapNotNull { i -> if (i % 7 == 0) i to firstDay.plusDays(i.toLong()).dayOfMonth.toString() else null }
        return bars to labels
    }

    /** The discharge runs in the log, in order. */
    fun runs(samples: List<BatterySample>): List<Run> {
        val out = ArrayList<Run>()
        var start: BatterySample? = null; var last: BatterySample? = null
        fun close() { val a = start; val b = last; if (a != null && b != null && b !== a) out += Run(a.at, b.at, a.pct, b.pct); start = null; last = null }
        for (s in samples) {
            val l = last
            val breaks = s.plugged || (l != null && (s.pct > l.pct || s.at - l.at >= RUN_GAP_MS))
            if (breaks) close()
            if (!s.plugged) { if (start == null) start = s; last = s }
        }
        close()
        return out
    }

    /** Percent moved over time spent in one state; it counts once it is [RUN_MIN_MS] long and [RUN_MIN_DROP] deep, like a run. */
    data class Rate(val pct: Int = 0, val ms: Long = 0) {
        val perHour get() = if (ms > 0) pct * 3_600_000.0 / ms else 0.0
        val qualifies get() = ms >= RUN_MIN_MS && pct >= RUN_MIN_DROP
        operator fun plus(o: Rate) = Rate(pct + o.pct, ms + o.ms)
    }

    /** The drain on battery with the screen on and off, and the charge on the charger below 100. */
    data class Rates(val screenOn: Rate, val screenOff: Rate, val charge: Rate)

    private enum class Kind { On, Off, Charge }

    /** One stretch between consecutive samples that counts towards a rate. */
    private class Stretch(val kind: Kind, val rate: Rate, val start: Long, val end: Long)

    /**
     * The stretches between consecutive samples, in order. A sample is taken at every
     * plug, unplug, screen on and screen off, so each stretch is in the state of the
     * sample that opens it: on battery with the screen on or off, or on the charger.
     * Left out: a gap of [RUN_GAP_MS] or more, the stretch that ends in a boot (and in
     * the service start just before it, which is what the device was off for), a level
     * that went the wrong way (the gauge correcting itself), and on the charger a
     * stretch opening at 100 or ending at 100, since the level sits at 100 for an
     * unknown part of it until the next tick.
     */
    private fun stretches(samples: List<BatterySample>): List<Stretch> {
        val out = ArrayList<Stretch>()
        for (i in 1 until samples.size) {
            val p = samples[i - 1]; val s = samples[i]
            if (s.at - p.at >= RUN_GAP_MS) continue
            val next = samples.getOrNull(i + 1)
            val reboot = s.why == "boot" || (s.why == "start" && next != null && next.why == "boot" && next.at - s.at < 5 * 60_000L)
            if (reboot) continue
            val kind = when {
                !p.plugged && s.pct <= p.pct -> if (p.screenOn) Kind.On else Kind.Off
                p.plugged && s.pct >= p.pct && s.pct < 100 -> Kind.Charge
                else -> continue
            }
            out += Stretch(kind, Rate(abs(s.pct - p.pct), s.at - p.at), p.at, s.at)
        }
        return out
    }

    /** The rates over the stretches that end after [since] (see [stretches] for what counts). */
    fun rates(samples: List<BatterySample>, since: Long = Long.MIN_VALUE): Rates {
        var on = Rate(); var off = Rate(); var charge = Rate()
        for (st in stretches(samples)) {
            if (st.end < since) continue
            when (st.kind) { Kind.On -> on += st.rate; Kind.Off -> off += st.rate; Kind.Charge -> charge += st.rate }
        }
        return Rates(on, off, charge)
    }

    /** A rate and the moment its oldest stretch began; [from] is before the window only when the window alone fell short. */
    data class Reached(val rate: Rate, val from: Long)
    data class Reach(val screenOn: Reached, val screenOff: Reached, val charge: Reached)

    /**
     * Each rate over the stretches that end after [since], and when that falls short of
     * [Rate.qualifies], the newest stretches before it added one by one until it does,
     * then the folded [days] and [months], newest first (2026-10-18). A day counts in the
     * window when its last sample is in it. Short of it with the whole log, the rate does
     * not qualify: the log is too young.
     */
    fun reach(samples: List<BatterySample>, since: Long, days: List<Day> = emptyList(), months: List<Month> = emptyList()): Reach {
        val all = stretches(samples)
        val older = days.sortedByDescending { it.date }.map { it.totals } + months.sortedByDescending { it.month }.map { it.totals }
        fun one(kind: Kind, of: (Totals) -> Rate): Reached {
            val mine = all.filter { it.kind == kind }
            var r = Rate(); var from = since
            var i = mine.lastIndex
            while (i >= 0 && mine[i].end >= since) r += mine[i--].rate
            var j = 0
            while (j < older.size && older[j].to >= since) r += of(older[j++])
            while (!r.qualifies && i >= 0) { r += mine[i].rate; from = minOf(from, mine[i].start); i-- }
            while (!r.qualifies && j < older.size) {
                val t = older[j++]; val tr = of(t)
                if (tr.ms > 0) { r += tr; from = minOf(from, t.from) }
            }
            return Reached(r, from)
        }
        return Reach(one(Kind.On) { it.screenOn }, one(Kind.Off) { it.screenOff }, one(Kind.Charge) { it.charge })
    }

    /** "36 h" under two days, "4 d" from there, each rounded up: how far back a rate reached. */
    fun fmtSpan(ms: Long): String {
        val hours = (ms + 3_599_999) / 3_600_000
        return if (hours < 48) "$hours h" else "${(ms + 86_399_999) / 86_400_000} d"
    }

    /** A rate's value on the page: "3.1 %/h", with "· 4 d" when it reached back before [since]; null when it does not qualify. */
    fun fmtReached(r: Reached, since: Long, now: Long): String? {
        if (!r.rate.qualifies) return null
        return if (r.from < since) "${fmtRate(r.rate)} · ${fmtSpan(now - r.from)}" else fmtRate(r.rate)
    }

    /** "3.2 %/h" under 10, "45 %/h" from there. */
    fun fmtRate(r: Rate): String {
        val v = r.perHour
        return if (v < 9.95) String.format(Locale.UK, "%.1f %%/h", v) else "${v.roundToInt()} %/h"
    }

    /**
     * Minutes a full charge lasts at the drain rate summed over the qualifying runs that
     * end after [since], with the runs of the folded [days] whose last sample is after it;
     * null with none.
     */
    fun lifeMinutes(samples: List<BatterySample>, since: Long = Long.MIN_VALUE, days: List<Day> = emptyList()): Long? {
        val runs = runs(samples).filter { it.qualifies && it.end >= since }
        val folded = days.filter { it.totals.to >= since }
        val drop = runs.sumOf { it.drop } + folded.sumOf { it.totals.runs.pct }
        val ms = runs.sumOf { it.ms } + folded.sumOf { it.totals.runs.ms }
        if (drop <= 0) return null
        return ms * 100 / drop / 60_000
    }

    /** Minutes since the current run began, or null when the last sample was plugged in or there is none. */
    fun onBatteryMinutes(samples: List<BatterySample>, now: Long): Long? {
        val last = samples.lastOrNull() ?: return null
        if (last.plugged) return null
        val run = runs(samples).lastOrNull() ?: return (now - last.at) / 60_000
        return (now - run.start) / 60_000
    }

    /**
     * Minutes the screen was on between [since] and [now], from the samples' screen state
     * held until the next sample, with the folded [days] whose last sample is after [since].
     */
    fun screenOnMinutes(samples: List<BatterySample>, since: Long, now: Long, days: List<Day> = emptyList()): Long =
        (screenOnMs(samples, since, now) + days.filter { it.totals.to >= since }.sumOf { it.totals.screenOnMs }) / 60_000

    private fun screenOnMs(samples: List<BatterySample>, since: Long, now: Long): Long {
        var ms = 0L
        for (i in samples.indices) {
            val s = samples[i]
            if (!s.screenOn) continue
            val end = if (i + 1 < samples.size) samples[i + 1].at else now
            val a = maxOf(s.at, since); val b = minOf(end, now)
            if (b > a) ms += b - a
        }
        return ms
    }

    /**
     * What a stretch of the log adds up to: the sample count and the span they cover, the
     * three rates, the qualifying discharge runs as percent and time (the average life is
     * 100 over their rate), the percent used on battery, and the screen-on time. Totals
     * rather than averages, so days add up to months.
     */
    data class Totals(
        val samples: Int = 0,
        val from: Long = 0,
        val to: Long = 0,
        val screenOn: Rate = Rate(),
        val screenOff: Rate = Rate(),
        val charge: Rate = Rate(),
        val runs: Rate = Rate(),
        val usedPct: Int = 0,
        val screenOnMs: Long = 0,
    ) {
        operator fun plus(o: Totals) = when {
            o.samples == 0 -> this
            samples == 0 -> o
            else -> Totals(
                samples + o.samples, minOf(from, o.from), maxOf(to, o.to), screenOn + o.screenOn, screenOff + o.screenOff,
                charge + o.charge, runs + o.runs, usedPct + o.usedPct, screenOnMs + o.screenOnMs,
            )
        }
    }

    /** A calendar day folded out of the raw log [RAW_DAYS] days after it ended. */
    data class Day(val date: LocalDate, val totals: Totals)

    /** A calendar month, the sum of its [Day]s, folded [DAY_DAYS] days after it ended. */
    data class Month(val month: YearMonth, val totals: Totals)

    /**
     * The day [date] out of [samples], the raw log in order from that day on. The rates,
     * the percent used and the screen-on time are the day's samples' own; a stretch across
     * midnight from a day already folded is not in it. A run counts when it qualifies as a
     * whole, the later days still raw included, and gives the day the stretches that end in it.
     */
    fun day(samples: List<BatterySample>, date: LocalDate, zone: ZoneId): Day {
        val a = date.atStartOfDay(zone).toInstant().toEpochMilli(); val b = dayEnd(date, zone)
        val inDay = samples.filter { it.at in a until b }
        val r = rates(inDay)
        var used = 0
        for (i in 1 until inDay.size) {
            val p = inDay[i - 1]; val s = inDay[i]
            if (!p.plugged && !s.plugged && s.pct < p.pct && s.at - p.at < RUN_GAP_MS) used += p.pct - s.pct
        }
        var runs = Rate()
        val qualifying = runs(samples).filter { it.qualifies && it.end >= a && it.start < b }
        for (i in 1 until samples.size) {
            val p = samples[i - 1]; val s = samples[i]
            if (s.at < a || s.at >= b) continue
            if (qualifying.any { p.at >= it.start && s.at <= it.end }) runs += Rate(p.pct - s.pct, s.at - p.at)
        }
        val from = inDay.firstOrNull()?.at ?: 0L; val to = inDay.lastOrNull()?.at ?: 0L
        return Day(date, Totals(inDay.size, from, to, r.screenOn, r.screenOff, r.charge, runs, used, screenOnMs(inDay, from, to)))
    }

    /** The month out of its [days]. */
    fun month(days: List<Day>, month: YearMonth): Month = Month(month, days.fold(Totals()) { acc, d -> acc + d.totals })

    /** The day holding [at], when it ends, and the moment it may leave the raw log: [RAW_DAYS] days after. */
    fun dateOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    fun dayEnd(date: LocalDate, zone: ZoneId): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayDue(at: Long, zone: ZoneId): Long = dayEnd(dateOf(at, zone), zone) + RAW_DAYS * 86_400_000L

    /** When [month] ends, and the moment its days may fold into it: [DAY_DAYS] days after. */
    fun monthEnd(month: YearMonth, zone: ZoneId): Long = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun monthDue(month: YearMonth, zone: ZoneId): Long = monthEnd(month, zone) + DAY_DAYS * 86_400_000L

    /** "16 h 40", "5 h 05", "40 min", "0 min". */
    fun fmt(minutes: Long): String {
        val h = minutes / 60; val m = minutes % 60
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
    }
}
