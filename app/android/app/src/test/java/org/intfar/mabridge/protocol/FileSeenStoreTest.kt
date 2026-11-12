package org.intfar.mabridge.protocol

import org.intfar.mabridge.FileSeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSeenStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t = 1_800_000_000_000L

    @Test fun survivesRestart() {
        val f = File(tmp.root, "seen_ids.txt")
        assertTrue(FileSeenStore(f).markIfNew("a", t))
        assertFalse(FileSeenStore(f).markIfNew("a", t + 1000))
    }

    @Test fun windowExpiresAcrossRestart() {
        val f = File(tmp.root, "seen_ids.txt")
        FileSeenStore(f).markIfNew("a", t)
        assertTrue(FileSeenStore(f).markIfNew("a", t + DEDUP_WINDOW_MS + 1))
    }

    @Test fun corruptLinesIgnored() {
        val f = File(tmp.root, "seen_ids.txt")
        // "Yw" is base64url("c"), "Yg" is base64url("b").
        f.writeText("garbage\nYg notanumber\n\n!!! 5\nYw ${t}\n")
        val s = FileSeenStore(f)
        assertFalse(s.markIfNew("c", t + 1))
        assertTrue(s.markIfNew("b", t + 1))
    }

    @Test fun writeFailureStillDedupsInMemory() {
        val dir = tmp.newFolder("ro")
        val f = File(dir, "seen_ids.txt")
        dir.setWritable(false)
        try {
            var errors = 0
            val s = FileSeenStore(f) { errors++ }
            assertTrue(s.markIfNew("a", t))
            assertFalse(s.markIfNew("a", t + 1))
            assertTrue(errors >= 1)
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun unreadableFileStartsEmptyInsteadOfThrowing() {
        val f = tmp.newFolder("seen_ids.txt") // a directory where the file should be
        var errors = 0
        val s = FileSeenStore(f, onError = { errors++ })
        assertEquals("the failed read is reported", 1, errors)
        assertTrue(s.markIfNew("a", t))
    }

    @Test fun newIdAppendsOneLineInsteadOfRewriting() {
        val f = File(tmp.root, "seen_ids.txt")
        val s = FileSeenStore(f)
        for (i in 1..50) s.markIfNew("id$i", t + i)
        assertEquals(50, f.readLines().size)
        // Appending kept the earlier lines: id1 still dedups after a restart.
        assertFalse(FileSeenStore(f).markIfNew("id1", t + 100))
    }

    @Test fun appendKeepsExistingBytes() {
        val f = File(tmp.root, "seen_ids.txt")
        f.writeText("marker-line\n") // a line a rewrite would drop
        FileSeenStore(f).markIfNew("a", t)
        assertTrue(f.readText().startsWith("marker-line\n"))
    }

    @Test fun tornLastLineDoesNotSwallowTheNextId() {
        val f = File(tmp.root, "seen_ids.txt")
        f.writeText("YQ $t") // base64url("a"), cut off before its newline
        FileSeenStore(f).markIfNew("b", t + 2)
        val s = FileSeenStore(f)
        assertFalse("a lost", s.markIfNew("a", t + 3))
        assertFalse("b lost", s.markIfNew("b", t + 3))
    }

    @Test fun wellFormedFileGetsNoExtraNewline() {
        val f = File(tmp.root, "seen_ids.txt")
        f.writeText("YQ $t\n")
        FileSeenStore(f).markIfNew("b", t + 2)
        assertEquals(listOf("YQ $t", "Yg ${t + 2}"), f.readLines())
    }

    @Test fun expiredEntriesAreCompactedAway() {
        val f = File(tmp.root, "seen_ids.txt")
        val s = FileSeenStore(f)
        for (i in 1..50) s.markIfNew("old$i", t)
        // A day later every old entry has expired; enough new ones trigger a compaction.
        val later = t + DEDUP_WINDOW_MS + 1
        for (i in 1..10) s.markIfNew("new$i", later + i)
        val lines = f.readLines()
        assertTrue("file still holds expired lines: ${lines.size}", lines.size <= 10)
        assertFalse(FileSeenStore(f).markIfNew("new3", later + 100))
        assertTrue(FileSeenStore(f).markIfNew("old3", later + 100))
    }

    @Test fun idWithSpaceOrNewlineCannotForgeEntries() {
        val f = File(tmp.root, "seen_ids.txt")
        val s = FileSeenStore(f)
        assertTrue(s.markIfNew("x 1\ny", t))
        assertTrue(FileSeenStore(f).markIfNew("y", t + 1))
        assertFalse(FileSeenStore(f).markIfNew("x 1\ny", t + 2))
    }
}
