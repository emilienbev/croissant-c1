package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = GeneratedStore(File(tmp.root, "generated"))

    /** Asked for just now: old enough to be waiting, young enough not to be given up on. */
    private val now get() = System.currentTimeMillis()

    @Test fun addsPendingNewestFirstAndSurvivesAReload() {
        val s = store()
        s.add("g_aaaaaa", "a red fox in the snow", at = now - 2000)
        s.add("g_bbbbbb", "a city at night", at = now)
        assertEquals(listOf("g_bbbbbb", "g_aaaaaa"), s.list().map { it.id })
        assertTrue(s.list().all { it.pending })
        assertEquals("a city at night", s.get("g_bbbbbb")?.prompt)
        // The id is the name on disk, so a reboot finds the same two waiting.
        assertEquals(2, store().list().size)
    }

    @Test fun oneMadeOnTheC1StaysTheC1sThroughEveryChange() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = now, c1 = true)
        s.add("g_bbbbbb", "a city", at = now)
        assertTrue(s.get("g_aaaaaa")!!.c1)
        assertFalse(s.get("g_bbbbbb")!!.c1)
        s.tried("g_aaaaaa")
        s.fail("g_aaaaaa", "Cut short")
        assertTrue(store().get("g_aaaaaa")!!.c1)
        s.save("g_aaaaaa", byteArrayOf(1))
        assertTrue(store().get("g_aaaaaa")!!.c1)
        assertFalse(store().get("g_bbbbbb")!!.c1)
    }

    @Test fun addingTheSameIdTwiceKeepsTheFirst() {
        val s = store()
        s.add("g_aaaaaa", "first", at = 1000)
        s.add("g_aaaaaa", "second", at = 9000)
        assertEquals(1, s.list().size)
        assertEquals("first", s.get("g_aaaaaa")?.prompt)
    }

    @Test fun savingTheBytesEndsThePendingState() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = 1000)
        s.save("g_aaaaaa", byteArrayOf(1, 2, 3))
        val g = s.get("g_aaaaaa")!!
        assertFalse(g.pending)
        assertEquals("a fox", g.prompt)
        assertEquals(3, g.file!!.length().toInt())
        assertEquals(1, g.tries)
        assertEquals(3, store().get("g_aaaaaa")!!.file!!.length().toInt())
    }

    @Test fun failingKeepsTheRowAndItsReason() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = 1000)
        s.tried("g_aaaaaa")
        s.fail("g_aaaaaa", "no longer on the server")
        val g = s.get("g_aaaaaa")!!
        assertFalse(g.pending)
        assertNull(g.file)
        assertEquals("no longer on the server", g.failed)
        assertEquals(2, g.tries)
    }

    @Test fun aTransformRemembersThePhotoItCameFrom() {
        val s = store()
        s.add("g_aaaaaa", "an oil painting of it", at = 1000, source = "20260918-193648-993.jpg")
        s.tried("g_aaaaaa")
        s.save("g_aaaaaa", byteArrayOf(1))
        assertEquals("20260918-193648-993.jpg", store().get("g_aaaaaa")?.source)
        // A picture made from nothing has none, and neither has a row written before
        // transforms existed.
        s.add("g_bbbbbb", "a fox", at = 2000)
        assertNull(s.get("g_bbbbbb")?.source)
    }

    @Test fun deleteTakesThePictureWithIt() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = 1000)
        s.save("g_aaaaaa", byteArrayOf(1))
        val file = s.get("g_aaaaaa")!!.file!!
        s.delete("g_aaaaaa")
        assertEquals(emptyList<Generated>(), s.list())
        assertFalse(file.exists())
    }

    @Test fun savingOrFailingAnUnknownIdDoesNothing() {
        val s = store()
        s.save("g_zzzzzz", byteArrayOf(1))
        s.fail("g_zzzzzz", "gone")
        s.tried("g_zzzzzz")
        assertEquals(emptyList<Generated>(), s.list())
    }

    @Test fun aRowThatSatThroughTheServersKeepWindowIsGivenUpOn() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = now - Generated.KEEP_MS - 1)
        val g = s.get("g_aaaaaa")!!
        // Nothing wrote a reason down: the server was never reachable to give one, which
        // is the case this exists for. The clock is the reason.
        assertNull(g.failed)
        assertEquals("Never arrived", g.lost)
        assertFalse(g.pending)
        // A picture that did arrive is never given up on, however long ago it was asked for.
        s.save("g_aaaaaa", byteArrayOf(1))
        assertNull(s.get("g_aaaaaa")!!.lost)
    }

    @Test fun aCorruptRowIsIgnoredRatherThanFatal() {
        val s = store()
        s.add("g_aaaaaa", "a fox", at = 1000)
        File(tmp.root, "generated/g_bbbbbb.json").writeText("{ not json")
        assertEquals(listOf("g_aaaaaa"), store().list().map { it.id })
    }
}
