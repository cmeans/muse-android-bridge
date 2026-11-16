package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(LOG_NOT_STARTED_REBOOT, d.logLine)
        assertFalse(d.markOwnerStopped)
    }

    @Test fun updateKeepsAnOwnerStoppedBridgeStopped() {
        val d = bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = true, wasRunning = true)
        assertFalse(d.start)
        assertEquals(LOG_NOT_STARTED_UPDATE, d.logLine)
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
        assertEquals(LOG_KEPT_OFF_AT_UPDATE, d.logLine)
        // Setup incomplete at that update: recorded the same way.
        assertTrue(bootStartFor(BootEvent.UPDATE, setupComplete = false, ownerStopped = null, wasRunning = false).markOwnerStopped)
        // Running at that update: restart it and record nothing.
        assertEquals(BootStart(true, null), bootStartFor(BootEvent.UPDATE, setupComplete = true, ownerStopped = null, wasRunning = true))
    }

    @Test fun incompleteSetupStartsNothingAndLogsNothing() {
        assertEquals(BootStart(false, null), bootStartFor(BootEvent.BOOT, setupComplete = false, ownerStopped = false, wasRunning = true))
        assertEquals(BootStart(false, null), bootStartFor(BootEvent.UPDATE, setupComplete = false, ownerStopped = false, wasRunning = true))
    }

    @Test fun aNeverWrittenPreferenceReadsAsNull() {
        // The receiver's read: the migration runs only for a preference that was never written.
        assertNull(ownerStoppedFrom(contains = false, value = false))
        assertEquals(false, ownerStoppedFrom(contains = true, value = false))
        assertEquals(true, ownerStoppedFrom(contains = true, value = true))
    }

    @Test fun everyBootLogLineShowsInRecent() {
        for (line in listOf(LOG_STOP_TAPPED, LOG_NOT_STARTED_REBOOT, LOG_NOT_STARTED_UPDATE, LOG_KEPT_OFF_AT_UPDATE)) {
            assertNotNull(line, recentItemFor("2026-11-15 09:41:07 | $line"))
        }
    }
}
