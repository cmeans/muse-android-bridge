package org.intfar.mabridge.protocol

import org.json.JSONObject

/**
 * One line of ntfy's `/json` subscription stream as a message, or null for
 * anything else: other events (open, keepalive, poll_request), invalid
 * JSON, or a message missing a usable id, integer time, topic or body.
 * Field values are copied exactly; the body is never trimmed.
 */
fun parseNtfyLine(line: String): IncomingMessage? {
    val o = try {
        JSONObject(line)
    } catch (_: Exception) {
        return null
    }
    if (o.opt("event") != "message") return null
    val id = o.opt("id") as? String ?: return null
    if (id.isBlank()) return null
    val time = when (val t = o.opt("time")) {
        is Int -> t.toLong()
        is Long -> t
        else -> return null
    }
    val topic = o.opt("topic") as? String ?: return null
    val body = o.opt("message") as? String ?: return null
    return IncomingMessage(id, time, topic, body)
}
