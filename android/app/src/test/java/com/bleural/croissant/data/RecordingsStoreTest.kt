package com.bleural.croissant.data

import com.bleural.croissant.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime

class RecordingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "recordings")

    @Test fun namesByStartNewestFirstAndKeepsTheSidecar() {
        val s = RecordingsStore(dir())
        val a = s.newFile(LocalDateTime.of(2026, 9, 16, 10, 47, 0))
        assertEquals("20260916-104700.wav", a.name)
        assertEquals(LocalDateTime.of(2026, 9, 16, 10, 47), RecordingsStore.startedAt(a))
        assertNull(RecordingsStore.startedAt(File("other.wav")))
        a.writeBytes(Wav.header(64_000) + ByteArray(64_000))
        s.keep(a, RecordingMeta("2026-09-16T10:47:00+01:00", 2_000L, RecordingMeta.STOP))
        val b = s.newFile(LocalDateTime.of(2026, 9, 16, 10, 47, 0))   // the same second: a different name
        assertTrue(b.name != a.name)
        b.writeBytes(Wav.header(0))
        s.keep(b, RecordingMeta("2026-09-16T10:47:00+01:00", 0L, RecordingMeta.STOP))
        assertEquals(listOf(b, a), s.list().map { it.file })
        assertEquals(2_000L, s.list()[1].durationMs)
        assertEquals(false, s.list()[1].meta!!.transcribed)
        s.keep(a, s.list()[1].meta!!.copy(transcript = listOf(Segment(0.0, 1.5, "Hello")), sent = "20260916-104700"))
        assertEquals(true, RecordingsStore.meta(a)!!.transcribed)
        assertEquals("Hello", RecordingsStore.meta(a)!!.transcript!!.single().text)
        s.delete(a)
        assertEquals(listOf(b), s.list().map { it.file })
        assertEquals(false, RecordingsStore.sidecar(a).exists())
    }

    @Test fun repairsAFileLeftOpenByACrash() {
        val d = dir(); d.mkdirs()
        val f = File(d, "20260916-104700.wav")
        f.writeBytes(Wav.header(0) + ByteArray(32_000 * 3))   // three seconds, the header never patched
        File(d, "20260916-104700.json").writeText(RecordingMeta("2026-09-16T10:47:00+01:00", 0L, RecordingMeta.RECORDING).toJson().toString())
        val s = RecordingsStore(d)
        val r = s.list().single()
        assertEquals(RecordingMeta.INTERRUPTED, r.meta!!.ended)
        assertEquals(3_000L, r.meta.durationMs)
        assertEquals(96_000, Wav.dataSize(f))
    }

    @Test fun numbersOldTapesInOrderThenCountsOnAndRenames() {
        val d = dir(); d.mkdirs()
        for (n in listOf("20260915-091200", "20260916-104700")) {
            File(d, "$n.wav").writeBytes(Wav.header(32_000) + ByteArray(32_000))
            File(d, "$n.json").writeText(RecordingMeta("2026-09-16T10:47:00+01:00", 1_000L, RecordingMeta.STOP).toJson().toString())
        }
        val s = RecordingsStore(d)
        assertEquals(listOf(2, 1), s.list().map { it.number })
        assertEquals(listOf("Tape 2", "Tape 1"), s.list().map { it.title })
        assertEquals(3, s.nextNumber())
        val old = s.list()[1].file
        s.rename(old, "  Standup  ")
        assertEquals("Standup", s.list()[1].title)
        assertEquals(1, s.list()[1].number)
        s.rename(old, "Tape 1")                      // its default: no name kept
        assertNull(s.list()[1].meta!!.name)
        s.rename(old, "Standup"); s.rename(old, " ")
        assertEquals("Tape 1", s.list()[1].title)
        assertEquals(listOf(2, 1), RecordingsStore(d).list().map { it.number })   // numbered once, not again
    }

    /** Tapes 1, 2 and 3 in a fresh store, numbered as the recorder numbers them. */
    private fun threeTapes(s: RecordingsStore): List<File> = (1..3).map { n ->
        s.newFile(LocalDateTime.of(2026, 9, 22, 10, n, 0)).also {
            it.writeBytes(Wav.header(0))
            s.keep(it, RecordingMeta("2026-09-22T10:0$n:00+01:00", 0L, RecordingMeta.STOP, number = s.nextNumber()))
        }
    }

    @Test fun aGapInTheMiddleStaysAGap() {
        val s = RecordingsStore(File(tmp.root, "a"))
        val tapes = threeTapes(s)
        s.delete(tapes[1])
        assertEquals(4, s.nextNumber())
    }

    @Test fun theNewestDeletedIsItsNumberAgain() {
        val s = RecordingsStore(File(tmp.root, "b"))
        val tapes = threeTapes(s)
        s.delete(tapes[2])
        assertEquals(3, s.nextNumber())
    }

    @Test fun wavHeaderAndPatch() {
        val h = Wav.header(0)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals("data", String(h, 36, 4))
        val f = File(tmp.root, "t.wav")
        f.writeBytes(h + ByteArray(640))
        assertEquals(0, Wav.dataSize(f))
        RandomAccessFile(f, "rw").use { Wav.patch(it, 640) }
        assertEquals(640, Wav.dataSize(f))
        assertEquals(20L, Wav.millis(640))
        assertNull(Wav.dataSize(File(tmp.root, "missing.wav")))
    }
}
