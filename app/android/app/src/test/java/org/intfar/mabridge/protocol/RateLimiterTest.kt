package org.intfar.mabridge.protocol

import org.intfar.mabridge.protocol.Channel.FAMILY
import org.intfar.mabridge.protocol.Channel.OWNER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private val t = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun fourthRingInTenMinutesIsRefused() {
        val r = RateLimiter()
        assertTrue(r.allow(OWNER, Command.RingFind, t))
        assertTrue(r.allow(OWNER, Command.RingFind, t + 2 * min))
        assertTrue(r.allow(OWNER, Command.RingFind, t + 4 * min))
        assertFalse(r.allow(OWNER, Command.RingFind, t + 6 * min))
    }

    @Test fun windowSlides() {
        val r = RateLimiter()
        repeat(3) { assertTrue(r.allow(OWNER, Command.RingFind, t + it)) }
        assertFalse(r.allow(OWNER, Command.RingFind, t + 9 * min))
        // Ten minutes after the first, one slot frees up.
        assertTrue(r.allow(OWNER, Command.RingFind, t + 10 * min + 1))
    }

    @Test fun refusedCommandsDontUseUpTheLimit() {
        val r = RateLimiter()
        repeat(3) { r.allow(OWNER, Command.RingFind, t + it) }
        repeat(20) { assertFalse(r.allow(OWNER, Command.RingFind, t + min + it)) }
        assertTrue(r.allow(OWNER, Command.RingFind, t + 10 * min + 3))
    }

    @Test fun channelsAreLimitedSeparately() {
        val r = RateLimiter()
        repeat(3) { r.allow(FAMILY, Command.RingFind, t + it) }
        assertFalse(r.allow(FAMILY, Command.RingFind, t + 10))
        assertTrue(r.allow(OWNER, Command.RingFind, t + 10))
    }

    @Test fun launchesShareTenPerTenMinutesAcrossApps() {
        val r = RateLimiter()
        val apps = listOf("meet", "chrome", "whatsapp", "facebook", "muse")
        repeat(10) { assertTrue(r.allow(FAMILY, Command.Launch(apps[it % apps.size]), t + it)) }
        assertFalse(r.allow(FAMILY, Command.Launch("meet"), t + 20))
        // Rings have their own budget.
        assertTrue(r.allow(FAMILY, Command.RingFind, t + 21))
    }

    @Test fun statusAndPlaceholdersAreNotLimited() {
        val r = RateLimiter()
        repeat(50) {
            assertTrue(r.allow(OWNER, Command.StatusReport, t + it))
            assertTrue(r.allow(OWNER, Command.BootstrapRustdesk, t + it))
        }
    }
}
