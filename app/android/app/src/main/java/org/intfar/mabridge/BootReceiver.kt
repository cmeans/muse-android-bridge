package org.intfar.mabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the bridge after reboot if setup is complete. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Config.missingSetup(context).isNotEmpty()) return
        context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
