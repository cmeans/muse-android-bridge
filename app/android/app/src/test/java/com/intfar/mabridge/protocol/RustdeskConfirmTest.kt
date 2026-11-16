package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The confirm decisions for family RustDesk launches (design §4-§5). */
class RustdeskConfirmTest {
    private val pending = PendingConfirm(id = "Ab12Cd34Ef56", member = "m1", deadlineMs = 1_000_000L)
    private val owner = Source(Channel.OWNER, null)
    private val family = Source(Channel.FAMILY, FamilyMember("m1", "Alex"))

    @Test fun onlyFamilyRustdeskNeedsConfirm() {
        assertFalse(needsConfirm(Command.Launch("rustdesk"), owner))
        assertFalse(needsConfirm(Command.Launch("meet"), family))
        assertTrue(needsConfirm(Command.Launch("rustdesk"), family))
    }

    @Test fun firstRequestStarts() {
        assertEquals(PendingConfirm("x1", "m1", 5_000L + 120_000L), onConfirmRequest(null, "x1", "m1", 5_000L))
        assertEquals(120_000L, CONFIRM_TIMEOUT_MS)
    }

    @Test fun secondRequestWhilePendingRefused() {
        assertNull(onConfirmRequest(pending, "x2", "m2", 5_000L))
    }

    @Test fun openLaunches() {
        assertEquals(ConfirmOutcome.Launch, confirmOutcome(pending, ConfirmEvent.OPEN, pending.id))
    }

    @Test fun notNowIsDeclined() {
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk declined", "declined (family/m1): launch rustdesk"),
            confirmOutcome(pending, ConfirmEvent.NOT_NOW, pending.id),
        )
    }

    @Test fun timeoutIsNotConfirmed() {
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk not-confirmed", "not-confirmed (family/m1): launch rustdesk (timeout)"),
            confirmOutcome(pending, ConfirmEvent.TIMEOUT, pending.id),
        )
    }

    @Test fun restartIsNotConfirmed() {
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk not-confirmed", "not-confirmed (family/m1): launch rustdesk (restart)"),
            confirmOutcome(pending, ConfirmEvent.RESTARTED, pending.id),
        )
    }

    @Test fun stopIsDisabled() {
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk disabled", "disabled (family/m1): launch rustdesk (while waiting)"),
            confirmOutcome(pending, ConfirmEvent.STOPPED, pending.id),
        )
    }

    @Test fun actionForOtherIdIsIgnored() {
        ConfirmEvent.values().forEach { assertNull(confirmOutcome(pending, it, "other")) }
    }

    @Test fun actionWithNothingPendingIsIgnored() {
        ConfirmEvent.values().forEach { assertNull(confirmOutcome(null, it, "Ab12Cd34Ef56")) }
    }

    @Test fun expiredPendingCountsAsTimeout() {
        assertTrue(isExpired(pending, 1_000_000L))
        assertTrue(isExpired(pending, 1_000_001L))
        assertFalse(isExpired(pending, 999_999L))
    }
}
