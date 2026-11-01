package com.bleural.croissant.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** croissant-server's side as text (server/croissant_server/backups.py): the upload's frames, its errors, the list. */
class BackupWireTest {
    private val mac = "macbook"

    @Test fun theUploadsFrames() {
        assertEquals(Reply.Ready("2026-11-01-1402.zip"), BackupWire.reply("""{"type":"ready","name":"2026-11-01-1402.zip"}"""))
        assertEquals(Reply.Got(1048576L), BackupWire.reply("""{"type":"got","bytes":1048576}"""))
        assertEquals(Reply.Saved("2026-11-01-1402.zip", "~/.croissant/backups"),
            BackupWire.reply("""{"type":"saved","name":"2026-11-01-1402.zip","folder":"~/.croissant/backups"}"""))
        assertEquals(Reply.Error("nope"), BackupWire.reply("""{"type":"error","error":"nope"}"""))
        for (other in listOf("not json", """{"type":"got"}""", """{"type":"saved","name":"a.zip"}""", """{"type":"hello"}""")) {
            assertEquals(Reply.Other(other), BackupWire.reply(other))
        }
        val begin = org.json.JSONObject(BackupWire.begin(123L, "ab".repeat(32)))
        assertEquals("begin", begin.getString("type"))
        assertEquals(123, begin.getInt("size"))
    }

    @Test fun damagedOnArrivalCarriesBothSizes() {
        val said = "the backup arrived damaged: 900 bytes, sha256 ${"a".repeat(64)}, expected 1000 bytes, sha256 ${"b".repeat(64)}"
        assertEquals(BackupProblem.Damaged(900L, 1000L, said), BackupWire.problem(said, mac))
    }

    @Test fun aFullDiskIsToldApart() {
        for (said in listOf("the disk on this machine is full: 10 bytes free, the backup is 99 bytes",
            "the disk on this machine is full after 5 of 99 bytes: [Errno 28] No space left on device")) {
            assertEquals(BackupProblem.DiskFull(mac, said), BackupWire.problem(said, mac))
        }
        val over = "the backup is 3000000000 bytes, over the 2147483648 bytes this server takes"
        assertEquals(BackupProblem.ServerSaid(mac, over), BackupWire.problem(over, mac))
    }

    @Test fun anOldServerClosesBackupAsAnUnknownPath() {
        assertEquals(BackupProblem.TooOld(mac), BackupWire.closed(1008, "unknown path", ready = false, mac))
        assertTrue(BackupWire.closed(1008, "unknown path", ready = true, mac) is BackupProblem.Dropped)
        assertTrue(BackupWire.closed(1000, "", ready = false, mac) is BackupProblem.Dropped)
    }

    @Test fun theListSplitsByTheSerialAndMarksANewerVersion() {
        val json = """{"folder": "~/.croissant/backups", "backups": [
            {"name": "2026-11-01-1402.zip", "size": 123, "app": "com.bleural.croissant", "version": "0.1.0", "versionCode": 100, "serial": "TEST-C1-ONE", "date": "2026-11-01T14:02:31+01:00"},
            {"name": "2026-10-30-2210.zip", "size": 456, "app": "com.bleural.croissant", "version": "0.2.0", "versionCode": 200, "serial": "TEST-C1-TWO", "date": "2026-10-30T22:10:00+01:00"},
            {"name": "2026-10-20-0830.zip", "size": 789, "error": "the zip has no manifest.json"},
            {"name": "2026-10-12-1840.zip", "size": 10, "app": "com.bleural.croissant", "version": "0.1.0", "serial": "TEST-C1-ONE", "date": "x"}
        ]}"""
        val all = BackupWire.list(json, appCode = 100)
        assertEquals("~/.croissant/backups", all.folder)
        val l = BackupList.split(all.folder, mac, all.backups, "TEST-C1-ONE")
        assertEquals(listOf("2026-11-01-1402.zip"), l.mine.map { it.name })
        assertEquals(listOf("2026-10-30-2210.zip"), l.others.map { it.name })
        assertEquals(listOf("2026-10-20-0830.zip", "2026-10-12-1840.zip"), l.unreadable.map { it.name })
        assertFalse(l.mine[0].newer)
        assertTrue(l.others[0].newer)
        assertEquals(BackupAbout("com.bleural.croissant", "0.2.0", 200, "TEST-C1-TWO", "2026-10-30T22:10:00+01:00"), l.others[0].about)
        assertEquals("the zip has no manifest.json", l.unreadable[0].error)
        assertNull(l.unreadable[0].about)
        assertTrue(l.unreadable[1].error!!, "versionCode" in l.unreadable[1].error!!)
    }

    @Test fun theServersErrorField() {
        assertEquals("no such backup: x.zip", BackupWire.error("""{"error": "no such backup: x.zip"}"""))
        assertNull(BackupWire.error("<html>"))
    }
}
