package com.bleural.croissant.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Runs one of Ask's tool calls (assets/ask/catalog.json names and arguments) against the
 * device's stores. Returns the JSON value the model is answered with, or throws a
 * [ToolError] whose message goes back as the `error` string for the model to relay.
 *
 * The agenda is one family for reminders and events (2026-09-16): the model
 * never chooses between them, the date does. `agenda_add` with an `at` makes an event
 * (a day makes an all-day one, a time a one-hour one), without makes a reminder. Ids
 * tell the rest apart: `r_` is a reminder, `e_` an event. An `at` given to a reminder
 * turns it into an event.
 *
 * `tape_read` is never offered to the model: `tape_ask` (ask/Calls.kt) calls it for the
 * transcript of the tape the user named, by number or by words of its title, and asks
 * the question of it in a call of its own.
 */
class ToolExecutor(
    private val reminders: RemindersStore,
    private val events: EventsStore,
    private val timers: TimersStore,
    private val recordings: RecordingsStore? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    fun run(name: String, args: JSONObject): Any = when (name) {
        "agenda_list" -> {
            val today = clock().toLocalDate()
            JSONObject()
                .put("reminders", JSONArray().also { a -> reminders.list().forEach { a.put(it.toJson()) } })
                .put("events", JSONArray().also { a ->
                    events.between(args.str("from") ?: today.toString(), args.str("to") ?: today.plusDays(LIST_DAYS).toString()).forEach { a.put(it.toJson()) }
                })
        }
        "agenda_add" -> {
            val at = args.str("at")
            if (at == null) reminders.add(args.str("title")).toJson()
            else events.add(args.str("title"), at, args.str("end"), null, args.str("location"), args.str("notes")).toJson()
        }
        "agenda_update" -> {
            val id = args.str("id")
            val at = args.str("at")
            when {
                isEvent(id) -> {
                    if (args.bool("done") != null) throw ToolError("an event is not ticked off; delete it instead")
                    events.update(id, title = args.str("title"), start = at, end = args.str("end"), location = args.str("location"), notes = args.str("notes")).toJson()
                }
                at != null -> {
                    // A when given to a reminder: it becomes an event and the reminder goes.
                    val r = reminders.update(id, title = args.str("title"))
                    val e = events.add(r.title, at, args.str("end"), null, args.str("location"), args.str("notes"))
                    reminders.delete(r.id)
                    e.toJson()
                }
                else -> reminders.update(id, title = args.str("title"), done = args.bool("done")).toJson()
            }
        }
        "agenda_delete" -> {
            val id = args.str("id")
            if (isEvent(id)) events.delete(id) else reminders.delete(id)
            JSONObject().put("ok", true)
        }

        "timers_list" -> JSONArray().also { a -> val now = clock(); timers.running().forEach { a.put(it.toJson(now)) } }
        "timers_add" -> timers.add(args.int("seconds"), args.str("label")).toJson(clock())
        "timers_cancel" -> { timers.cancel(args.str("id")); JSONObject().put("ok", true) }
        "tape_read" -> tapeRead(args.str("tape") ?: throw ToolError("tape is required"))
        else -> throw ToolError("unknown tool $name")
    }

    /** The tape [asked] names, with its transcript; an error the model can relay or retry from. */
    private fun tapeRead(asked: String): JSONObject {
        val all = recordings?.list().orEmpty()
        val tape = findTape(all, asked)
        val meta = tape.meta
        if (meta?.transcribed != true) throw ToolError("${tape.title} has not been transcribed yet; it can be, from its page in the Recorder")
        return JSONObject()
            .put("number", tape.number).put("title", tape.title)
            .put("startedAt", meta.startedAt).put("durationMs", meta.durationMs)
            .put("segments", JSONArray().also { a ->
                meta.transcript!!.forEach { a.put(JSONObject().put("start", Math.round(it.start * 10) / 10.0).put("text", it.text)) }
            })
    }

    companion object {
        /** `agenda_list` without a range: today and the next two weeks of events. */
        const val LIST_DAYS = 14L

        private fun isEvent(id: String?) = id?.startsWith("e_") == true

        /** What the Ask screen shows for a call in flight: the agenda's word depends on the arguments. */
        fun label(name: String, args: JSONObject): String = when (name) {
            "agenda_list" -> "Reading agenda"
            "agenda_add" -> if (args.has("at") && !args.isNull("at")) "Adding event" else "Adding reminder"
            "agenda_update" -> if (isEvent(args.optString("id")) || (args.has("at") && !args.isNull("at"))) "Updating event" else "Updating reminder"
            "agenda_delete" -> if (isEvent(args.optString("id"))) "Deleting event" else "Deleting reminder"
            "timers_list" -> "Reading timers"
            "timers_add" -> "Starting timer"
            "timers_cancel" -> "Cancelling timer"
            "tape_read" -> "Reading tape"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

        /** The one argument worth showing on the tool line: a timer's length, an item's title. Null when there is none. */
        fun value(name: String, args: JSONObject): String? = when (name) {
            "timers_add" -> args.opt("seconds")?.toString()?.toDoubleOrNull()?.toLong()?.let { total ->
                val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
                if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
            }
            "agenda_add", "agenda_update" -> args.optString("title").takeIf { it.isNotBlank() }
            "tape_read" -> args.optString("tape").takeIf { it.isNotBlank() }
            else -> null
        }

        /** Words that name no tape in particular, left out when matching a title. */
        private val FILLER = setOf("tape", "the", "a", "an", "my", "of", "about", "recording", "number")

        /**
         * The one tape [asked] names: "21", "tape 21" or "#21" by number, else the tape whose
         * title shares the most words with it ("the compiler talk" finds "Thinking Like a
         * Compiler"). None, or a tie, is an error listing the tapes there are, so the model
         * can say which exist or ask again with one of them.
         */
        fun findTape(tapes: List<Recording>, asked: String): Recording {
            val q = asked.trim().lowercase()
            Regex("^(?:tape\\s*)?(?:number\\s*)?#?(\\d+)$").find(q)?.let { m ->
                val n = m.groupValues[1].toInt()
                return tapes.firstOrNull { it.number == n } ?: throw ToolError("there is no tape $n. ${shelf(tapes)}")
            }
            tapes.firstOrNull { it.title.lowercase() == q }?.let { return it }
            val words = words(q) - FILLER
            val scored = tapes.map { it to (words(it.title.lowercase()) intersect words).size }.filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second }
            val top = scored.filter { it.second == best }.map { it.first }
            return when (top.size) {
                1 -> top.single()
                0 -> throw ToolError("no tape is called \"$asked\". ${shelf(tapes)}")
                else -> throw ToolError("\"$asked\" could be ${top.joinToString(" or ") { name(it) }}")
            }
        }

        private fun words(s: String): Set<String> = s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

        private fun name(t: Recording) = if (t.meta?.name != null) "tape ${t.number}, \"${t.title}\"" else "tape ${t.number}"

        private fun shelf(tapes: List<Recording>) =
            if (tapes.isEmpty()) "There are no tapes." else "The tapes are: " + tapes.joinToString("; ") { name(it) } + "."
    }
}

/** Absent or JSON null both read as "not given"; a number or boolean is taken as its text. */
private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else get(key).toString()

/** A whole number, whether the model sent 120, 120.0 or "120"; anything else is an error for it. */
private fun JSONObject.int(key: String): Int? = when {
    !has(key) || isNull(key) -> null
    else -> when (val v = get(key)) {
        is Int -> v
        is Number -> v.toDouble().let { d -> if (d == Math.floor(d) && Math.abs(d) < Int.MAX_VALUE) d.toInt() else throw ToolError("$key must be a whole number") }
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.let { d -> if (d == Math.floor(d)) d.toInt() else null }
            ?: throw ToolError("$key must be a whole number, got \"$v\"")
        else -> throw ToolError("$key must be a whole number")
    }
}

private fun JSONObject.bool(key: String): Boolean? = when {
    !has(key) || isNull(key) -> null
    else -> when (val v = get(key)) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        is Number -> v.toInt() != 0
        else -> null
    }
}
