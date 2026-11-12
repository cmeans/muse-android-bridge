package org.intfar.mabridge.protocol

/** Receipt bodies for the ack topic, exactly as listed in PROTOCOL.md §6. */
object Receipts {
    fun ok(c: Command) = "ok ${c.text}"
    fun notInstalled(c: Command.Launch) = "err ${c.text} not-installed"
    fun disabled(c: Command) = "err ${c.text} disabled"
    fun notImplemented(c: Command) = "err ${c.text} not-implemented"
    fun unknown() = "err unknown-command"

    /** Names the command only when [body] is one on [channel]; never echoes other text. */
    fun staleOf(body: String, channel: Channel, registry: Registry): String =
        parseCommand(body, channel, registry)?.let { "dropped-stale ${it.text}" } ?: "dropped-stale"

    fun status(battery: Int, appVersion: String, protocol: String, stop: StopState): String {
        fun onOff(off: Boolean) = if (off) "off" else "on"
        return "ok status report battery=$battery version=$appVersion protocol=$protocol " +
            "owner=${onOff(stop.all)} family=${onOff(stop.all || stop.family)}"
    }
}
