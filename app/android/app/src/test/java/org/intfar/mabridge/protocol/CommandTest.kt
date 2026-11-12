package org.intfar.mabridge.protocol

import org.intfar.mabridge.protocol.Channel.FAMILY
import org.intfar.mabridge.protocol.Channel.OWNER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandTest {
    private val reg = Registry(
        mapOf(
            "meet" to App("Meet", "com.example.meet"),
            "rustdesk" to App("RustDesk", "com.example.rustdesk"),
        )
    )

    @Test fun launchOnBothChannels() {
        assertEquals(Command.Launch("meet"), parseCommand("launch meet", OWNER, reg))
        assertEquals(Command.Launch("meet"), parseCommand("launch meet", FAMILY, reg))
    }

    @Test fun ringFindOnBothChannels() {
        assertEquals(Command.RingFind, parseCommand("ring find", OWNER, reg))
        assertEquals(Command.RingFind, parseCommand("ring find", FAMILY, reg))
    }

    @Test fun statusReportOwnerOnly() {
        assertEquals(Command.StatusReport, parseCommand("status report", OWNER, reg))
        assertNull(parseCommand("status report", FAMILY, reg))
    }

    @Test fun rustdeskCommandsOwnerOnly() {
        assertEquals(Command.BootstrapRustdesk, parseCommand("bootstrap rustdesk", OWNER, reg))
        assertEquals(Command.UpdateRustdesk, parseCommand("update rustdesk", OWNER, reg))
        assertNull(parseCommand("bootstrap rustdesk", FAMILY, reg))
        assertNull(parseCommand("update rustdesk", FAMILY, reg))
    }

    @Test fun canonicalText() {
        assertEquals("launch meet", Command.Launch("meet").text)
        assertEquals("ring find", Command.RingFind.text)
        assertEquals("status report", Command.StatusReport.text)
        assertEquals("bootstrap rustdesk", Command.BootstrapRustdesk.text)
        assertEquals("update rustdesk", Command.UpdateRustdesk.text)
    }

    @Test fun nearMissesRejected() {
        val bodies = listOf(
            "Launch meet", " launch meet", "launch meet ", "launch  meet", "launch\tmeet",
            "launch meet\n", "launch", "launch zoom", "launch meet now", "", "launch méet",
            "launch " + "a".repeat(10_000), "ring", "ring find please",
        )
        for (ch in Channel.values()) for (b in bodies) {
            assertNull("accepted ${b.take(20)} on $ch", parseCommand(b, ch, reg))
        }
    }

    @Test fun topicsPerChannel() {
        assertEquals("mab-chris-razr-owner", OWNER.topic("chris-razr"))
        assertEquals("mab-chris-razr-family", FAMILY.topic("chris-razr"))
        assertEquals(OWNER, channelForTopic("mab-chris-razr-owner", "chris-razr"))
        assertEquals(FAMILY, channelForTopic("mab-chris-razr-family", "chris-razr"))
        assertNull(channelForTopic("mab-chris-razr-ack", "chris-razr"))
        assertNull(channelForTopic("mab-other-owner", "chris-razr"))
    }
}
