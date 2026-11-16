package com.intfar.mabridge

import android.content.Context
import com.intfar.mabridge.protocol.CREDENTIAL_UNREADABLE
import com.intfar.mabridge.protocol.PendingConfirm
import com.intfar.mabridge.protocol.basicAuthHeader
import com.intfar.mabridge.protocol.hostOf
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

    // commit, not apply: a request cleared but kept by a crash would be answered again at the
    // next start (a second receipt), and one stored but lost would get no receipt. Commit gives
    // at most one receipt per message under a crash, not exactly one: the receipt is queued
    // after the clear commits and sent later (ack is asynchronous, and commit's result isn't
    // checked), so a process death in between leaves the message with none.
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
 * Where a receipt goes: the server, the ack topic, the username and the stored ciphertext of the
 * password, read when the receipt is queued rather than when it's sent, so a setup save in between
 * can't send it to the new server (QA r1). Reading them is cheap (preferences only), so any thread
 * can queue a receipt; [post] decrypts the password at send time, off the main thread. It keeps
 * the ciphertext it read, not the one stored at send time: that is the credential for the server
 * it read, and a later save's password may be the new server's, which must never go to the old
 * one. So a receipt queued just before a password-only change is sent with the old password,
 * and may be refused (logged as `receipt failed`). Held in memory only, for one send; never
 * the plaintext.
 */
class AckDestination private constructor(
    private val store: CredentialStore,
    private val server: String,
    /** The ack topic, `mab-<device>-ack`: the log may name it. */
    val topic: String,
    private val username: String,
    private val sealed: CredentialStore.Sealed?,
) {
    /** The server's host (and port), never a credential: the log may name it. */
    val host: String get() = hostOf(server)

    /**
     * Posts [text] to the ack topic: the HTTP status, or throws on a network error. Throws
     * [CredentialUnreadableException], sending nothing, when the password can't be decrypted.
     * Off the main thread.
     */
    fun post(text: String): Int {
        var c: HttpURLConnection? = null
        try {
            val auth = basicAuthHeader(username, sealed?.let { store.open(it) }) ?: throw CredentialUnreadableException()
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
        /** The stored setup's destination, as it is now. Preference reads only: no Keystore call. */
        fun current(ctx: Context): AckDestination {
            val store = CredentialStore(ctx.applicationContext)
            return AckDestination(store, Config.server(ctx), Config.ackTopic(ctx), Config.username(ctx), store.sealed())
        }
    }
}

/**
 * The stored username and password as an HTTP Basic Authorization header, or null when the
 * password can't be decrypted (or none is stored): the caller logs [CREDENTIAL_UNREADABLE] and
 * sends nothing, rather than an empty password. A Keystore call: not on the main thread.
 */
fun basicAuth(ctx: Context): String? = basicAuthHeader(Config.username(ctx), CredentialStore(ctx).load())

/** The stored password can't be decrypted (or none is stored), so a request was not sent. */
class CredentialUnreadableException : Exception("credential unreadable")
