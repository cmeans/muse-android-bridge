package org.intfar.mabridge.protocol

/** Receipt bodies for the ack topic, exactly as listed in PROTOCOL.md §6. */
object Receipts {
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

    /** [launchCheck]: whether launches are checked to reach the front (usage access granted); off, `ok launch` is unverified. */
    fun status(battery: Int, appVersion: String, protocol: String, stop: StopState, launchCheck: Boolean): String {
        fun onOff(off: Boolean) = if (off) "off" else "on"
        return "ok status report battery=$battery version=$appVersion protocol=$protocol " +
            "owner=${onOff(stop.all)} family=${onOff(stop.all || stop.family)} launch-check=${onOff(!launchCheck)}"
    }
}
