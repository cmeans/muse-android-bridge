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

    @Test fun openBeforeTheDeadlineLaunches() {
        assertEquals(ConfirmEvent.OPEN, effectiveEvent(pending, ConfirmEvent.OPEN, pending.deadlineMs - 1))
        assertEquals(
            ConfirmOutcome.Launch,
            confirmOutcome(pending, effectiveEvent(pending, ConfirmEvent.OPEN, pending.deadlineMs - 1), pending.id),
        )
        assertEquals(ConfirmEvent.NOT_NOW, effectiveEvent(pending, ConfirmEvent.NOT_NOW, pending.deadlineMs - 1))
    }

    @Test fun openAtTheDeadlineIsNotConfirmed() {
        // The timer runs on the uptime clock and stops in deep sleep: a late tap mustn't launch.
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk not-confirmed", "not-confirmed (family/m1): launch rustdesk (timeout)"),
            confirmOutcome(pending, effectiveEvent(pending, ConfirmEvent.OPEN, pending.deadlineMs), pending.id),
        )
    }

    @Test fun notNowAtTheDeadlineIsNotConfirmed() {
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk not-confirmed", "not-confirmed (family/m1): launch rustdesk (timeout)"),
            confirmOutcome(pending, effectiveEvent(pending, ConfirmEvent.NOT_NOW, pending.deadlineMs + 5_000), pending.id),
        )
    }

    @Test fun stopPastTheDeadlineIsNotConfirmed() {
        // A late answer is not-confirmed whatever the event (PROTOCOL §5 c, d): STOP before the
        // deadline withdraws the request as disabled, STOP after it (the timer slept) times out.
        assertEquals(ConfirmEvent.STOPPED, effectiveEvent(pending, ConfirmEvent.STOPPED, pending.deadlineMs - 1))
        for (now in listOf(pending.deadlineMs, pending.deadlineMs + 5_000)) {
            assertEquals(ConfirmEvent.TIMEOUT, effectiveEvent(pending, ConfirmEvent.STOPPED, now))
        }
        assertEquals(
            ConfirmOutcome.Answer("err launch rustdesk not-confirmed", "not-confirmed (family/m1): launch rustdesk (timeout)"),
            confirmOutcome(pending, effectiveEvent(pending, ConfirmEvent.STOPPED, pending.deadlineMs), pending.id),
        )
    }

    @Test fun deadlineDoesNotChangeOtherEvents() {
        // RESTARTED already answers not-confirmed, with its own log reason.
        for (e in listOf(ConfirmEvent.TIMEOUT, ConfirmEvent.RESTARTED)) {
            for (now in listOf(pending.deadlineMs - 1, pending.deadlineMs, pending.deadlineMs + 1)) {
                assertEquals(e, effectiveEvent(pending, e, now))
            }
        }
    }

    @Test fun effectiveEventWithNothingPendingIsUnchanged() {
        ConfirmEvent.values().forEach { assertEquals(it, effectiveEvent(null, it, 0L)) }
    }

    @Test fun expiredPendingCountsAsTimeout() {
        assertTrue(isExpired(pending, 1_000_000L))
        assertTrue(isExpired(pending, 1_000_001L))
        assertFalse(isExpired(pending, 999_999L))
    }

    // QA r1: a request waiting across a server or device change is answered by the save, on the
    // old server and ack topic, before the new ones are written.
    private val setup = Setup("https://ntfy.example.com", "p", "p-phone", listOf(FamilyMember("m1", "Alex")))
    private val setupChanged = ConfirmOutcome.Answer(
        "err launch rustdesk not-confirmed",
        "not-confirmed (family/m1): launch rustdesk (setup changed)",
    )

    @Test fun setupChangeIsNotConfirmed() {
        assertEquals(setupChanged, confirmOutcome(pending, ConfirmEvent.SETUP_CHANGED, pending.id))
    }

    @Test fun serverOrDeviceChangeAnswersTheWaitingRequest() {
        assertEquals(setupChanged, setupSaveAnswer(pending, setup, setup.copy(server = "https://other.example.com")))
        assertEquals(setupChanged, setupSaveAnswer(pending, setup, setup.copy(slug = "q")))
        // Past its deadline it's still not-confirmed, with the setup-change reason.
        assertEquals(ConfirmEvent.SETUP_CHANGED, effectiveEvent(pending, ConfirmEvent.SETUP_CHANGED, pending.deadlineMs + 1))
    }

    @Test fun otherSetupChangesLeaveItToTheRestart() {
        // Same server and ack topic: the restart after every save answers it as RESTARTED.
        assertNull(setupSaveAnswer(pending, setup, setup.copy(username = "other")))
        assertNull(setupSaveAnswer(pending, setup, setup.copy(members = listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "Bo")))))
        assertNull(setupSaveAnswer(pending, setup, setup))
    }

    @Test fun setupChangeWithNothingWaitingAnswersNothing() {
        assertNull(setupSaveAnswer(null, setup, setup.copy(server = "https://other.example.com")))
    }

    @Test fun destinationChangeIsServerOrDevice() {
        assertTrue(ackDestinationChanged(setup, setup.copy(server = "https://other.example.com")))
        assertTrue(ackDestinationChanged(setup, setup.copy(slug = "q")))
        assertFalse(ackDestinationChanged(setup, setup.copy(username = "other", members = emptyList())))
    }

    // #33 item 1: STOP is read again under the confirm lock, so a STOP that lands between the
    // gate and the prompt answers `disabled` and stores nothing.
    @Test fun stopReadUnderTheLockIsDisabled() {
        val off = StopState(all = false, family = false)
        for (stop in listOf(StopState(all = false, family = true), StopState(all = true, family = false))) {
            assertEquals(ConfirmRequest.Disabled, confirmRequestFor(stop, null, "x1", "m1", 5_000L))
            // STOP wins over a waiting request too: the listener's STOPPED answers that one.
            assertEquals(ConfirmRequest.Disabled, confirmRequestFor(stop, pending, "x2", "m2", 5_000L))
        }
        assertEquals(ConfirmRequest.Busy, confirmRequestFor(off, pending, "x2", "m2", 5_000L))
        assertEquals(
            ConfirmRequest.Ask(PendingConfirm("x1", "m1", 5_000L + CONFIRM_TIMEOUT_MS)),
            confirmRequestFor(off, null, "x1", "m1", 5_000L),
        )
    }
}
