package com.bleural.croissant.data

import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime

/**
 * The Translator's languages, by the code the server takes (`LANGUAGES` in
 * server/croissant_server/stt.py, which is also Whisper's). A limited set on purpose:
 * each is one Whisper, the LLM and the Mac all handle well.
 */
enum class Language(val code: String, val word: String) {
    English("en", "English"),
    French("fr", "French"),
    German("de", "German"),
    Italian("it", "Italian"),
    Spanish("es", "Spanish"),
    Japanese("ja", "Japanese");

    companion object {
        fun of(code: String?): Language? = entries.firstOrNull { it.code == code }
    }
}

/** What a hold on the Translator is said in and comes back in. Never the same language twice. */
data class LanguagePair(val from: Language, val to: Language) {
    fun swapped() = LanguagePair(to, from)

    /** Both languages, whichever way round: English to French and back are one conversation. */
    fun sameAs(other: LanguagePair) = setOf(from, to) == setOf(other.from, other.to)

    /** As Prefs keeps it: "en,fr". */
    override fun toString() = "${from.code},${to.code}"

    companion object {
        val DEFAULT = LanguagePair(Language.English, Language.French)

        fun parse(s: String?): LanguagePair {
            val (a, b) = s?.split(',')?.takeIf { it.size == 2 } ?: return DEFAULT
            val from = Language.of(a) ?: return DEFAULT
            val to = Language.of(b) ?: return DEFAULT
            return if (from == to) DEFAULT else LanguagePair(from, to)
        }
    }
}

/** One translation kept: what was [said] in [pair]'s `from`, what was [heard] back in its `to`, and when. */
data class Translation(
    val id: String,
    val pair: LanguagePair,
    val said: String,
    val heard: String,
    val at: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("from", pair.from.code)
        put("to", pair.to.code)
        put("said", said)
        put("heard", heard)
        put("at", at)
    }

    companion object {
        /** A language this build no longer has reads as English rather than failing the load, which would empty the file. */
        fun fromJson(o: JSONObject) = Translation(
            id = o.getString("id"),
            pair = LanguagePair(
                Language.of(o.optString("from")) ?: Language.English,
                Language.of(o.optString("to")) ?: Language.English,
            ),
            said = o.getString("said"),
            heard = o.getString("heard"),
            at = o.getString("at"),
        )
    }
}

/**
 * `translations.json`: every translation the Translator made, oldest first, the newest
 * [MAX] kept. Each pair's list is read off it by [forPair], newest first.
 */
class TranslationsStore(
    file: File,
    clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : JsonStore<Translation>(file, clock) {
    companion object {
        const val MAX = 2000
    }

    init { load() }

    override fun encode(item: Translation) = item.toJson()
    override fun decode(o: JSONObject) = Translation.fromJson(o)
    override fun keep(item: Translation, now: OffsetDateTime) = true

    @Synchronized
    fun add(pair: LanguagePair, said: String, heard: String): Translation {
        val t = Translation(newId("t"), pair, said.trim(), heard.trim(), Dates.stamp(clock()))
        commit((list() + t).takeLast(MAX))
        return t
    }

    @Synchronized
    fun delete(id: String) {
        commit(list().filter { it.id != id })
    }

    /** The pair's translations, either way round, newest first. */
    fun forPair(pair: LanguagePair): List<Translation> = list().filter { it.pair.sameAs(pair) }.asReversed()
}
