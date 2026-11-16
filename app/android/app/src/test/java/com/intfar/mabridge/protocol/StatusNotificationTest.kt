package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusNotificationTest {
    @Test fun nothingStopped() {
        val s = statusNotificationFor(StopState(all = false, family = false), "ntfy.example.com")
        assertEquals("Listening for your Muse and family on ntfy.example.com. Tap to open.", s.text)
        assertEquals(listOf(StopButton.FAMILY, StopButton.ALL), s.buttons)
    }

    @Test fun familyStopped() {
        val s = statusNotificationFor(StopState(all = false, family = true), "ntfy.example.com")
        assertEquals("Listening for your Muse on ntfy.example.com; family stopped. Tap to open.", s.text)
        assertEquals(listOf(StopButton.ALL), s.buttons)
    }

    @Test fun eachButtonTurnsOnItsOwnSwitch() {
        assertEquals("Stop family", StopButton.FAMILY.label)
        assertEquals(PREF_STOP_FAMILY, StopButton.FAMILY.prefKey)
        assertEquals("STOP family", StopButton.FAMILY.switchName)
        assertEquals("Stop everything", StopButton.ALL.label)
        assertEquals(PREF_STOP_ALL, StopButton.ALL.prefKey)
        assertEquals("STOP all", StopButton.ALL.switchName)
        assertEquals("stop_family", PREF_STOP_FAMILY)
        assertEquals("stop_all", PREF_STOP_ALL)
    }

    @Test fun actionsMapBackToTheirButtonOnly() {
        for (b in StopButton.entries) assertEquals(b, StopButton.forAction(b.action))
        assertEquals(2, StopButton.entries.map { it.action }.toSet().size)
        assertEquals(null, StopButton.forAction(null))
        assertEquals(null, StopButton.forAction("com.intfar.mabridge.STOP_RING"))
        assertEquals(null, StopButton.forAction("com.intfar.mabridge.REPOST_STATUS"))
    }

    @Test fun bothSwitchesRepostTheNotification() {
        assertEquals(setOf("stop_all", "stop_family"), STOP_PREF_KEYS)
    }

    @Test fun allStoppedWinsAndOffersNoButtons() {
        for (family in listOf(false, true)) {
            val s = statusNotificationFor(StopState(all = true, family = family), "ntfy.example.com")
            assertEquals("Everything stopped. Tap to open.", s.text)
            assertEquals(emptyList<StopButton>(), s.buttons)
        }
    }

    @Test fun stopFamilyTapTurnsOnFamilyAndLeavesTheRing() {
        val t = stopTapFor(StopButton.FAMILY.action, StopState(all = false, family = false))!!
        assertEquals(StopButton.FAMILY, t.button)
        assertEquals(false, t.stopsRinging)
        assertEquals("STOP family switched on from the notification", t.logLine)
    }

    @Test fun stopAllTapTurnsOnAllAndEndsTheRing() {
        val t = stopTapFor(StopButton.ALL.action, StopState(all = false, family = true))!!
        assertEquals(StopButton.ALL, t.button)
        assertEquals(true, t.stopsRinging)
        assertEquals("STOP all switched on from the notification", t.logLine)
    }

    @Test fun aTapOnASwitchAlreadyOnSaysSo() {
        assertEquals(
            "STOP family tapped on the notification; it was already on",
            stopTapFor(StopButton.FAMILY.action, StopState(all = false, family = true))!!.logLine,
        )
        assertEquals(
            "STOP all tapped on the notification; it was already on",
            stopTapFor(StopButton.ALL.action, StopState(all = true, family = false))!!.logLine,
        )
        // Each button reads only its own switch.
        assertEquals(
            "STOP family switched on from the notification",
            stopTapFor(StopButton.FAMILY.action, StopState(all = true, family = false))!!.logLine,
        )
        assertEquals(
            "STOP all switched on from the notification",
            stopTapFor(StopButton.ALL.action, StopState(all = false, family = true))!!.logLine,
        )
    }

    @Test fun otherActionsAreNotTaps() {
        val none = StopState(all = false, family = false)
        assertEquals(null, stopTapFor(null, none))
        assertEquals(null, stopTapFor("com.intfar.mabridge.STOP_RING", none))
        assertEquals(null, stopTapFor("com.intfar.mabridge.REPOST_STATUS", none))
    }
}
