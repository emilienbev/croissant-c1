package com.bleural.croissant.ui

import com.bleural.croissant.data.Language
import com.bleural.croissant.data.LanguagePair
import com.bleural.croissant.data.Translation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class TranslationDaysTest {
    private val now = OffsetDateTime.parse("2026-09-23T10:00:00+01:00")
    private val enFr = LanguagePair(Language.English, Language.French)
    private fun t(at: String) = Translation(at, enFr, "s", "h", at)

    @Test fun groupsNewestFirstByDayInTheDeviceZone() {
        val items = listOf(
            t("2026-09-23T09:00:00+01:00"),
            t("2026-09-23T00:30:00+01:00"),
            t("2026-09-22T23:30:00Z"),          // 00:30 on the 23rd here
            t("2026-09-22T12:00:00+01:00"),
            t("2026-09-15T12:00:00+01:00"),
        )
        val days = translationDays(items, now)
        assertEquals(listOf("Today", "Yesterday", "Tue 15 Sept"), days.map { it.label })
        assertEquals(listOf(3, 1, 1), days.map { it.items.size })
    }

    @Test fun aSideTakesAnyLanguageButTheOther() {
        assertEquals(Language.entries - Language.French, languagesFor(enFr, to = false))
        assertEquals(Language.entries - Language.English, languagesFor(enFr, to = true))
        assertEquals(LanguagePair(Language.German, Language.French), enFr.withSide(false, Language.German))
    }

    @Test fun showsTheLiveHoldElseTheNewestKept() {
        val kept = listOf(Translation("a", enFr.swapped(), "Bonjour", "Hello", "2026-09-23T09:00:00+01:00"))
        assertEquals(TranslatorShown(enFr.swapped(), "Bonjour", "Hello"), translatorShown(com.bleural.croissant.VoiceState(), kept))
        val live = com.bleural.croissant.VoiceState(transcript = "Two coffees", translate = enFr).withText(" Deux cafés ")
        assertEquals(TranslatorShown(enFr, "Two coffees", "Deux cafés"), translatorShown(live, kept))
        assertEquals(null, translatorShown(com.bleural.croissant.VoiceState(), emptyList()))
    }
}
