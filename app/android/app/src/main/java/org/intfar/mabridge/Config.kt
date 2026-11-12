package org.intfar.mabridge

import android.content.Context
import android.content.SharedPreferences

/** Bridge configuration + the PROTOCOL.md constants. */
object Config {
    private const val PREFS = "mabridge"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(ctx: Context): String =
        prefs(ctx).getString("server", "https://ntfy.intfar.com")!!.trimEnd('/')

    fun deviceSlug(ctx: Context): String =
        prefs(ctx).getString("slug", "chris-razr")!!

    fun username(ctx: Context): String =
        prefs(ctx).getString("username", "")!!

    // Pilot note: stored in plain SharedPreferences. The credential is the
    // scope-limited phone user (read two topics, write ack only; verified
    // in test F2). Encrypted storage is a v1.1 hardening item.
    fun password(ctx: Context): String =
        prefs(ctx).getString("password", "")!!

    fun ownerTopic(ctx: Context) = "mab-${deviceSlug(ctx)}-owner"
    fun familyTopic(ctx: Context) = "mab-${deviceSlug(ctx)}-family"
    fun ackTopic(ctx: Context) = "mab-${deviceSlug(ctx)}-ack"

    fun stopAll(ctx: Context) = prefs(ctx).getBoolean("stop_all", false)
    fun stopFamily(ctx: Context) = prefs(ctx).getBoolean("stop_family", false)

    const val TTL_SECONDS = 120L
    const val DEDUP_WINDOW_MS = 24L * 60 * 60 * 1000
}
