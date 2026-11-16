package com.intfar.mabridge.protocol

/** The longest ntfy message id the phone accepts (PROTOCOL.md §6; ntfy's are 12 characters). */
const val MAX_MESSAGE_ID_LENGTH = 32

/**
 * The 0.4 message id grammar: 1 to [MAX_MESSAGE_ID_LENGTH] characters, each `[A-Za-z0-9]`.
 * An explicit ASCII range check, not isLetterOrDigit, which accepts non-ASCII.
 */
fun isValidMessageId(id: String): Boolean =
    id.length in 1..MAX_MESSAGE_ID_LENGTH && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

/** ntfy's own message ids are exactly this long; its `since=` reads any other length as a time or duration. */
const val NTFY_MESSAGE_ID_LENGTH = 12

/**
 * The reconnect's `since=`: the last message id seen, only when ntfy will read it as an id
 * (valid and exactly [NTFY_MESSAGE_ID_LENGTH] characters), else a 2-minute look-back. A stored
 * value from an older build, or a short all-digit id ntfy would take as a Unix time, can't
 * replay the cache or get every reconnect refused.
 */
fun sinceParam(lastId: String?): String =
    lastId?.takeIf { isValidMessageId(it) && it.length == NTFY_MESSAGE_ID_LENGTH } ?: "2m"

/**
 * Receipt bodies for the ack topic, exactly as listed in PROTOCOL.md §6 (0.4: each ends with
 * `id=`, added by [withId]).
 */
object Receipts {
    /**
     * [receipt] with the id of the command message it answers appended last (0.4). Throws for
     * an id outside the grammar, which the gate never passes on, so a receipt can't carry free text.
     */
    fun withId(receipt: String, id: String): String {
        require(isValidMessageId(id)) { "message id outside the 0.4 grammar" }
        return "$receipt id=$id"
    }

    fun ok(c: Command) = "ok ${c.text}"
    fun notInstalled(c: Command.Launch) = "err ${c.text} not-installed"
    fun disabled(c: Command) = "err ${c.text} disabled"
    fun notImplemented(c: Command) = "err ${c.text} not-implemented"
    fun unknown() = "err unknown-command"
    /** The action threw or couldn't run (e.g. no sound to ring); nothing is retried. */
    fun failed(c: Command) = "err ${c.text} failed"
    /** The phone can't show the announcement, so nothing was done (PROTOCOL §1: no silent operation). */
    fun cannotAnnounce(c: Command) = "err ${c.text} cannot-announce"
    /** "Display over other apps" is off, so Android would block the launch; nothing was done. */
    fun cannotLaunch(c: Command.Launch) = "err ${c.text} cannot-launch"
    /** Announced and sent, but the app wasn't seen in front within 5 s; it may still appear later. */
    fun blocked(c: Command.Launch) = "err ${c.text} blocked"
    /** Too many of this kind of command on this channel lately (§3); nothing was done. */
    fun rateLimited(c: Command) = "err ${c.text} rate-limited"

    /** Names the command only when [body] is one on [channel]; never echoes other text. */
    fun staleOf(body: String, channel: Channel, registry: Registry): String =
        parseCommand(body, channel, registry)?.let { "dropped-stale ${it.text}" } ?: "dropped-stale"

    /**
     * [launchCheck]: whether launches are checked to reach the front (usage access granted); off,
     * `ok launch` is unverified. [nowMs]: the phone's wall clock, written as `time=` in whole Unix
     * seconds (milliseconds dropped), so a publisher can spot a phone clock that's off.
     */
    fun status(battery: Int, appVersion: String, protocol: String, stop: StopState, launchCheck: Boolean, nowMs: Long): String {
        fun onOff(off: Boolean) = if (off) "off" else "on"
        return "ok status report battery=$battery version=$appVersion protocol=$protocol " +
            "owner=${onOff(stop.all)} family=${onOff(stop.all || stop.family)} launch-check=${onOff(!launchCheck)} " +
            "time=${Math.floorDiv(nowMs, 1000L)}"
    }
}
