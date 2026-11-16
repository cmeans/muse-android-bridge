package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 0.4: every receipt ends with the command's message id (PROTOCOL.md §6). */
class ReceiptsTest {
    @Test fun idAppendedLast() {
        assertEquals("ok launch meet id=Ab12Cd34Ef56", Receipts.withId(Receipts.ok(Command.Launch("meet")), "Ab12Cd34Ef56"))
        assertEquals("err ring find rate-limited id=x1", Receipts.withId(Receipts.rateLimited(Command.RingFind), "x1"))
        assertEquals("err unknown-command id=x1", Receipts.withId(Receipts.unknown(), "x1"))
        assertEquals("dropped-stale id=x1", Receipts.withId("dropped-stale", "x1"))
        assertEquals(
            "ok status report battery=87 version=0.2.0 protocol=0.4 owner=on family=off launch-check=on time=1800000000 id=x1",
            Receipts.withId(Receipts.status(87, "0.2.0", "0.4", StopState(all = false, family = true), launchCheck = true, nowMs = 1_800_000_000_999L), "x1"),
        )
    }

    @Test fun statusTimeIsWholeSeconds() {
        // milliseconds are dropped, not rounded: 999 ms past the second is still that second
        assertTrue(
            Receipts.status(5, "0.2.0", "0.4", StopState(false, false), launchCheck = false, nowMs = 1_800_000_000_999L)
                .endsWith(" launch-check=off time=1800000000"),
        )
    }

    @Test fun idGrammar() {
        assertTrue(isValidMessageId("Ab12Cd34Ef56"))
        assertTrue(isValidMessageId("a".repeat(32)))
        listOf("", " ", "a".repeat(33), "ab cd", "ab=cd", "ab-cd", "abé", "ab\n").forEach { assertFalse(it, isValidMessageId(it)) }
        assertThrows(IllegalArgumentException::class.java) { Receipts.withId("ok ring find", "ab cd") }
    }

    @Test fun sinceUsesOnlyARealNtfyId() {
        assertEquals("Ab12Cd34Ef56", sinceParam("Ab12Cd34Ef56"))
        // no id yet; an older build's stored value; ids ntfy would read as a time or duration
        listOf(null, "never-seen", "1", "1800000000", "2m", "a".repeat(13), "Ab12Cd34Ef5").forEach {
            assertEquals(it.toString(), "2m", sinceParam(it))
        }
    }

    @Test fun longestStatusReceiptFits() {
        // a 12-digit time: the server's pattern allows up to 12 digits
        val r = Receipts.withId(
            Receipts.status(100, "1".repeat(16), "0.4", StopState(true, true), launchCheck = false, nowMs = 999_999_999_999_000L),
            "a".repeat(32),
        )
        assertTrue(r.contains(" time=999999999999 "))
        assertTrue(r.toByteArray().size <= 200)
    }
}
