package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The version the phone reports in `status report` is the one PROTOCOL.md's header states. */
class ProtocolVersionTest {
    @Test fun protocolVersionMatchesProtocolMd() {
        // Unit tests run with the module directory (app/android/app) as the working directory.
        val header = File("../../../PROTOCOL.md").readLines().first { it.startsWith("**Version:**") }
        assertEquals(Regex("""\*\*Version:\*\* ([0-9.]+)""").find(header)!!.groupValues[1], PROTOCOL_VERSION)
        assertEquals("0.4", PROTOCOL_VERSION)
    }
}
