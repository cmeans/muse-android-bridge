package org.intfar.mabridge.protocol

/**
 * Set when the owner taps Stop bridge, cleared when they start it again. Unlike
 * "service_running", which the service clears whenever it stops (a restart after a setup
 * save included), this records the owner's choice, so a reboot can honor it.
 */
const val PREF_OWNER_STOPPED = "owner_stopped"

/** The two broadcasts that may start the bridge without the owner. */
enum class BootEvent { BOOT, UPDATE }

/**
 * What the boot receiver does: start the bridge or not, an on-phone log line (null for none),
 * and whether to record [PREF_OWNER_STOPPED] (see [bootStartFor]).
 */
data class BootStart(val start: Boolean, val logLine: String?, val markOwnerStopped: Boolean = false)

/**
 * Decides whether [event] starts the bridge.
 * - A bridge the owner stopped stays stopped, after a reboot or an update, and the log says so.
 * - After a reboot, a bridge that wasn't stopped starts once setup is complete.
 * - After an update, it starts only if it was running ([wasRunning]): installing an update
 *   stops the service without its onDestroy, so "service_running" still says it was on.
 *   [ownerStopped] is null when the preference has never been written (builds before it
 *   existed): a bridge that wasn't running at the update is then recorded as owner-stopped,
 *   so the next reboot doesn't start a bridge that was off.
 * Incomplete setup starts nothing and logs nothing, as before.
 */
fun bootStartFor(event: BootEvent, setupComplete: Boolean, ownerStopped: Boolean?, wasRunning: Boolean): BootStart {
    val after = if (event == BootEvent.BOOT) "a reboot" else "an update"
    if (ownerStopped == true) return BootStart(false, "not started after $after: the bridge was stopped on the phone")
    return when (event) {
        BootEvent.BOOT -> BootStart(setupComplete, null)
        BootEvent.UPDATE ->
            if (wasRunning) BootStart(setupComplete, null)
            else BootStart(false, null, markOwnerStopped = ownerStopped == null)
    }
}
