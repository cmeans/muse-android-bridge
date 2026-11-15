package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartTest {
    @Test fun rebootStartsABridgeTheOwnerDidNotStop() {
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.BOOT, setupComplete = true, ownerStopped = false, wasRunning = true))
        // Running or not before the reboot doesn't matter: a reboot ends the service without onDestroy.
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.BOOT, setupComplete = true, ownerStopped = false, wasRunning = false))
        // Builds before the preference existed: a reboot starts it, as it always did.
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.BOOT, setupComplete = true, ownerStopped = null, wasRunning = false))
    }

    @Test fun rebootKeepsAnOwnerStoppedBridgeStoppedAndLogsIt() {
        val d = bootStartFor(BootEvent.BOOT, setupComplete = true, ownerStopped = true, wasRunning = true)
        assertFalse(d.start)
        assertEquals("not started after a reboot: the bridge was stopped on the phone", d.logLine)
        assertFalse(d.markOwnerStopped)
    }

    @Test fun updateKeepsAnOwnerStoppedBridgeStopped() {
        val d = bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = true, wasRunning = true)
        assertFalse(d.start)
        assertEquals("not started after an update: the bridge was stopped on the phone", d.logLine)
    }

    @Test fun updateRestartsOnlyARunningBridge() {
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = false, wasRunning = true))
        assertEquals(BootStart(false, null), bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = false, wasRunning = false))
    }

    @Test fun firstUpdateRecordsAStoppedBridgeAsOwnerStopped() {
        // Installed over a build without the preference, with the bridge off: the next reboot must not start it.
        val d = bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = null, wasRunning = false)
        assertFalse(d.start)
        assertTrue(d.markOwnerStopped)
        assertNull(d.logLine)
        // Running at that update: restart it and record nothing.
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = null, wasRunning = true))
    }

    @Test fun incompleteSetupStartsNothingAndLogsNothing() {
        assertEquals(BootStart(false, null), bootStartFor(BootEvent.BOOT, setupComplete = false, ownerStopped = false, wasRunning = true))
        assertEquals(BootStart(false, null), bootStartFor(BootEvent.UPDATE, setupComplete = false, ownerStopped = false, wasRunning = true))
    }
}
