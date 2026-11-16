package com.intfar.mabridge.protocol

import com.intfar.mabridge.protocol.Channel.FAMILY
import com.intfar.mabridge.protocol.Channel.OWNER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private val t = 1_800_000_000_000L
    private val min = 60_000L

    /** A command that passes the limit and then runs. */
    private fun RateLimiter.run(channel: Channel, command: Command, now: Long): Boolean =
        allows(channel, command, now).also { if (it) record(channel, command, now) }

    @Test fun fourthRingInTenMinutesIsRefused() {
        val r = RateLimiter()
        assertTrue(r.run(OWNER, Command.RingFind, t))
        assertTrue(r.run(OWNER, Command.RingFind, t + 2 * min))
        assertTrue(r.run(OWNER, Command.RingFind, t + 4 * min))
        assertFalse(r.run(OWNER, Command.RingFind, t + 6 * min))
    }

    @Test fun windowSlides() {
        val r = RateLimiter()
        repeat(3) { assertTrue(r.run(OWNER, Command.RingFind, t + it)) }
        assertFalse(r.run(OWNER, Command.RingFind, t + 9 * min))
        // Exactly ten minutes after the first, its slot is free again.
        assertTrue(r.run(OWNER, Command.RingFind, t + 10 * min))
    }

    @Test fun refusedCommandsDontUseUpTheLimit() {
        val r = RateLimiter()
        repeat(3) { r.run(OWNER, Command.RingFind, t + it) }
        repeat(20) { assertFalse(r.run(OWNER, Command.RingFind, t + min + it)) }
        assertTrue(r.run(OWNER, Command.RingFind, t + 10 * min + 3))
    }

    @Test fun checkingDoesNotCountOnlyRecordingDoes() {
        val r = RateLimiter()
        repeat(10) { assertTrue(r.allows(OWNER, Command.RingFind, t + it)) }
        repeat(3) { assertTrue(r.run(OWNER, Command.RingFind, t + 20 + it)) }
        assertFalse(r.allows(OWNER, Command.RingFind, t + 30))
    }

    @Test fun aNewRunOfRefusalsGetsItsReceiptAgain() {
        val r = RateLimiter()
        repeat(3) { r.run(OWNER, Command.RingFind, t + it * min) }
        assertTrue(r.shouldReceiptRefusal(OWNER, Command.RingFind, t + 3 * min))
        // Ten minutes on, rings run again, then the limit is hit again: that refusal is
        // a new run and must get a receipt, not vanish.
        repeat(3) { assertTrue(r.run(OWNER, Command.RingFind, t + (10 + it) * min)) }
        assertTrue(r.shouldReceiptRefusal(OWNER, Command.RingFind, t + 12 * min + 30_000))
        assertFalse(r.shouldReceiptRefusal(OWNER, Command.RingFind, t + 14 * min))
    }

    @Test fun channelsAreLimitedSeparately() {
        val r = RateLimiter()
        repeat(3) { r.run(FAMILY, Command.RingFind, t + it) }
        assertFalse(r.run(FAMILY, Command.RingFind, t + 10))
        assertTrue(r.run(OWNER, Command.RingFind, t + 10))
    }

    @Test fun launchesShareTenPerTenMinutesAcrossApps() {
        val r = RateLimiter()
        val apps = listOf("meet", "chrome", "whatsapp", "facebook", "muse")
        repeat(10) { assertTrue(r.run(FAMILY, Command.Launch(apps[it % apps.size]), t + it)) }
        assertFalse(r.run(FAMILY, Command.Launch("meet"), t + 20))
        // Rings have their own budget.
        assertTrue(r.run(FAMILY, Command.RingFind, t + 21))
    }

    @Test fun statusAndPlaceholdersAreNotLimited() {
        val r = RateLimiter()
        repeat(50) {
            assertTrue(r.run(OWNER, Command.StatusReport, t + it))
            assertTrue(r.run(OWNER, Command.BootstrapRustdesk, t + it))
        }
    }

    @Test fun onlyTheFirstRefusalInAWindowGetsAReceipt() {
        // A looping publisher mustn't queue one rate-limited receipt per message.
        val r = RateLimiter()
        assertTrue(r.shouldReceiptRefusal(OWNER, Command.RingFind, t))
        repeat(50) { assertFalse(r.shouldReceiptRefusal(OWNER, Command.RingFind, t + 1 + it)) }
        assertTrue("other channel", r.shouldReceiptRefusal(FAMILY, Command.RingFind, t + 2))
        assertTrue("other kind", r.shouldReceiptRefusal(OWNER, Command.Launch("meet"), t + 3))
        assertTrue("next window", r.shouldReceiptRefusal(OWNER, Command.RingFind, t + 10 * min))
    }
}
