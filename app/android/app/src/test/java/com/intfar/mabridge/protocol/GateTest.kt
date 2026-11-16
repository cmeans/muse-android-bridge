package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
    private val t = 1_800_000_000_000L
    private val nowSec = t / 1000
    private val day = 24L * 60 * 60 * 1000
    private var clock = t
    private var stop = StopState(all = false, family = false)
    private val gate = Gate("test-phone", PILOT_REGISTRY, InMemorySeenStore(), { clock }, { stop }, { members })

    private val m1 = FamilyMember("m1", "Alex")
    private val m2 = FamilyMember("m2", "Robin")
    private var members = listOf(m1, m2)
    private var nextId = 0
    private fun msg(
        body: String,
        topic: String = "mab-test-phone-family-m1",
        timeSec: Long = nowSec,
        id: String = "id${nextId++}",
    ) = IncomingMessage(id = id, timeSec = timeSec, topic = topic, body = body)

    private val owner = "mab-test-phone-owner"

    @Test fun staleByAge() {
        val d = gate.decide(msg("launch meet", timeSec = nowSec - 121))
        assertTrue(d is Decision.DropStale)
        assertEquals("dropped-stale launch meet", (d as Decision.DropStale).receipt)
    }

    @Test fun freshAtBoundary() {
        assertEquals(
            Decision.Run(Command.Launch("meet"), Source(Channel.FAMILY, m1)),
            gate.decide(msg("launch meet", timeSec = nowSec - 120)),
        )
        assertTrue(gate.decide(msg("launch meet", timeSec = nowSec + 120)) is Decision.Run)
    }

    @Test fun futureDatedIsStale() {
        assertTrue(gate.decide(msg("launch meet", timeSec = nowSec + 121)) is Decision.DropStale)
    }

    @Test fun staleJunkHasBareReceipt() {
        val d = gate.decide(msg("rm -rf /", timeSec = nowSec - 500)) as Decision.DropStale
        assertEquals("dropped-stale", d.receipt)
    }

    @Test fun staleOwnerOnlyCommandOnFamilyHasBareReceipt() {
        val d = gate.decide(msg("status report", timeSec = nowSec - 500)) as Decision.DropStale
        assertEquals("dropped-stale", d.receipt)
    }

    @Test fun extremeTimestampIsStale() {
        assertTrue(gate.decide(msg("launch meet", timeSec = nowSec + Long.MIN_VALUE)) is Decision.DropStale)
        assertTrue(gate.decide(msg("launch meet", timeSec = Long.MAX_VALUE)) is Decision.DropStale)
    }

    @Test fun duplicateAtWindowEdgeStillDropped() {
        assertTrue(gate.decide(msg("launch meet", id = "edge")) is Decision.Run)
        clock = t + day
        assertTrue(gate.decide(msg("launch meet", id = "edge", timeSec = clock / 1000)) is Decision.DropDuplicate)
    }

    @Test fun duplicateDropped() {
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.Run)
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.DropDuplicate)
    }

    @Test fun staleRedeliveryOfAlreadyRunMessageIsDuplicate() {
        // A catch-up replay brings back a message that already ran, now older than 120 s:
        // it must not get a dropped-stale receipt that contradicts its earlier ok.
        assertTrue(gate.decide(msg("launch meet", id = "ran", timeSec = nowSec)) is Decision.Run)
        clock = t + 300_000
        assertTrue(gate.decide(msg("launch meet", id = "ran", timeSec = nowSec)) is Decision.DropDuplicate)
    }

    @Test fun staleNewMessageStillGetsStaleReceipt() {
        clock = t + 300_000
        assertTrue(gate.decide(msg("launch meet", id = "neverSeen", timeSec = nowSec)) is Decision.DropStale)
    }

    @Test fun secondStaleCopyIsDuplicate() {
        assertTrue(gate.decide(msg("launch meet", id = "s1", timeSec = nowSec - 500)) is Decision.DropStale)
        assertTrue(gate.decide(msg("launch meet", id = "s1", timeSec = nowSec - 500)) is Decision.DropDuplicate)
    }

    @Test fun futureDatedThenFreshCopyDoesNotRun() {
        // Dropped while the phone's clock was behind; redelivered after it's corrected.
        assertTrue(gate.decide(msg("launch meet", id = "f1", timeSec = nowSec + 600)) is Decision.DropStale)
        clock = t + 600_000
        assertTrue(gate.decide(msg("launch meet", id = "f1", timeSec = nowSec + 600)) is Decision.DropDuplicate)
    }

    @Test fun duplicateWindowExpires() {
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.Run)
        clock = t + day + 1
        assertTrue(gate.decide(msg("launch meet", id = "same", timeSec = clock / 1000)) is Decision.Run)
    }

    // ---- atRunLock: the re-check after waiting for the run lock (#33 item 21) ----

    @Test fun atRunLockRunsWhenRunningAndNothingStopped() {
        val none = StopState(all = false, family = false)
        assertEquals(RunLockCheck.RUN, atRunLock(running = true, stop = none, channel = Channel.OWNER))
        assertEquals(RunLockCheck.RUN, atRunLock(running = true, stop = none, channel = Channel.FAMILY))
    }

    @Test fun atRunLockStopFamilyDisablesOnlyFamily() {
        val fam = StopState(all = false, family = true)
        assertEquals(RunLockCheck.DISABLED, atRunLock(running = true, stop = fam, channel = Channel.FAMILY))
        assertEquals(RunLockCheck.RUN, atRunLock(running = true, stop = fam, channel = Channel.OWNER))
    }

    @Test fun atRunLockStopAllDisablesBothChannels() {
        val all = StopState(all = true, family = false)
        assertEquals(RunLockCheck.DISABLED, atRunLock(running = true, stop = all, channel = Channel.OWNER))
        assertEquals(RunLockCheck.DISABLED, atRunLock(running = true, stop = all, channel = Channel.FAMILY))
    }

    @Test fun atRunLockStoppedBridgeWinsOverStop() {
        for (all in listOf(false, true)) for (family in listOf(false, true)) for (ch in Channel.values()) {
            assertEquals(RunLockCheck.STOPPED, atRunLock(running = false, stop = StopState(all, family), channel = ch))
        }
    }

    @Test fun atRunLockMatchesTheGatesStopRule() {
        // The re-check and the gate must agree on what STOP turns off.
        for (all in listOf(false, true)) for (family in listOf(false, true)) {
            stop = StopState(all, family)
            for ((ch, topic) in listOf(Channel.OWNER to "mab-test-phone-owner", Channel.FAMILY to "mab-test-phone-family-m1")) {
                val gateRefuses = gate.decide(msg("launch meet", topic = topic)) is Decision.Reject
                assertEquals("all=$all family=$family $ch", gateRefuses, atRunLock(true, stop, ch) == RunLockCheck.DISABLED)
            }
        }
    }

    @Test fun stopFamilyRejectsFamily() {
        stop = StopState(all = false, family = true)
        assertEquals(Decision.Reject("err launch meet disabled", "r1"), stripLog(gate.decide(msg("launch meet", id = "r1"))))
        assertEquals(
            Decision.Run(Command.Launch("meet"), Source(Channel.OWNER, null)),
            gate.decide(msg("launch meet", topic = owner)),
        )
    }

    @Test fun familyRunCarriesMember() {
        assertEquals(
            Decision.Run(Command.Launch("meet"), Source(Channel.FAMILY, m1)),
            gate.decide(msg("launch meet")),
        )
    }

    @Test fun sharedFamilyTopicIgnored() {
        val d = gate.decide(msg("launch meet", topic = "mab-test-phone-family")) as Decision.Ignore
        assertEquals("ignored: topic mab-test-phone-family: launch meet", d.log)
    }

    @Test fun stopFamilyCoversEveryMember() {
        stop = StopState(all = false, family = true)
        val d1 = gate.decide(msg("launch meet", id = "a1")) as Decision.Reject
        assertEquals("err launch meet disabled", d1.receipt)
        assertEquals("disabled (family/m1): launch meet", d1.log)
        val d2 = gate.decide(msg("launch meet", topic = "mab-test-phone-family-m2", id = "a2")) as Decision.Reject
        assertEquals("err launch meet disabled", d2.receipt)
        assertEquals("disabled (family/m2): launch meet", d2.log)
    }

    @Test fun duplicateLogNamesTheMemberSlug() {
        gate.decide(msg("launch meet", id = "dup"))
        val d = gate.decide(msg("launch meet", id = "dup")) as Decision.DropDuplicate
        assertEquals("duplicate (family/m1) dup: launch meet", d.log)
    }

    @Test fun membersAreReadOnEveryMessage() {
        val m2Topic = "mab-test-phone-family-m2"
        members = listOf(m1)
        assertTrue(gate.decide(msg("launch meet", topic = m2Topic)) is Decision.Ignore)
        members = listOf(m1, m2)
        assertEquals(
            Decision.Run(Command.Launch("meet"), Source(Channel.FAMILY, m2)),
            gate.decide(msg("launch meet", topic = m2Topic)),
        )
        members = listOf(m1)
        assertTrue(gate.decide(msg("launch meet", topic = m2Topic)) is Decision.Ignore)
    }

    @Test fun stopAllRejectsBoth() {
        stop = StopState(all = true, family = false)
        assertEquals(Decision.Reject("err launch meet disabled", "r2"), stripLog(gate.decide(msg("launch meet", id = "r2"))))
        assertEquals(
            Decision.Reject("err status report disabled", "r3"),
            stripLog(gate.decide(msg("status report", topic = owner, id = "r3"))),
        )
    }

    @Test fun stoppedMessageIsStillMarkedSeen() {
        stop = StopState(all = false, family = true)
        assertTrue(gate.decide(msg("launch meet", id = "x")) is Decision.Reject)
        stop = StopState(all = false, family = false)
        assertTrue(gate.decide(msg("launch meet", id = "x")) is Decision.DropDuplicate)
    }

    @Test fun unknownCommand() {
        val d = gate.decide(msg("launch meet xyzzy")) as Decision.Reject
        assertEquals("err unknown-command", d.receipt)
        assertFalse(d.receipt.contains("xyzzy"))
    }

    @Test fun ownerOnlyCommandOnFamilyIsUnknown() {
        assertEquals(Decision.Reject("err unknown-command", "r4"), stripLog(gate.decide(msg("status report", id = "r4"))))
    }

    @Test fun wrongTopicIgnored() {
        assertTrue(gate.decide(msg("launch meet", topic = "mab-test-phone-ack")) is Decision.Ignore)
        assertTrue(gate.decide(msg("launch meet", topic = "mab-other-owner")) is Decision.Ignore)
    }

    @Test fun blankIdIgnored() {
        assertTrue(gate.decide(msg("launch meet", id = "")) is Decision.Ignore)
        assertTrue(gate.decide(msg("launch meet", id = "   ")) is Decision.Ignore)
    }

    @Test fun idOutsideGrammarIgnored() {
        listOf("ab cd", "ab=cd", "a".repeat(33), "abé").forEach {
            assertTrue(it, gate.decide(msg("launch meet", topic = owner, id = it)) is Decision.Ignore)
        }
    }

    @Test fun staleReceiptCarriesItsOwnId() {
        val d = gate.decide(msg("launch meet", timeSec = nowSec - 121, id = "old1")) as Decision.DropStale
        assertEquals("old1", d.id)
        assertEquals("dropped-stale launch meet", d.receipt)
    }

    @Test fun rejectCarriesItsOwnId() {
        val d = gate.decide(msg("launch meet xyzzy", id = "junk1")) as Decision.Reject
        assertEquals("junk1", d.id)
    }

    @Test fun rustdeskNotImplemented() {
        assertEquals(
            Decision.Run(Command.BootstrapRustdesk, Source(Channel.OWNER, null)),
            gate.decide(msg("bootstrap rustdesk", topic = owner)),
        )
        assertEquals("err bootstrap rustdesk not-implemented", Receipts.notImplemented(Command.BootstrapRustdesk))
    }

    @Test fun failureReceipts() {
        assertEquals("err ring find failed", Receipts.failed(Command.RingFind))
        assertEquals("err launch meet cannot-announce", Receipts.cannotAnnounce(Command.Launch("meet")))
        assertEquals("err launch meet cannot-launch", Receipts.cannotLaunch(Command.Launch("meet")))
        assertEquals("err launch meet blocked", Receipts.blocked(Command.Launch("meet")))
        assertEquals("err ring find rate-limited", Receipts.rateLimited(Command.RingFind))
    }

    @Test fun receiptWording() {
        assertEquals("ok launch meet", Receipts.ok(Command.Launch("meet")))
        assertEquals("err launch meet not-installed", Receipts.notInstalled(Command.Launch("meet")))
        assertEquals("err unknown-command", Receipts.unknown())
    }

    @Test fun statusReceipt() {
        assertEquals(
            "ok status report battery=87 version=0.1.0 protocol=0.4 owner=on family=off launch-check=on time=1800000000",
            Receipts.status(87, "0.1.0", "0.4", StopState(all = false, family = true), launchCheck = true, nowMs = t),
        )
        assertEquals(
            "ok status report battery=5 version=0.1.0 protocol=0.4 owner=off family=off launch-check=off time=1800000000",
            Receipts.status(5, "0.1.0", "0.4", StopState(all = true, family = false), launchCheck = false, nowMs = t),
        )
    }

    /** Reject with its log line dropped, to compare receipts alone. */
    private fun stripLog(d: Decision): Decision =
        if (d is Decision.Reject) Decision.Reject(d.receipt, d.id) else d
}
