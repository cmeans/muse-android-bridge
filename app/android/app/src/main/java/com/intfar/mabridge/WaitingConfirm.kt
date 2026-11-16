package com.intfar.mabridge

import android.content.Context
import android.util.Base64
import com.intfar.mabridge.protocol.PendingConfirm
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one request waiting for a tap (design §4), as stored in the app's preferences, and the
 * lock every path that reads, decides and clears it holds. Process-wide, not the service's: the
 * setup screen answers it too, when a save moves the server or device name (QA r1).
 */
object WaitingConfirm {
    /** Held from reading the waiting request to clearing it. Never taken while holding the service's runLock. */
    val lock = Any()

    fun read(ctx: Context): PendingConfirm? {
        val prefs = Config.prefs(ctx)
        val id = prefs.getString(PREF_ID, null) ?: return null
        return PendingConfirm(id, prefs.getString(PREF_MEMBER, null) ?: "", prefs.getLong(PREF_DEADLINE, 0L))
    }

    // commit, not apply: a request stored but lost, or cleared but kept, by a crash would
    // break exactly one receipt per message.
    @android.annotation.SuppressLint("ApplySharedPref") // on purpose, see above
    fun store(ctx: Context, p: PendingConfirm) {
        Config.prefs(ctx).edit()
            .putString(PREF_ID, p.id)
            .putString(PREF_MEMBER, p.member)
            .putLong(PREF_DEADLINE, p.deadlineMs)
            .commit()
    }

    @android.annotation.SuppressLint("ApplySharedPref") // on purpose, like store
    fun clear(ctx: Context) {
        Config.prefs(ctx).edit().remove(PREF_ID).remove(PREF_MEMBER).remove(PREF_DEADLINE).commit()
    }

    private const val PREF_ID = "confirm_id"
    private const val PREF_MEMBER = "confirm_member"
    private const val PREF_DEADLINE = "confirm_deadline"
}

/**
 * Where a receipt goes: the server, the ack topic and the credential's Authorization header,
 * read when the receipt is queued rather than when it's sent, so a setup save in between can't
 * send it to the new server (QA r1). Held in memory only, for one send.
 */
class AckDestination private constructor(private val server: String, private val topic: String, private val auth: String) {
    /** Posts [text] to the ack topic: the HTTP status, or throws on a network error. Off the main thread. */
    fun post(text: String): Int {
        var c: HttpURLConnection? = null
        try {
            c = URL("$server/").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", auth)
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            val payload = JSONObject().put("topic", topic).put("message", text).toString()
            c.outputStream.use { it.write(payload.toByteArray()) }
            return c.responseCode
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        /** The stored setup's destination, as it is now. */
        fun current(ctx: Context) = AckDestination(Config.server(ctx), Config.ackTopic(ctx), basicAuth(ctx))
    }
}

/** The stored username and password as an HTTP Basic Authorization header. */
fun basicAuth(ctx: Context): String {
    val pw = CredentialStore(ctx).load() ?: CharArray(0)
    val raw = "${Config.username(ctx)}:${String(pw)}"
    pw.fill('\u0000')
    return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
}
