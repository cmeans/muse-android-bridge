package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleReceiptsTest {
    @Test fun aReplayedBacklogGetsFiveReceiptsThenCounts() {
        val s = StaleReceipts()
        repeat(5) { assertTrue(s.shouldSend()) }
        repeat(7) { assertFalse(s.shouldSend()) }
        assertEquals(7, s.suppressed)
    }

    @Test fun aFreshMessageEndsTheBacklog() {
        // The cap is for a catch-up replay. Once a live message arrives, later stale ones
        // (a phone clock that's off, say) get their receipts again: they're the signal.
        val s = StaleReceipts()
        repeat(6) { s.shouldSend() }
        assertEquals(1, s.freshArrived())
        repeat(20) { assertTrue(s.shouldSend()) }
    }

    @Test fun aNewConnectionStartsOver() {
        val s = StaleReceipts()
        repeat(8) { s.shouldSend() }
        assertEquals(3, s.connected())
        repeat(5) { assertTrue(s.shouldSend()) }
        assertFalse(s.shouldSend())
    }
}
