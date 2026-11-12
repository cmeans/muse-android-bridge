package org.intfar.mabridge

import android.content.Context
import android.content.SharedPreferences

/** The bridge's stored setup and STOP switches (the protocol constants live in `protocol/`). */
object Config {
    private const val PREFS = "mabridge"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(ctx: Context): String =
        prefs(ctx).getString("server", "https://ntfy.intfar.com")!!.trimEnd('/')

    fun deviceSlug(ctx: Context): String =
        prefs(ctx).getString("slug", "")!!

    fun username(ctx: Context): String =
        prefs(ctx).getString("username", "")!!

    fun ackTopic(ctx: Context) = "mab-${deviceSlug(ctx)}-ack"

    fun stopAll(ctx: Context) = prefs(ctx).getBoolean("stop_all", false)
    fun stopFamily(ctx: Context) = prefs(ctx).getBoolean("stop_family", false)

    /** Setup fields still empty; the service doesn't start until this is empty. */
    fun missingSetup(ctx: Context): List<String> = buildList {
        if (server(ctx).isBlank()) add("server")
        if (deviceSlug(ctx).isBlank()) add("device slug")
        if (username(ctx).isBlank()) add("username")
        if (!CredentialStore(ctx).isSet()) add("password")
    }

}
