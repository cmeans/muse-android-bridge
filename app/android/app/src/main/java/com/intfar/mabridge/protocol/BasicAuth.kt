package com.intfar.mabridge.protocol

import java.util.Base64

/**
 * [username] and [password] as an HTTP Basic Authorization header, or null when there is no
 * password to send: null (it can't be decrypted, or none is stored) or empty. The caller then
 * logs [CREDENTIAL_UNREADABLE] and sends nothing, never a request with an empty password (#33
 * item 17). Zeroes [password] either way.
 */
fun basicAuthHeader(username: String, password: CharArray?): String? {
    val pw = password ?: return null
    try {
        if (pw.isEmpty()) return null
        val raw = "$username:${String(pw)}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    } finally {
        pw.fill('\u0000')
    }
}
