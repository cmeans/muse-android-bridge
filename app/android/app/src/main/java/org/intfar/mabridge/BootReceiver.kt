package org.intfar.mabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the bridge after a reboot if setup is complete, and after the app is
 * updated if it was running then: installing an update stops the service
 * without its onDestroy, so "service_running" still says it was on, and
 * nothing else would start it again. A bridge someone stopped stays stopped
 * through an update. Both broadcasts may start a foreground service from the
 * background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {}
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (!Config.prefs(context).getBoolean("service_running", false)) return
            else -> return
        }
        if (Config.missingSetup(context).isNotEmpty()) return
        context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
