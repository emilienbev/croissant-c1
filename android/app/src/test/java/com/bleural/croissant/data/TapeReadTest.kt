package com.bleural.croissant.data

import com.bleural.croissant.audio.Wav
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime

class TapeReadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = OffsetDateTime.parse("2026-09-24T17:00:00+01:00")

    private fun shelf(): RecordingsStore {
        val s = RecordingsStore(File(tmp.root, "recordings"))
        fun tape(minute: Int, number: Int, name: String?, words: String?) {
            val f = s.newFile(LocalDateTime.of(2026, 9, 24, 16, minute))
            f.writeBytes(Wav.header(0))
            s.keep(f, RecordingMeta("2026-09-24T16:%02d:00+01:00".format(minute), 60_000L, RecordingMeta.STOP,
                sent = words?.let { f.nameWithoutExtension }, transcript = words?.let { listOf(Segment(0.0, 2.0, it), Segment(31.24, 33.0, "and more")) },
                number = number, name = name))
        }
        tape(0, 20, null, "earlier this month")
        tape(1, 21, "Thinking Like a Compiler", "a real pleasure")
        tape(2, 22, "Compiler notes", null)
        return s
    }

    private fun read(tape: String): JSONObject {
        val s = shelf()
        return ToolExecutor(RemindersStore(File(tmp.root, "r.json")) { now }, EventsStore(File(tmp.root, "e.json")) { now },
            TimersStore(File(tmp.root, "t.json")) { now }, s) { now }.run("tape_read", JSONObject().put("tape", tape)) as JSONObject
    }

    @Test fun byNumberInAnyOfItsSpellings() {
        for (asked in listOf("21", "Tape 21", "tape21", "#21", "tape number 21")) assertEquals(asked, 21, read(asked).getInt("number"))
        assertEquals("Tape 20", read("20").getString("title"))
    }

    @Test fun byTitleWordsAndHandsBackTheSegments() {
        val t = read("the thinking compiler talk")
        assertEquals(21, t.getInt("number"))
        assertEquals("Thinking Like a Compiler", t.getString("title"))
        val segs = t.getJSONArray("segments")
        assertEquals(2, segs.length())
        assertEquals(31.2, segs.getJSONObject(1).getDouble("start"), 0.0)
        assertEquals("a real pleasure", segs.getJSONObject(0).getString("text"))
    }

    @Test fun aMissTieOrUntranscribedTapeIsAnErrorTheModelCanUse() {
        val miss = assertThrows(ToolError::class.java) { read("99") }
        assertTrue(miss.message, miss.message!!.contains("tape 21, \"Thinking Like a Compiler\""))
        val tie = assertThrows(ToolError::class.java) { read("compiler") }
        assertTrue(tie.message, tie.message!!.contains("could be"))
        val raw = assertThrows(ToolError::class.java) { read("compiler notes") }
        assertTrue(raw.message, raw.message!!.contains("not been transcribed"))
    }
}
