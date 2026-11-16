package com.intfar.mabridge.protocol

/**
 * Logged, once per request not sent, when the stored password can't be decrypted (the Keystore
 * key was lost, say): the bridge sends nothing rather than an empty password, which would only
 * show as an unexplained `HTTP 401`.
 */
const val CREDENTIAL_UNREADABLE = "credential unreadable: re-enter the password in setup"

/** Logged when the setup-time receipt (a save that moved the server or device name) was sent. */
const val SETUP_RECEIPT_SENT = "receipt sent (setup changed)"

/**
 * The setup-time receipt's failure line. It names where the receipt went, the old server's
 * [host] (never a credential) and the old [topic], so it doesn't read as the new server refusing
 * it. [detail] is `HTTP <code>` or the error's class name; [text] is the receipt.
 */
fun setupReceiptFailedLog(host: String, topic: String, detail: String, text: String): String =
    "receipt failed (old server $host, $topic): $detail: $text"
