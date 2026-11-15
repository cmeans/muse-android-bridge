package org.intfar.mabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.intfar.mabridge.protocol.BootEvent
import org.intfar.mabridge.protocol.PREF_OWNER_STOPPED
import org.intfar.mabridge.protocol.bootStartFor

/**
 * Restarts the bridge after a reboot, and after the app is updated if it was running then,
 * unless the owner stopped it (rules in [bootStartFor]). Both broadcasts may start a
 * foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> BootEvent.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> BootEvent.UPDATE
            else -> return
        }
        val prefs = Config.prefs(context)
        val decision = bootStartFor(
            event,
            setupComplete = Config.missingSetup(context).isEmpty(),
            ownerStopped = if (prefs.contains(PREF_OWNER_STOPPED)) prefs.getBoolean(PREF_OWNER_STOPPED, false) else null,
            wasRunning = prefs.getBoolean("service_running", false),
        )
        if (decision.markOwnerStopped) prefs.edit().putBoolean(PREF_OWNER_STOPPED, true).apply()
        decision.logLine?.let { BridgeLog.append(context, it) }
        if (decision.start) context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
