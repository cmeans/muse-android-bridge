package org.intfar.mabridge

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import org.intfar.mabridge.protocol.isValidServer
import org.intfar.mabridge.protocol.isValidSlug

/** The bridge's stored setup and STOP switches (the protocol constants live in `protocol/`). */
object Config {
    private const val PREFS = "mabridge"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(ctx: Context): String =
        prefs(ctx).getString("server", "")!!.trimEnd('/')

    fun deviceSlug(ctx: Context): String =
        prefs(ctx).getString("slug", "")!!

    fun username(ctx: Context): String =
        prefs(ctx).getString("username", "")!!

    fun ackTopic(ctx: Context) = "mab-${deviceSlug(ctx)}-ack"

    fun stopAll(ctx: Context) = prefs(ctx).getBoolean("stop_all", false)
    fun stopFamily(ctx: Context) = prefs(ctx).getBoolean("stop_family", false)

    /** A debug build, whose network config also allows plain HTTP to the emulator's host. */
    fun isDebugBuild(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Setup fields still empty or invalid; the service doesn't start until this is empty. */
    fun missingSetup(ctx: Context): List<String> = buildList {
        if (!isValidServer(server(ctx), isDebugBuild(ctx))) add("server address")
        if (!isValidSlug(deviceSlug(ctx))) add("device name")
        if (username(ctx).isBlank()) add("username")
        if (!CredentialStore(ctx).isSet()) add("password")
    }

}
