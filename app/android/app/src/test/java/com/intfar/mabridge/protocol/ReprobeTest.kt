package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #33 items 4-6: the re-probe deadline, what a 403 probe round does, and the bridge's own reconnects. */
class ReprobeTest {
    private val owner = "mab-p-owner"

    // Item 4: the deadline is on elapsedRealtime, so a line read after deep sleep finds it due.
    @Test fun reprobeDueOnlyWhileSomethingIsRefused() {
        assertNull(reprobeDueAt(emptyList(), 1_000L))
        assertEquals(1_000L + 15 * 60_000L, reprobeDueAt(listOf("m1"), 1_000L))
        assertEquals(15 * 60_000L, PROBE_EVERY_MS)
    }

    @Test fun reprobeIsDueAtItsDeadline() {
        val due = reprobeDueAt(listOf("m1"), 1_000L)
        assertFalse(isReprobeDue(due, 1_000L))
        assertFalse(isReprobeDue(due, 1_000L + PROBE_EVERY_MS - 1))
        assertTrue(isReprobeDue(due, 1_000L + PROBE_EVERY_MS))
        // Deep sleep: the uptime timer hasn't fired, but the next line read is well past the deadline.
        assertTrue(isReprobeDue(due, 1_000L + 3 * PROBE_EVERY_MS))
        assertFalse(isReprobeDue(null, Long.MAX_VALUE))
    }

    // Item 5: a 403 on the whole list where every topic then answers 200 alone.
    @Test fun allReadableReconnectsAtOnce() {
        val probed = linkedMapOf(owner to 200, "mab-p-family-m1" to 200, "mab-p-family-m2" to 200)
        assertEquals(ProbeRound.AllReadable(reconnectNow = true), probeRound(owner, "p", listOf("m1", "m2"), probed, retriedAllReadable = false))
        // A second time in a row backs off, so a server that keeps doing it isn't hit in a tight loop.
        assertEquals(ProbeRound.AllReadable(reconnectNow = false), probeRound(owner, "p", listOf("m1", "m2"), probed, retriedAllReadable = true))
    }

    // Pre-QA r1 row 4: against a server that keeps refusing the list while each topic answers
    // alone, only the first round reconnects at once; the rest back off until a round ends
    // some other way.
    @Test fun allReadableSettlesIntoBackoff() {
        val all = linkedMapOf(owner to 200, "mab-p-family-m1" to 200)
        val some = linkedMapOf(owner to 200, "mab-p-family-m1" to 403)
        // null: a connect that ends without a probe round (subscribed, or a network error).
        val connects: List<Map<String, Int>?> = listOf(all, all, all, all, some, all, all, null, all)
        var retried = false
        val actions = connects.map { probed ->
            val round = probed?.let { probeRound(owner, "p", listOf("m1"), it, retried) }
            retried = retriedAfter(round)
            when (round) {
                is ProbeRound.AllReadable -> if (round.reconnectNow) "now" else "backoff"
                is ProbeRound.Partial -> "partial"
                is ProbeRound.OwnerRefused -> "owner"
                null -> "other"
            }
        }
        assertEquals(listOf("now", "backoff", "backoff", "backoff", "partial", "now", "backoff", "other", "now"), actions)
    }

    @Test fun someRefusedSubscribesToTheRest() {
        val probed = linkedMapOf(owner to 200, "mab-p-family-m1" to 403, "mab-p-family-m2" to 200)
        assertEquals(
            ProbeRound.Partial(topics = listOf(owner, "mab-p-family-m2"), refused = listOf("m1")),
            probeRound(owner, "p", listOf("m1", "m2"), probed, retriedAllReadable = false),
        )
        // A member the round didn't reach counts as refused.
        assertEquals(
            ProbeRound.Partial(topics = listOf(owner), refused = listOf("m1")),
            probeRound(owner, "p", listOf("m1"), linkedMapOf(owner to 200), retriedAllReadable = true),
        )
    }

    @Test fun ownerNot200IsLoggedAsItself() {
        val probed = linkedMapOf(owner to 403, "mab-p-family-m1" to 200)
        assertEquals(ProbeRound.OwnerRefused(403), probeRound(owner, "p", listOf("m1"), probed, retriedAllReadable = false))
        assertEquals(ProbeRound.OwnerRefused(500), probeRound(owner, "p", listOf("m1"), linkedMapOf(owner to 500), retriedAllReadable = false))
    }

    // Item 6: the flag names the stream it meant to end, so a stale one can't mute a later stream.
    @Test fun ownReconnectMatchesOnlyItsStream() {
        val first = Any()
        val second = Any()
        assertTrue(endedByBridge(first, first))
        assertFalse(endedByBridge(first, second))
        assertFalse(endedByBridge(null, second))
        assertFalse(endedByBridge(null, null))
    }
}
