package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

class TranslationsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-23T10:00:00+01:00")
    private fun file() = File(tmp.root, "translations.json")
    private fun store() = TranslationsStore(file()) { now }

    private val enFr = LanguagePair(Language.English, Language.French)
    private val frEn = enFr.swapped()
    private val enDe = LanguagePair(Language.English, Language.German)

    @Test fun addTrimsWritesAndReloads() {
        val t = store().add(enFr, " Two coffees, please. ", "Deux cafés, s’il vous plaît.\n")
        assertEquals("Two coffees, please.", t.said)
        assertEquals("Deux cafés, s’il vous plaît.", t.heard)
        assertEquals("2026-09-23T10:00:00+01:00", t.at)
        assertEquals(listOf(t), store().list())
    }

    @Test fun aPairIsBothWaysRoundNewestFirst() {
        val s = store()
        val a = s.add(enFr, "Where is the station?", "Où est la gare ?")
        s.add(enDe, "Hello", "Hallo")
        val b = s.add(frEn, "C’est à dix minutes.", "It’s ten minutes away.")
        assertEquals(listOf(b, a), s.forPair(enFr))
        assertEquals(listOf(b, a), s.forPair(frEn))
        assertEquals(1, s.forPair(enDe).size)
    }

    @Test fun deleteRemovesOne() {
        val s = store()
        val a = s.add(enFr, "a", "a")
        val b = s.add(enFr, "b", "b")
        s.delete(a.id)
        assertEquals(listOf(b), store().list())
    }

    @Test fun keepsTheNewestMax() {
        val s = store()
        repeat(TranslationsStore.MAX + 3) { s.add(enFr, "s$it", "h$it") }
        assertEquals(TranslationsStore.MAX, s.list().size)
        assertEquals("s3", s.list().first().said)
    }

    @Test fun pairParsesAndFallsBack() {
        assertEquals(LanguagePair(Language.Japanese, Language.Spanish), LanguagePair.parse("ja,es"))
        assertEquals(LanguagePair.DEFAULT, LanguagePair.parse(null))
        assertEquals(LanguagePair.DEFAULT, LanguagePair.parse("fr,fr"))
        assertEquals(LanguagePair.DEFAULT, LanguagePair.parse("xx,fr"))
        assertEquals("fr,en", frEn.toString())
        assertTrue(enFr.sameAs(frEn))
        assertFalse(enFr.sameAs(enDe))
    }
}
