package org.intfar.mabridge.protocol

/** The preference keys of the two STOP switches; a change to either re-posts the status notification. */
const val PREF_STOP_ALL = "stop_all"
const val PREF_STOP_FAMILY = "stop_family"
val STOP_PREF_KEYS = setOf(PREF_STOP_ALL, PREF_STOP_FAMILY)

/**
 * A one-tap STOP button on the bridge's status notification: its label, the service action it
 * sends, and the STOP switch that action turns on. Kept together so a button can't be wired to
 * the other switch. [switchName] is how the log names the switch.
 */
enum class StopButton(val label: String, val action: String, val prefKey: String, val switchName: String) {
    FAMILY("Stop family", "org.intfar.mabridge.STOP_FAMILY", PREF_STOP_FAMILY, "STOP family"),
    ALL("Stop all", "org.intfar.mabridge.STOP_ALL", PREF_STOP_ALL, "STOP all");

    companion object {
        /** The button a service action came from, or null for any other action. */
        fun forAction(action: String?): StopButton? = entries.firstOrNull { it.action == action }
    }
}

/**
 * What tapping a STOP button does, given the STOP state before the tap: turn on
 * [StopButton.prefKey], end a ring for Stop all, and log [logLine]. A tap on a switch that's
 * already on (a stale notification) changes nothing and the log says so.
 */
data class StopTap(val button: StopButton, val stopsRinging: Boolean, val logLine: String)

/** The tap a service action stands for, or null when the action isn't a STOP button. */
fun stopTapFor(action: String?, before: StopState): StopTap? {
    val button = StopButton.forAction(action) ?: return null
    val alreadyOn = when (button) {
        StopButton.FAMILY -> before.family
        StopButton.ALL -> before.all
    }
    val logLine = if (alreadyOn) "${button.switchName} tapped on the notification; it was already on"
    else "${button.switchName} switched on from the notification"
    return StopTap(button, stopsRinging = button == StopButton.ALL, logLine = logLine)
}

/** What the status notification says and which STOP buttons it offers. */
data class StatusNotification(val text: String, val buttons: List<StopButton>)

/**
 * The status notification for a STOP state (PROTOCOL §7): it tells the truth about which
 * channels are on, and offers a button only for a channel that's still on. Switching a
 * channel back on is done in the app, never from the notification.
 */
fun statusNotificationFor(stop: StopState): StatusNotification = when {
    stop.all -> StatusNotification("All commands stopped. Tap to open.", emptyList())
    stop.family -> StatusNotification("Listening for owner commands; family stopped. Tap to open.", listOf(StopButton.ALL))
    else -> StatusNotification("Listening for owner and family commands. Tap to open.", listOf(StopButton.FAMILY, StopButton.ALL))
}
