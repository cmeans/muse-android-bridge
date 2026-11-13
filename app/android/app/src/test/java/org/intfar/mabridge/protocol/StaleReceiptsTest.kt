package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleReceiptsTest {
    private val open = 1_800_000_000L

    @Test fun aReplayedBacklogGetsFiveReceiptsThenCounts() {
        val s = StaleReceipts()
        s.connected(open)
        // Cached messages: older than the connection's open event.
        repeat(5) { assertTrue(s.shouldSend(open - 600 + it)) }
        repeat(7) { assertFalse(s.shouldSend(open - 300 + it)) }
        assertEquals(7, s.connected(open + 100))
    }

    @Test fun liveStaleMessagesAlwaysGetReceipts() {
        // A phone clock that's off makes every live message stale. They arrive after the
        // open event, so they're never part of the backlog: their receipts are the sign.
        val s = StaleReceipts()
        s.connected(open)
        repeat(20) { assertTrue(s.shouldSend(open + 1 + it)) }
    }

    @Test fun aNewConnectionStartsOverAndReportsOnce() {
        val s = StaleReceipts()
        s.connected(open)
        repeat(8) { s.shouldSend(open - 100) }
        assertEquals(3, s.connected(open + 50))
        assertEquals(0, s.connected(open + 60))
        repeat(5) { assertTrue(s.shouldSend(open + 10)) }
        assertFalse(s.shouldSend(open + 10))
    }

    @Test fun beforeAnyOpenEventNothingIsCapped() {
        val s = StaleReceipts()
        repeat(10) { assertTrue(s.shouldSend(open)) }
    }

    @Test fun aMessageInTheOpensOwnSecondIsLive() {
        val s = StaleReceipts()
        s.connected(open)
        repeat(10) { s.shouldSend(open - 1) }
        assertTrue(s.shouldSend(open))
    }

    @Test fun flushReportsTheCountOnce() {
        val s = StaleReceipts()
        s.connected(open)
        repeat(9) { s.shouldSend(open - 50) }
        assertEquals(4, s.flush())
        assertEquals(0, s.flush())
        assertEquals(0, s.connected(open + 10))
    }
}
