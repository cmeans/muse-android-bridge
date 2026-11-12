package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test fun doublesToCapWithoutJitter() {
        val b = Backoff(random = { 0.0 })
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L), List(7) { b.nextDelayMs() })
    }

    @Test fun resetStartsOver() {
        val b = Backoff(random = { 0.0 })
        repeat(4) { b.nextDelayMs() }
        b.reset()
        assertEquals(2000L, b.nextDelayMs())
    }

    @Test fun jitterBounded() {
        val b = Backoff(random = { 1.0 })
        val delays = List(20) { b.nextDelayMs() }
        assertTrue(delays.all { it <= 72_000L })
        assertEquals(2400L, delays[0])
    }
}
