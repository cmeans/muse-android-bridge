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

    @Test fun unicodeLineAndFormatCharactersReplaced() {
        // U+2028/U+2029 render as line breaks in a TextView; U+202E/U+200B hide or reorder text.
        assertEquals("x?y?z?w?v", logExcerpt("x\u2028y\u2029z\u202Ew\u200Bv"))
    }

    @Test fun nonAsciiReplaced() {
        assertEquals("caf?", logExcerpt("café"))
        assertEquals("??", logExcerpt("\uD83D\uDE00"))
    }

    @Test fun commandUnchanged() {
        assertEquals("launch meet", logExcerpt("launch meet"))
    }
}
