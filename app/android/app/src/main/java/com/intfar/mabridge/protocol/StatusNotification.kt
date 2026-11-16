package com.intfar.mabridge.protocol

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
    FAMILY("Stop family", "com.intfar.mabridge.STOP_FAMILY", PREF_STOP_FAMILY, "STOP family"),
    // The label matches the owner screen's card; the log keeps the shorter "STOP all".
    ALL("Stop everything", "com.intfar.mabridge.STOP_ALL", PREF_STOP_ALL, "STOP all");

    companion object {
        /** The button a service action came from, or null for any other action. */
        fun forAction(action: String?): StopButton? = entries.firstOrNull { it.action == action }
    }
}

/**
 * What tapping a STOP button does, given the STOP state before the tap: turn on
 * [StopButton.prefKey], end a ring for Stop everything, and log [logLine]. A tap on a switch that's
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

/**
 * What the status notification says and which STOP buttons it offers. [publicText] is the
 * version a lock screen that hides sensitive content shows: it names no member.
 */
data class StatusNotification(val text: String, val buttons: List<StopButton>, val publicText: String = text)

/**
 * The status notification for a STOP state (PROTOCOL §7): it tells the truth about which
 * channels are on, and offers a button only for a channel that's still on. Switching a
 * channel back on is done in the app, never from the notification. [members] is how many
 * family members are set up: with none, the phone reads no family topic, so the text names
 * only "your Muse" and there's no Stop family button. [refusedNames] are the display names of
 * the members whose topics the server refused (design §2); the STOP texts win over them. The
 * names are only in [StatusNotification.text]; its public text says "some" or "any" instead.
 */
fun statusNotificationFor(stop: StopState, host: String, members: Int, refusedNames: List<String>): StatusNotification {
    val buttons = if (members == 0) listOf(StopButton.ALL) else listOf(StopButton.FAMILY, StopButton.ALL)
    val refused = refusedNames.joinToString(", ")
    return when {
        stop.all -> StatusNotification("Everything stopped. Tap to open.", emptyList())
        // The server is named, so a changed setup shows on the notification too.
        stop.family -> StatusNotification("Listening for your Muse on $host; family stopped. Tap to open.", listOf(StopButton.ALL))
        members == 0 -> StatusNotification("Listening for your Muse on $host. Tap to open.", buttons)
        refusedNames.isEmpty() -> StatusNotification("Listening for your Muse and family on $host. Tap to open.", buttons)
        // Every member refused: only the owner's own Muse can reach the phone.
        refusedNames.size >= members -> StatusNotification(
            "Listening for your Muse on $host. Can't reach: $refused. Tap to open.",
            buttons,
            publicText = "Listening for your Muse on $host. Can't reach any family member. Tap to open.",
        )
        else -> StatusNotification(
            "Listening for your Muse and family on $host. Can't reach: $refused. Tap to open.",
            buttons,
            publicText = "Listening for your Muse and family on $host. Can't reach some family members. Tap to open.",
        )
    }
}
