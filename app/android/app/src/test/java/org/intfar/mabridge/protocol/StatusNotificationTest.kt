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

    @Test fun allStoppedWinsAndOffersNoButtons() {
        for (family in listOf(false, true)) {
            val s = statusNotificationFor(StopState(all = true, family = family))
            assertEquals("All commands stopped. Tap to open.", s.text)
            assertEquals(emptyList<StopButton>(), s.buttons)
        }
    }
}
