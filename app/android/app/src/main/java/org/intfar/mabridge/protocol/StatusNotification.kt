package org.intfar.mabridge.protocol

/** A one-tap STOP button on the bridge's status notification. */
enum class StopButton { FAMILY, ALL }

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
    else -> StatusNotification("Listening for commands. Tap to open.", listOf(StopButton.FAMILY, StopButton.ALL))
}
