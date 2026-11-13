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

    @Test fun topicMustMatchExactly() {
        assertNull(channelForTopic("mab-test-phone-owner-x", "test-phone"))
        assertNull(channelForTopic("mab-test-phone-owne", "test-phone"))
        assertNull(channelForTopic("xmab-test-phone-family", "test-phone"))
    }

    @Test fun pilotRegistryMatchesVerifiedPackages() {
        // Verified on the pilot phone over adb, 2026-11-12.
        assertEquals(
            mapOf(
                "meet" to App("Meet", "com.google.android.apps.tachyon"),
                "whatsapp" to App("WhatsApp", "com.whatsapp"),
                "facebook" to App("Facebook", "com.facebook.katana"),
                "chrome" to App("Chrome", "com.android.chrome"),
                "muse" to App("Muse", "com.facebook.aura"),
                "rustdesk" to App("RustDesk", "com.carriez.flutter_hbb"),
            ),
            PILOT_REGISTRY.apps,
        )
    }

    @Test fun topicsPerChannel() {
        assertEquals("mab-test-phone-owner", OWNER.topic("test-phone"))
        assertEquals("mab-test-phone-family", FAMILY.topic("test-phone"))
        assertEquals(OWNER, channelForTopic("mab-test-phone-owner", "test-phone"))
        assertEquals(FAMILY, channelForTopic("mab-test-phone-family", "test-phone"))
        assertNull(channelForTopic("mab-test-phone-ack", "test-phone"))
        assertNull(channelForTopic("mab-other-owner", "test-phone"))
    }
}
