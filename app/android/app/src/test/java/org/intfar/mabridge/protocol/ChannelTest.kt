package org.intfar.mabridge.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §2.1: a device slug is short, lowercase, and uses hyphens only. */
class ChannelTest {
    @Test fun acceptsProtocolExamples() {
        for (s in listOf("chris-razr", "michele-pixel", "peggy-phone", "test-moto", "a1")) {
            assertTrue(s, isValidSlug(s))
        }
    }

    @Test fun rejectsWhatWouldBreakTheTopicOrUrl() {
        // A space breaks the HTTP request line, a comma adds topics, a slash changes the path.
        for (s in listOf("", "Peggy-phone", "peggy phone", "peggy,x", "peggy/x", "-peggy", "peggy-",
            "peggy--phone", "peggy_phone", "pégy", "a".repeat(41))) {
            assertFalse(s, isValidSlug(s))
        }
    }

    @Test fun acceptsHttpsServerWithHostOnly() {
        for (s in listOf("https://ntfy.intfar.com", "https://ntfy.example.com:8443", "https://ntfy.intfar.com/")) {
            assertTrue(s, isValidServer(s))
        }
    }

    @Test fun rejectsServerThatReleaseBuildsCantReachOrThatBreaksTheUrl() {
        // Release builds are HTTPS only; a path would be glued in front of the topic list.
        for (s in listOf("", "http://ntfy.intfar.com", "ntfy.intfar.com", "https://", "https://ntfy.intfar.com/sub",
            "https://ntfy intfar.com", "https://ntfy.intfar.com?x=1", "https://user@ntfy.intfar.com", "ftp://ntfy.intfar.com")) {
            assertFalse(s, isValidServer(s))
        }
    }
}
