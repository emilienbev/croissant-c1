package com.bleural.croissant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class PhotosStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun savesNamedByTimeNewestFirstAndDeletes() {
        val s = PhotosStore(File(tmp.root, "photos"))
        val a = s.save(byteArrayOf(1), LocalDateTime.of(2026, 9, 14, 18, 30, 5, 120_000_000))
        val b = s.save(byteArrayOf(2), LocalDateTime.of(2026, 9, 15, 9, 0, 0))
        assertEquals("20260914-183005-120.jpg", a.name)
        assertEquals(listOf(b, a), s.list())
        assertEquals(LocalDateTime.of(2026, 9, 15, 9, 0), PhotosStore.takenAt(b))
        assertNull(PhotosStore.takenAt(File("other.jpg")))
        val c = s.save(byteArrayOf(3), LocalDateTime.of(2026, 9, 15, 9, 0, 0))   // same instant: still two files
        assertTrue(c.name != b.name)
        assertEquals(3, PhotosStore(File(tmp.root, "photos")).list().size)
        s.delete(b)
        assertEquals(listOf(c, a), s.list())
    }

    @Test fun keepsAnExchangeBesideThePhotoAndDeletesItWithIt() {
        val s = PhotosStore(File(tmp.root, "photos"))
        val a = s.save(byteArrayOf(1), LocalDateTime.of(2026, 9, 15, 13, 4, 0))
        assertNull(PhotosStore.exchange(a))
        s.keep(a, Exchange(1_000L, "What plant is this?", "A pothos."))
        assertEquals(Exchange(1_000L, "What plant is this?", "A pothos."), PhotosStore.exchange(a))
        s.keep(a, Exchange(2_000L, "Is it toxic to cats?", "Yes."))   // the latest replaces the earlier one
        assertEquals("Yes.", PhotosStore.exchange(a)?.answer)
        assertEquals(listOf(a), s.list())   // the sidecar is not a photo
        s.delete(a)
        assertFalse(File(tmp.root, "photos/20260915-130400-000.json").exists())
        assertNull(PhotosStore.exchange(a))
    }
}
