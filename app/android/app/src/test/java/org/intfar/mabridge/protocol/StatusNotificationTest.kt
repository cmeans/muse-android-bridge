package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusNotificationTest {
    @Test fun nothingStopped() {
        val s = statusNotificationFor(StopState(all = false, family = false))
        assertEquals("Listening for commands. Tap to open.", s.text)
        assertEquals(listOf(StopButton.FAMILY, StopButton.ALL), s.buttons)
    }

    @Test fun familyStopped() {
        val s = statusNotificationFor(StopState(all = false, family = true))
        assertEquals("Listening for owner commands; family stopped. Tap to open.", s.text)
        assertEquals(listOf(StopButton.ALL), s.buttons)
    }

    @Test fun eachButtonTurnsOnItsOwnSwitch() {
        assertEquals("Stop family", StopButton.FAMILY.label)
        assertEquals(PREF_STOP_FAMILY, StopButton.FAMILY.prefKey)
        assertEquals("STOP family", StopButton.FAMILY.switchName)
        assertEquals("Stop all", StopButton.ALL.label)
        assertEquals(PREF_STOP_ALL, StopButton.ALL.prefKey)
        assertEquals("STOP all", StopButton.ALL.switchName)
        assertEquals("stop_family", PREF_STOP_FAMILY)
        assertEquals("stop_all", PREF_STOP_ALL)
    }

    @Test fun actionsMapBackToTheirButtonOnly() {
        for (b in StopButton.entries) assertEquals(b, StopButton.forAction(b.action))
        assertEquals(2, StopButton.entries.map { it.action }.toSet().size)
        assertEquals(null, StopButton.forAction(null))
        assertEquals(null, StopButton.forAction("org.intfar.mabridge.STOP_RING"))
        assertEquals(null, StopButton.forAction("org.intfar.mabridge.REPOST_STATUS"))
    }

    @Test fun bothSwitchesRepostTheNotification() {
        assertEquals(setOf("stop_all", "stop_family"), STOP_PREF_KEYS)
    }

    @Test fun allStoppedWinsAndOffersNoButtons() {
        for (family in listOf(false, true)) {
            val s = statusNotificationFor(StopState(all = true, family = family))
            assertEquals("All commands stopped. Tap to open.", s.text)
            assertEquals(emptyList<StopButton>(), s.buttons)
        }
    }
}
