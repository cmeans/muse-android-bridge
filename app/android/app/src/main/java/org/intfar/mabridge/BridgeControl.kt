package org.intfar.mabridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Starting and stopping the bridge service from the owner and setup screens. */
object BridgeControl {
    /** Starts the service when setup is complete; returns what's still missing otherwise. */
    fun start(ctx: Context): List<String> {
        val missing = Config.missingSetup(ctx)
        if (missing.isEmpty()) ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        return missing
    }

    /**
     * Notifications carry every announcement, and launches and rings don't run without one.
     * Android's prompt is shown once (a second refusal stops it showing); after that this opens
     * the app's notification settings, if they're off.
     */
    fun requestNotifications(activity: Activity) {
        val prefs = Config.prefs(activity)
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)
        ) {
            prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (!(activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()) {
            openSetting(
                activity,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        }
    }

    /**
     * Opens an Android settings screen. Android says some of these may not exist on a device
     * ("a matching Activity may not exist, so ensure you safeguard against this"), so a missing
     * screen falls back to the app's own info page, and if that's missing too, a short message,
     * instead of crashing the owner screen.
     */
    fun openSetting(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "This phone has no settings screen for that. Look for Muse Bridge in Settings, Apps.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private const val PREF_ASKED_NOTIFICATIONS = "asked_notifications"

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
