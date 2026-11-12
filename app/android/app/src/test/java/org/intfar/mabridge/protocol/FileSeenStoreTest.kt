package org.intfar.mabridge.protocol

import org.intfar.mabridge.FileSeenStore
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

    @Test fun idWithSpaceOrNewlineCannotForgeEntries() {
        val f = File(tmp.root, "seen_ids.txt")
        val s = FileSeenStore(f)
        assertTrue(s.markIfNew("x 1\ny", t))
        assertTrue(FileSeenStore(f).markIfNew("y", t + 1))
        assertFalse(FileSeenStore(f).markIfNew("x 1\ny", t + 2))
    }
}
