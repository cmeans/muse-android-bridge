package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §2.1: a device slug is short, lowercase, and uses hyphens only. */
class ChannelTest {
    @Test fun acceptsProtocolExamples() {
        for (s in listOf("alex-pixel", "owner-phone", "spare-phone", "a1")) {
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

    @Test fun overlongServerIsRejectedWithoutThrowing() {
        // A long dotted host overflowed the regex engine's stack (QA round 2 on #4). Run on a
        // small fixed stack, so the test fails without the length cap whatever the JVM's default.
        val host = (1..5000).joinToString(".") { "a" } // about 10,000 characters
        var result: Boolean? = null
        var thrown: Throwable? = null
        val t = Thread(null, {
            try { result = isValidServer("https://$host") } catch (e: Throwable) { thrown = e }
        }, "small-stack", 256 * 1024)
        t.start(); t.join()
        thrown?.let { throw it }
        assertEquals(false, result)
        assertFalse(isValidServer("https://" + "a".repeat(254) + ".com"))
    }

    @Test fun longestValidServerIsAccepted() {
        // 253-character host of 63-character labels, the highest port and a slash: 268 characters.
        val label = "a".repeat(63)
        val host = listOf(label, label, label, "a".repeat(61)).joinToString(".")
        assertEquals(253, host.length)
        val server = "https://$host:65535/"
        assertEquals(268, server.length)
        assertTrue(isValidServer(server))
        assertFalse("a 64-character label", isValidServer("https://" + "a".repeat(64) + ".com"))
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

    private val m1 = FamilyMember("m1", "Alex")
    private val m2 = FamilyMember("m2", "Robin")

    @Test fun memberTopicResolves() {
        assertEquals(Source(Channel.FAMILY, m1), sourceForTopic("mab-p-family-m1", "p", listOf(m1)))
        assertEquals(Source(Channel.OWNER, null), sourceForTopic("mab-p-owner", "p", listOf(m1)))
    }

    @Test fun sharedFamilyTopicRetired() {
        assertNull(sourceForTopic("mab-p-family", "p", listOf(m1)))
    }

    @Test fun removedMemberTopicIsIgnored() {
        assertNull(sourceForTopic("mab-p-family-m2", "p", listOf(m1)))
    }

    @Test fun logTagNamesTheSlugNotTheName() {
        assertEquals("owner", Source(Channel.OWNER, null).logTag())
        assertEquals("family/m1", Source(Channel.FAMILY, m1).logTag())
    }

    @Test fun subscribeTopicsOrder() {
        assertEquals(listOf("mab-p-owner", "mab-p-family-m1", "mab-p-family-m2"), subscribeTopics("p", listOf(m1, m2)))
        assertEquals(listOf("mab-p-owner"), subscribeTopics("p", emptyList()))
    }

    @Test fun subscribeFallback() {
        val owner = "mab-p-owner"
        assertEquals(
            listOf(owner, "mab-p-family-m1"),
            topicsAfterProbe(owner, linkedMapOf(owner to 200, "mab-p-family-m1" to 200, "mab-p-family-m2" to 403)),
        )
        // The owner's own probe isn't required in the map; members keep setup order.
        assertEquals(
            listOf(owner, "mab-p-family-m2", "mab-p-family-m1"),
            topicsAfterProbe(owner, linkedMapOf("mab-p-family-m2" to 200, "mab-p-family-m1" to 200)),
        )
        // All refused: the owner topic alone.
        assertEquals(listOf(owner), topicsAfterProbe(owner, linkedMapOf(owner to 200, "mab-p-family-m1" to 403, "mab-p-family-m2" to 403)))
        // A network error (-1) or any status other than 200 counts as refused for this round.
        assertEquals(listOf(owner), topicsAfterProbe(owner, linkedMapOf("mab-p-family-m1" to -1, "mab-p-family-m2" to 500)))
        assertEquals(listOf(owner), topicsAfterProbe(owner, emptyMap<String, Int>()))
    }
    @Test fun refusedForLogSaysWhatHappened() {
        // A 403 is the plain refusal; anything else names its answer, so a flaky probe doesn't
        // read as a missing grant. Slugs only, never names.
        assertEquals("family/m1", refusedForLog(listOf("m1" to 403)))
        assertEquals(
            "family/m1, family/m2 (no answer), family/m3 (HTTP 500)",
            refusedForLog(listOf("m1" to 403, "m2" to -1, "m3" to 500)),
        )
        // A member the probe never reached (the bridge stopped mid-round) has no answer either.
        assertEquals("family/m4 (no answer)", refusedForLog(listOf("m4" to null)))
    }
}
