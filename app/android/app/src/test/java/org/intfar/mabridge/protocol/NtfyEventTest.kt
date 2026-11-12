package org.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtfyEventTest {
    // The shape ntfy's /json stream sends (one JSON object per line).
    private val real = """{"id":"sPs71M8A2T","time":1800000000,"expires":1800259200,""" +
        """"event":"message","topic":"mab-chris-razr-family","message":"launch meet"}"""

    @Test fun messageLineParsed() {
        assertEquals(
            IncomingMessage("sPs71M8A2T", 1_800_000_000L, "mab-chris-razr-family", "launch meet"),
            parseNtfyLine(real),
        )
    }

    @Test fun bodyNotTrimmed() {
        val line = real.replace("\"launch meet\"", "\" launch meet\\n\"")
        assertEquals(" launch meet\n", parseNtfyLine(line)?.body)
    }

    @Test fun nonMessageEventsIgnored() {
        for (ev in listOf("open", "keepalive", "poll_request")) {
            assertNull(ev, parseNtfyLine("""{"id":"x","time":1800000000,"event":"$ev","topic":"mab-chris-razr-family"}"""))
        }
    }

    @Test fun malformedLinesIgnored() {
        val bad = listOf(
            "", "not json", "[1,2]", "{",
            real.replace(""""id":"sPs71M8A2T",""", ""),
            real.replace(""""id":"sPs71M8A2T"""", """"id":"   """"),
            real.replace("1800000000,", "\"abc\","),
            real.replace("1800000000,", "1.8e9,"),
            real.replace(""","message":"launch meet"""", ""),
            real.replace(""""topic":"mab-chris-razr-family",""", ""),
            real.replace("\"launch meet\"", "42"),
        )
        for (b in bad) assertNull(b.take(40), parseNtfyLine(b))
    }

    @Test fun hugeLineDoesNotThrow() {
        val line = real.replace("launch meet", "a".repeat(1_000_000))
        assertEquals(1_000_000, parseNtfyLine(line)?.body?.length)
    }
}
