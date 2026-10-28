package com.bleural.croissant.net

import com.bleural.croissant.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the device used over the last 30 days: per job (chat, speech, pictures, transform)
 * what it took and cost at today's prices, the total, and a cost a day, oldest first, today
 * last. Every job as the C1 counted its calls (ask/Costs.kt) and the server its own
 * (`GET /usage`, chat there from before the C1 made every chat call), added up.
 */
data class Usage(val days: Int, val total: Double, val daily: List<Double>, val slots: Map<ModelSlot, UsageSlot>) {
    /** Every job's use was free. */
    val allFree: Boolean get() = slots.values.all { it.free }
    /** Every job's use had a price, so [total] is the whole cost. */
    val priced: Boolean get() = slots.values.all { it.priced }

    companion object {
        /**
         * [mine] with every job of [theirs] (the server's, null when it cannot be reached) added
         * in, and a day's costs too, today with today, so the days add up to the total.
         */
        fun joined(theirs: Usage?, mine: Usage): Usage {
            val slots = ModelSlot.entries.mapNotNull { slot ->
                val a = theirs?.slots?.get(slot)
                val b = mine.slots[slot]
                (if (a != null && b != null) a + b else a ?: b)?.let { slot to it }
            }.toMap()
            val a = theirs?.daily.orEmpty().asReversed()
            val b = mine.daily.asReversed()
            val daily = List(maxOf(a.size, b.size)) { a.getOrElse(it) { 0.0 } + b.getOrElse(it) { 0.0 } }.asReversed()
            return Usage(mine.days, slots.values.sumOf { it.cost }, daily, slots)
        }
    }
}

/**
 * One job's use: its cost, whether all of it was free, whether all of it had a price
 * ([priced] false when part ran on a paid model with no known price), and its counts
 * ([secs] the speech heard).
 */
data class UsageSlot(val cost: Double, val free: Boolean, val tokens: Long, val secs: Long, val pictures: Int,
                     val priced: Boolean = true) {
    /** One job's use in two places (the server's and the C1's), together. */
    operator fun plus(o: UsageSlot) = UsageSlot(cost + o.cost, free && o.free, tokens + o.tokens, secs + o.secs,
        pictures + o.pictures, priced && o.priced)
}

object UsageClient {
    /** The server's count, or an exception when it cannot be reached. Blocking: call off the main thread. */
    fun fetch(server: Prefs.Server): Usage {
        val c = URL("${server.httpUrl}/usage").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try {
            if (c.responseCode != 200) throw java.io.IOException("the server answered ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun parse(json: String): Usage {
        val o = JSONObject(json)
        val d = o.optJSONArray("daily")
        val s = o.optJSONObject("slots") ?: JSONObject()
        return Usage(o.optInt("days", 30), o.optDouble("total", 0.0),
            List(d?.length() ?: 0) { d!!.optDouble(it, 0.0) },
            ModelSlot.entries.mapNotNull { slot ->
                s.optJSONObject(slot.key)?.let {
                    slot to UsageSlot(it.optDouble("cost", 0.0), it.optBoolean("free"), it.optLong("tokens"),
                        it.optLong("secs"), it.optInt("pictures"), it.optBoolean("priced", true))
                }
            }.toMap())
    }
}
