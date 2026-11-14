package org.intfar.mabridge

import android.content.Context
import android.content.Intent

/** Starting and stopping the bridge service from the owner and setup screens. */
object BridgeControl {
    /** Starts the service when setup is complete; returns what's still missing otherwise. */
    fun start(ctx: Context): List<String> {
        val missing = Config.missingSetup(ctx)
        if (missing.isEmpty()) ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        return missing
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    /**
     * Applies a saved setup at once: a running bridge stops and starts again, so it
     * subscribes with the new server and topics instead of picking them up silently at
     * the next reconnect.
     */
    fun restartIfRunning(ctx: Context) {
        if (!Config.prefs(ctx).getBoolean("service_running", false)) return
        stop(ctx)
        start(ctx)
    }
}
