package org.intfar.mabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the bridge after a reboot, or after the app is updated (installing an
 * update stops the running service, and nothing else would start it again), if
 * setup is complete. Both broadcasts may start a foreground service from the
 * background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Config.missingSetup(context).isNotEmpty()) return
        context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
