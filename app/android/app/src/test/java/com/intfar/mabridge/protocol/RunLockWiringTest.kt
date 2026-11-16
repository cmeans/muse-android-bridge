package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #33 item 21: every command that runs goes through runIfStillAllowed, which asks atRunLock
 * after the run lock is taken. BridgeService needs a running Android service, so this reads its
 * source instead: a new direct call to run(...) would skip the STOP re-check.
 */
class RunLockWiringTest {
    // Unit tests run with the module directory as the working directory.
    private val src = File("src/main/java/com/intfar/mabridge/BridgeService.kt").readText()

    private fun range(fn: String): IntRange {
        val start = src.indexOf("private fun $fn(")
        assertTrue("$fn not found", start >= 0)
        val next = src.indexOf("\n    private fun ", start + 1).let { if (it < 0) src.length else it }
        return start until next
    }

    private fun body(fn: String) = src.substring(range(fn))

    @Test fun runIsCalledOnlyFromRunIfStillAllowed() {
        // Any call of run(...), whatever its arguments, but not the declaration itself.
        val calls = Regex("""(?<![A-Za-z.])run\(""").findAll(src)
            .filterNot { src.startsWith("fun ", it.range.first - 4) }.toList()
        assertEquals("direct run(command, …) calls", 1, calls.size)
        val guard = body("runIfStillAllowed")
        assertTrue("the one run(…) call is inside runIfStillAllowed", calls.single().range.first in range("runIfStillAllowed"))
        assertTrue("runIfStillAllowed asks atRunLock", guard.contains("atRunLock(running, stopState(), source.channel)"))
    }

    @Test fun bothRunLockHoldersGoThroughTheGuard() {
        assertTrue(body("handle").contains("synchronized(runLock) { runIfStillAllowed("))
        val confirmed = body("launchConfirmed")
        assertTrue(confirmed.contains("runIfStillAllowed(RUSTDESK, source, p.id)"))
        // A stopped bridge's confirmed launch is stored again for RESTARTED, never answered here.
        assertTrue(confirmed.contains("if (check == RunLockCheck.STOPPED) synchronized(confirmLock)"))
        assertTrue(confirmed.contains("storePending(p)"))
        assertEquals("runLock holders", 2, Regex("""synchronized\(runLock\)""").findAll(src).count())
    }
}
