package com.bleural.croissant.ui

import androidx.compose.runtime.getValue
import com.bleural.croissant.VoiceState
import com.bleural.croissant.data.Language
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.Translation
import java.time.LocalDate
import java.time.OffsetDateTime

/** What the Translator shows: what was [said] in [pair]'s `from` and [heard] back in its `to`. */
data class TranslatorShown(val pair: LanguagePair, val said: String, val heard: String)

/**
 * Its own hold while there is one (live, or finished and not yet cleared), else the newest
 * kept for the pair ([kept], newest first), else nothing.
 */
fun translatorShown(voice: VoiceState, kept: List<Translation>): TranslatorShown? {
    voice.translate?.let { return TranslatorShown(it, voice.transcript, voice.answer.trim()) }
    return kept.firstOrNull()?.let { TranslatorShown(it.pair, it.said, it.heard) }
}

/** One day of the saved list: its label ("Today", "Yesterday", "Tue 22 Sept") and its translations, newest first. */
data class TranslationDay(val label: String, val items: List<Translation>)

/** [items] (newest first) by the day they were made, in the device's zone, labelled as the Reminders' days are. */
fun translationDays(items: List<Translation>, now: OffsetDateTime): List<TranslationDay> {
    val today = now.toLocalDate()
    val out = ArrayList<TranslationDay>()
    var day: LocalDate? = null
    var bucket = ArrayList<Translation>()
    for (t in items) {
        val d = runCatching { OffsetDateTime.parse(t.at).atZoneSameInstant(now.offset).toLocalDate() }.getOrDefault(LocalDate.MIN)
        if (d != day) {
            day = d
            bucket = ArrayList()
            out += TranslationDay(addedLabel(d, today), bucket)
        }
        bucket += t
    }
    return out
}

/** The languages a side can take: any but the one on the other side. */
fun languagesFor(pair: LanguagePair, to: Boolean): List<Language> = Language.entries.filter { it != if (to) pair.from else pair.to }

/** [pair] with one side changed to [l]. */
fun LanguagePair.withSide(to: Boolean, l: Language) = if (to) LanguagePair(from, l) else LanguagePair(l, this.to)
