package org.intfar.mabridge.protocol

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
    private val gate = Gate("chris-razr", PILOT_REGISTRY, InMemorySeenStore(), { clock }, { stop })

    private var nextId = 0
    private fun msg(
        body: String,
        topic: String = "mab-chris-razr-family",
        timeSec: Long = nowSec,
        id: String = "id${nextId++}",
    ) = IncomingMessage(id = id, timeSec = timeSec, topic = topic, body = body)

    private val owner = "mab-chris-razr-owner"

    @Test fun staleByAge() {
        val d = gate.decide(msg("launch meet", timeSec = nowSec - 121))
        assertTrue(d is Decision.DropStale)
        assertEquals("dropped-stale launch meet", (d as Decision.DropStale).receipt)
    }

    @Test fun freshAtBoundary() {
        assertEquals(
            Decision.Run(Command.Launch("meet"), Channel.FAMILY),
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

    @Test fun duplicateDropped() {
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.Run)
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.DropDuplicate)
    }

    @Test fun duplicateWindowExpires() {
        assertTrue(gate.decide(msg("launch meet", id = "same")) is Decision.Run)
        clock = t + day + 1
        assertTrue(gate.decide(msg("launch meet", id = "same", timeSec = clock / 1000)) is Decision.Run)
    }

    @Test fun stopFamilyRejectsFamily() {
        stop = StopState(all = false, family = true)
        assertEquals(Decision.Reject("err launch meet disabled"), stripLog(gate.decide(msg("launch meet"))))
        assertEquals(
            Decision.Run(Command.Launch("meet"), Channel.OWNER),
            gate.decide(msg("launch meet", topic = owner)),
        )
    }

    @Test fun stopAllRejectsBoth() {
        stop = StopState(all = true, family = false)
        assertEquals(Decision.Reject("err launch meet disabled"), stripLog(gate.decide(msg("launch meet"))))
        assertEquals(
            Decision.Reject("err status report disabled"),
            stripLog(gate.decide(msg("status report", topic = owner))),
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
        assertEquals(Decision.Reject("err unknown-command"), stripLog(gate.decide(msg("status report"))))
    }

    @Test fun wrongTopicIgnored() {
        assertTrue(gate.decide(msg("launch meet", topic = "mab-chris-razr-ack")) is Decision.Ignore)
        assertTrue(gate.decide(msg("launch meet", topic = "mab-other-owner")) is Decision.Ignore)
    }

    @Test fun blankIdIgnored() {
        assertTrue(gate.decide(msg("launch meet", id = "")) is Decision.Ignore)
        assertTrue(gate.decide(msg("launch meet", id = "   ")) is Decision.Ignore)
    }

    @Test fun rustdeskNotImplemented() {
        assertEquals(
            Decision.Run(Command.BootstrapRustdesk, Channel.OWNER),
            gate.decide(msg("bootstrap rustdesk", topic = owner)),
        )
        assertEquals("err bootstrap rustdesk not-implemented", Receipts.notImplemented(Command.BootstrapRustdesk))
    }

    @Test fun receiptWording() {
        assertEquals("ok launch meet", Receipts.ok(Command.Launch("meet")))
        assertEquals("err launch meet not-installed", Receipts.notInstalled(Command.Launch("meet")))
        assertEquals("err unknown-command", Receipts.unknown())
    }

    @Test fun statusReceipt() {
        assertEquals(
            "ok status report battery=87 version=0.1.0 protocol=0.2 owner=on family=off",
            Receipts.status(87, "0.1.0", "0.2", StopState(all = false, family = true)),
        )
        assertEquals(
            "ok status report battery=5 version=0.1.0 protocol=0.2 owner=off family=off",
            Receipts.status(5, "0.1.0", "0.2", StopState(all = true, family = false)),
        )
    }

    /** Reject with its log line dropped, to compare receipts alone. */
    private fun stripLog(d: Decision): Decision =
        if (d is Decision.Reject) Decision.Reject(d.receipt) else d
}
