package org.intfar.mabridge.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §2.1: a device slug is short, lowercase, and uses hyphens only. */
class ChannelTest {
    @Test fun acceptsProtocolExamples() {
        for (s in listOf("alex-pixel", "owner-phone", "test-moto", "a1")) {
            assertTrue(s, isValidSlug(s))
        }
    }

    @Test fun rejectsWhatWouldBreakTheTopicOrUrl() {
        // A space breaks the HTTP request line, a comma adds topics, a slash changes the path.
        for (s in listOf("", "Alex-pixel", "alex pixel", "alex,x", "alex/x", "-alex", "alex-",
            "alex--pixel", "alex_pixel", "aléx", "a".repeat(41))) {
            assertFalse(s, isValidSlug(s))
        }
    }

    @Test fun acceptsHttpsServerWithHostOnly() {
        for (s in listOf("https://ntfy.example.com", "https://ntfy.example.com:8443", "https://ntfy.example.com/",
            "https://ntfy.example.com:1", "https://ntfy.example.com:65535")) {
            assertTrue(s, isValidServer(s))
        }
    }

    @Test fun rejectsPortOutOfRange() {
        for (s in listOf("https://ntfy.example.com:0", "https://ntfy.example.com:65536", "https://ntfy.example.com:99999")) {
            assertFalse(s, isValidServer(s))
        }
    }

    @Test fun emulatorHttpOnlyWhereDebugBuildsAllowIt() {
        // src/debug's network config allows plain HTTP to 10.0.2.2 (the emulator's host) only.
        assertTrue(isValidServer("http://10.0.2.2:8081", allowEmulatorHttp = true))
        assertTrue(isValidServer("http://10.0.2.2", allowEmulatorHttp = true))
        assertFalse(isValidServer("http://10.0.2.2:8081", allowEmulatorHttp = false))
        assertFalse(isValidServer("http://ntfy.example.com", allowEmulatorHttp = true))
        assertFalse(isValidServer("http://10.0.2.2:8081/x", allowEmulatorHttp = true))
    }

    @Test fun rejectsServerThatReleaseBuildsCantReachOrThatBreaksTheUrl() {
        // Release builds are HTTPS only; a path would be glued in front of the topic list.
        for (s in listOf("", "http://ntfy.example.com", "ntfy.example.com", "https://", "https://ntfy.example.com/sub",
            "https://ntfy example.com", "https://ntfy.example.com?x=1", "https://user@ntfy.example.com", "ftp://ntfy.example.com")) {
            assertFalse(s, isValidServer(s))
        }
    }
}
