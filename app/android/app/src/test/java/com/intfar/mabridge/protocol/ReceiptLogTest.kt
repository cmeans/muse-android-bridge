package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** #33 items 16, 17: the receipt and credential log lines. */
class ReceiptLogTest {
    @Test fun setupReceiptFailureNamesTheOldDestination() {
        assertEquals(
            "receipt failed (old server ntfy.example.org:8443, mab-old-phone-ack): HTTP 403: err launch rustdesk not-confirmed id=Ab12Cd34Ef56",
            setupReceiptFailedLog(
                hostOf("https://ntfy.example.org:8443/"),
                "mab-old-phone-ack",
                "HTTP 403",
                "err launch rustdesk not-confirmed id=Ab12Cd34Ef56",
            ),
        )
    }

    @Test fun fixedLines() {
        assertEquals("receipt sent (setup changed)", SETUP_RECEIPT_SENT)
        assertEquals("credential unreadable: re-enter the password in setup", CREDENTIAL_UNREADABLE)
    }
}
