package com.bleural.croissant.ask

import com.bleural.croissant.net.ModelEntry
import com.bleural.croissant.net.ModelList
import com.bleural.croissant.net.ModelSlot
import com.bleural.croissant.net.Rates
import com.bleural.croissant.net.UsageSlot
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.bleural.croissant.net.Usage as UsageReport

/**
 * Prices ([Prices]) put to the device's use ([Ledger]), as the server's costs.py.
 *
 * For a model page, each model that may be picked with its rates, a card's unit (an hour of
 * speech, a picture) and its month at your pace: the slot's last 30 days of use, whatever
 * ran it, priced at that model's rates; none under a week of use. A slot's models come
 * cheapest first within each provider, deprecated ones left out. For Settings > usage, the
 * last 30 days: what each job took and cost, a total, a cost a day.
 *
 * A model costs nothing on Ollama (the server's machine) or with a key on its provider's
 * free tier (as its listing found). [providers] and [listed] are the C1's, read each time;
 * [clock] in ms since the epoch, days in [zone].
 */
class Costs(
    private val prices: Prices,
    private val ledger: Ledger,
    private val providers: () -> List<Provider>,
    private val listed: () -> Map<String, Listing>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /** Ollama runs on the server's machine: what the server called local. */
    private fun local(p: Provider) = p.kind == Kind.Ollama

    fun freeTier(provider: String): Boolean = listed()[provider]?.free == true

    fun costsNothing(e: Entry): Boolean = local(e.provider) || freeTier(e.provider.id)

    fun price(e: Entry): Price? = if (local(e.provider)) null else prices.get(e.provider, e.model)

    fun cost(e: Entry, counts: Counts): Double? {
        if (costsNothing(e)) return 0.0
        return price(e)?.cost(counts)
    }

    /** A month of [slot]'s use at the last 30 days' rate, the live words' passes left out; null under a week of use. */
    fun pace(slot: ModelSlot): Counts? {
        val first = ledger.first()
        val now = now()
        val days = if (first != null && first != 0L) (now - first) / Ledger.DAY else 0.0
        if (days < MIN_DAYS) return null
        var totals = Counts()
        for (r in ledger.since(now - WINDOW_DAYS * Ledger.DAY)) {
            if (r.slot == slot.key && r.counts.live == 0.0) totals += r.counts
        }
        return totals * (WINDOW_DAYS / minOf(WINDOW_DAYS.toDouble(), days))
    }

    /** Requests today per model on a free tier, from midnight where its provider's day starts. */
    fun today(): Map<String, Int> {
        val starts = listed().filterValues { it.free }.mapValues { (_, l) -> midnight(l.resets) }
        return ledger.since(now() - Ledger.DAY)
            .filter { r -> r.t >= (starts[r.model.substringBefore(':')] ?: Double.POSITIVE_INFINITY) }
            .groupingBy { it.model }.eachCount()
    }

    /**
     * What a model page is told for [slot]: [default] and every model of [entries] it may
     * pick, cheapest first, with what each costs.
     */
    fun list(slot: ModelSlot, entries: List<Entry>, default: Entry?): ModelList {
        val pace = pace(slot)
        val today = today()
        return ModelList(default?.id, order(slot, pace, entries, default).map { about(slot, it, pace, today) })
    }

    /**
     * What a card shows of a model: its name, rates per 1M tokens, unit price, month at your
     * [pace], whether it thinks, its size on the Mac, and on a free tier [today]'s requests.
     */
    fun about(slot: ModelSlot, e: Entry, pace: Counts?, today: Map<String, Int>): ModelEntry {
        val p = price(e)
        val listing = listed()[e.provider.id]
        val free = freeTier(e.provider.id)
        var thinks = p?.thinks
        if (listing != null && local(e.provider)) thinks = e.model in listing.thinking
        return ModelEntry(
            e.id, e.name, e.provider.title, local(e.provider), partials = false, free = free,
            title = p?.let { short(it.name, e.provider.title) },
            rates = p?.takeIf { it.input != null || it.output != null }?.let { Rates(it.input, it.output, it.cached) },
            unit = UNIT[slot]?.let { cost(e, it) },
            month = pace?.let { cost(e, it) },
            thinks = thinks,
            bytes = listing?.sizes?.get(e.model),
            today = if (free) today[e.id] ?: 0 else null,
            resets = if (free) listing?.resets else null,
            shares = free && listing?.shares == true,
        )
    }

    /**
     * [entries] for [slot] cheapest first within each provider (free, the smaller in memory
     * first; then priced; then unknown). Deprecated ones are left out, but for [default],
     * which a hold that picks nothing still gets.
     */
    fun order(slot: ModelSlot, pace: Counts?, entries: List<Entry>, default: Entry?): List<Entry> {
        val kept = entries.filter { e -> price(e)?.deprecated != true || (default != null && e.id == default.id) }
        val mix = pace ?: REFERENCE.getValue(slot)
        val groups = kept.map { it.provider.id }.distinct()
        return kept.sortedWith(compareBy<Entry>({ groups.indexOf(it.provider.id) }, { cost(it, mix) ?: Double.POSITIVE_INFINITY },
            { listed()[it.provider.id]?.sizes?.get(it.model) ?: 0L }))
    }

    /**
     * The last 30 days from midnight (Settings > usage): per job its counts, its cost and
     * whether all of it could be priced; the total; a cost a day, oldest first.
     */
    fun usage(): UsageReport {
        val z = zone()
        val first = Instant.ofEpochMilli(clock()).atZone(z).toLocalDate().minusDays(WINDOW_DAYS - 1L)
        val daily = DoubleArray(WINDOW_DAYS)
        val all = providers()
        val slots = LinkedHashMap<ModelSlot, Tally>()
        for (r in ledger.since(first.atStartOfDay(z).toEpochSecond().toDouble())) {
            val slot = ModelSlot.entries.firstOrNull { it.key == r.slot } ?: continue
            val e = Providers.find(r.model, all)
            val s = slots.getOrPut(slot) { Tally() }
            s.counts += r.counts
            s.free = s.free && e != null && costsNothing(e)
            val c = e?.let { cost(it, r.counts) }
            if (c == null) {
                s.priced = false
                continue
            }
            s.cost += c
            val day = ChronoUnit.DAYS.between(first, Instant.ofEpochSecond(r.t).atZone(z).toLocalDate())
            if (day in 0 until WINDOW_DAYS) daily[day.toInt()] += c
        }
        return UsageReport(WINDOW_DAYS, slots.values.sumOf { it.cost }, daily.toList(), slots.mapValues { (_, v) ->
            UsageSlot(v.cost, v.free, (v.counts.input + v.counts.out).toLong(), v.counts.secs.toLong(), v.counts.pictures.toInt(), v.priced)
        })
    }

    private class Tally(var counts: Counts = Counts(), var cost: Double = 0.0, var free: Boolean = true, var priced: Boolean = true)

    private fun now(): Double = clock() / 1000.0

    /** When today began in [resets] (an IANA name), or here (seconds since the epoch). */
    private fun midnight(resets: String?): Double {
        val z = resets?.takeIf { it.isNotEmpty() }?.let { ZoneId.of(it) } ?: zone()
        return Instant.ofEpochMilli(clock()).atZone(z).toLocalDate().atStartOfDay(z).toEpochSecond().toDouble()
    }

    companion object {
        const val WINDOW_DAYS = 30
        const val MIN_DAYS = 7

        // A card's unit for what is not priced per token: an hour of speech (about 12,000
        // words' tokens written), one picture.
        val UNIT: Map<ModelSlot, Counts> = mapOf(
            ModelSlot.Stt to Counts(secs = 3600.0, out = 12000.0),
            ModelSlot.TextToImage to Counts(pictures = 1.0),
            ModelSlot.ImageToImage to Counts(pictures = 1.0),
        )

        /** What a slot is sorted by before there is any use of it. */
        val REFERENCE: Map<ModelSlot, Counts> = mapOf(ModelSlot.Chat to Counts(input = 1_000_000.0, out = 50_000.0)) + UNIT

        /** The name without its maker when the provider's says it ("Gemini 2.5 Flash" under Google Gemini). */
        fun short(name: String, provider: String): String {
            val first = name.substringBefore(' ')
            val rest = name.substringAfter(' ', "")
            return if (rest.isNotEmpty() && first.lowercase() in provider.lowercase().split(Regex("\\s+"))) rest else name
        }
    }
}
