package com.intfar.mabridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #33 item 17: no request goes out with an empty password. */
class BasicAuthTest {
    @Test fun unreadablePasswordGivesNoHeader() {
        assertNull(basicAuthHeader("pilot-phone-phone", null))
    }

    @Test fun emptyPasswordGivesNoHeader() {
        assertNull(basicAuthHeader("pilot-phone-phone", CharArray(0)))
    }

    @Test fun headerIsBasicBase64AndThePasswordIsZeroed() {
        val pw = "s3cret".toCharArray()
        // "user:s3cret" in Base64.
        assertEquals("Basic dXNlcjpzM2NyZXQ=", basicAuthHeader("user", pw))
        assertArrayEquals(CharArray(6), pw)
    }
}
