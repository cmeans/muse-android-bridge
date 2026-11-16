package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusNotificationTest {
    private val none = StopState(all = false, family = false)
    private val family = StopState(all = false, family = true)
    private val all = StopState(all = true, family = false)
    private val host = "ntfy.example.com"

    @Test fun nothingStopped() {
        val s = statusNotificationFor(none, host, members = 1, refusedNames = emptyList())
        assertEquals("Listening for your Muse and family on ntfy.example.com. Tap to open.", s.text)
        assertEquals(listOf(StopButton.FAMILY, StopButton.ALL), s.buttons)
    }

    @Test fun familyStopped() {
        val s = statusNotificationFor(family, host, members = 1, refusedNames = emptyList())
        assertEquals("Listening for your Muse on ntfy.example.com; family stopped. Tap to open.", s.text)
        assertEquals(listOf(StopButton.ALL), s.buttons)
    }

    @Test fun noMembersText() {
        val s = statusNotificationFor(none, host, members = 0, refusedNames = emptyList())
        assertEquals("Listening for your Muse on ntfy.example.com. Tap to open.", s.text)
        // Nothing to stop: no Stop family button.
        assertEquals(listOf(StopButton.ALL), s.buttons)
    }

    @Test fun noMembersFamilyStoppedKeepsItsText() {
        val s = statusNotificationFor(family, host, members = 0, refusedNames = emptyList())
        assertEquals("Listening for your Muse on ntfy.example.com; family stopped. Tap to open.", s.text)
        assertEquals(listOf(StopButton.ALL), s.buttons)
    }

    @Test fun noMembersAllStopped() {
        val s = statusNotificationFor(all, host, members = 0, refusedNames = emptyList())
        assertEquals("Everything stopped. Tap to open.", s.text)
        assertEquals(emptyList<StopButton>(), s.buttons)
    }

    @Test fun someMembersRefused() {
        val s = statusNotificationFor(none, host, members = 2, refusedNames = listOf("Alex"))
        assertEquals("Listening for your Muse and family on ntfy.example.com. Can't reach: Alex", s.text)
        assertEquals(listOf(StopButton.FAMILY, StopButton.ALL), s.buttons)
    }

    @Test fun allMembersRefused() {
        val s = statusNotificationFor(none, host, members = 2, refusedNames = listOf("Alex", "Helper B"))
        assertEquals("Listening for your Muse on ntfy.example.com. Can't reach: Alex, Helper B", s.text)
        assertEquals(listOf(StopButton.FAMILY, StopButton.ALL), s.buttons)
    }

    @Test fun oneMemberRefusedIsAllRefused() {
        val s = statusNotificationFor(none, host, members = 1, refusedNames = listOf("Alex"))
        assertEquals("Listening for your Muse on ntfy.example.com. Can't reach: Alex", s.text)
    }

    @Test fun familyStoppedWinsOverRefused() {
        for (refused in listOf(listOf("Alex"), listOf("Alex", "Helper B"))) {
            val s = statusNotificationFor(family, host, members = 2, refusedNames = refused)
            assertEquals("Listening for your Muse on ntfy.example.com; family stopped. Tap to open.", s.text)
            assertEquals(listOf(StopButton.ALL), s.buttons)
        }
    }

    @Test fun allStoppedWinsOverRefused() {
        for (refused in listOf(emptyList(), listOf("Alex"), listOf("Alex", "Helper B"))) {
            val s = statusNotificationFor(all, host, members = 2, refusedNames = refused)
            assertEquals("Everything stopped. Tap to open.", s.text)
            assertEquals(emptyList<StopButton>(), s.buttons)
        }
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
            val s = statusNotificationFor(StopState(all = true, family = family), host, members = 1, refusedNames = emptyList())
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
        assertEquals(null, stopTapFor(null, none))
        assertEquals(null, stopTapFor("com.intfar.mabridge.STOP_RING", none))
        assertEquals(null, stopTapFor("com.intfar.mabridge.REPOST_STATUS", none))
    }
}
