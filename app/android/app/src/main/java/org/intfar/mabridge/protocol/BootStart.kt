package org.intfar.mabridge.protocol

/**
 * Set when the owner taps Stop bridge, cleared when they start it again. Unlike
 * "service_running", which the service clears whenever it stops (a restart after a setup
 * save included), this records the owner's choice, so a reboot can honor it.
 */
const val PREF_OWNER_STOPPED = "owner_stopped"

/** On-phone log lines (the owner screen's Recent list maps each one, see plainEvent). */
const val LOG_STOP_TAPPED = "Stop bridge tapped: stays stopped after a reboot until started again"
const val LOG_NOT_STARTED_REBOOT = "not started after a reboot: the bridge is stopped (Start bridge starts it)"
const val LOG_NOT_STARTED_UPDATE = "not started after an update: the bridge is stopped (Start bridge starts it)"
const val LOG_KEPT_OFF_AT_UPDATE = "update: the bridge was off, so it stays off after a reboot until Start bridge"

/**
 * [PREF_OWNER_STOPPED] as [bootStartFor] takes it: null when the preference has never been
 * written ([contains] false), else its value.
 */
fun ownerStoppedFrom(contains: Boolean, value: Boolean): Boolean? = if (contains) value else null

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
 *   [ownerStopped] is null when the preference has never been written: an install from a
 *   build before it existed, or one where Start bridge and Stop bridge were never tapped. A
 *   bridge that isn't running at such an update (setup incomplete included) is then recorded
 *   as stopped, and logged, so the next reboot doesn't start a bridge that was off.
 * Otherwise, incomplete setup starts nothing and logs nothing, as before.
 */
fun bootStartFor(event: BootEvent, setupComplete: Boolean, ownerStopped: Boolean?, wasRunning: Boolean): BootStart {
    if (ownerStopped == true) {
        return BootStart(false, if (event == BootEvent.BOOT) LOG_NOT_STARTED_REBOOT else LOG_NOT_STARTED_UPDATE)
    }
    return when (event) {
        BootEvent.BOOT -> BootStart(setupComplete, null)
        BootEvent.UPDATE ->
            if (wasRunning) BootStart(setupComplete, null)
            else if (ownerStopped == null) BootStart(false, LOG_KEPT_OFF_AT_UPDATE, markOwnerStopped = true)
            else BootStart(false, null)
    }
}
