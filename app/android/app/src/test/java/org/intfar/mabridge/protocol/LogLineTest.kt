package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLineTest {
    @Test fun controlCharactersReplaced() {
        assertEquals("a?b", logExcerpt("a\nb"))
        assertEquals("a?b?c?d", logExcerpt("a\rb\tc\u0000d"))
    }

    @Test fun longBodyTruncated() {
        val e = logExcerpt("x".repeat(10_000))
        assertEquals(64, e.length)
        assertTrue(e.endsWith("…"))
    }

    @Test fun exactly64Unchanged() {
        val s = "y".repeat(64)
        assertEquals(s, logExcerpt(s))
    }

    @Test fun commandUnchanged() {
        assertEquals("launch meet", logExcerpt("launch meet"))
    }
}
