package org.intfar.mabridge.protocol

import kotlin.math.abs

/** One ntfy message, as the parser hands it over. [timeSec] is ntfy's server time. */
data class IncomingMessage(val id: String, val timeSec: Long, val topic: String, val body: String)

/** The owner's STOP switches (PROTOCOL.md §7). */
data class StopState(val all: Boolean, val family: Boolean)

/** What to do with a message. Receipts go to the ack topic; logs to the on-phone log. */
sealed interface Decision {
    data class Ignore(val log: String) : Decision
    data class DropStale(val receipt: String, val log: String) : Decision
    data class DropDuplicate(val log: String) : Decision
    data class Reject(val receipt: String, val log: String = "") : Decision
    data class Run(val command: Command, val channel: Channel) : Decision
}

const val TTL_SECONDS = 120L

/**
 * The checks of PROTOCOL.md §5, in order: command topic, freshness (§2.3,
 * either direction), dedup (recorded before STOP is read), exact registry
 * match, channel enabled.
 */
class Gate(
    private val slug: String,
    private val registry: Registry,
    private val seen: SeenStore,
    private val clockMs: () -> Long,
    private val stop: () -> StopState,
) {
    fun decide(msg: IncomingMessage): Decision {
        val excerpt = logExcerpt(msg.body)
        if (msg.id.isBlank()) return Decision.Ignore("ignored: no message id: $excerpt")
        val channel = channelForTopic(msg.topic, slug)
            ?: return Decision.Ignore("ignored: topic ${logExcerpt(msg.topic)}: $excerpt")
        val name = channel.name.lowercase()

        val nowMs = clockMs()
        val age = nowMs / 1000 - msg.timeSec
        if (abs(age) > TTL_SECONDS) {
            return Decision.DropStale(
                Receipts.staleOf(msg.body, channel, registry),
                "dropped-stale ($name, age ${age}s): $excerpt",
            )
        }
        if (!seen.markIfNew(msg.id, nowMs)) {
            return Decision.DropDuplicate("duplicate ($name) ${logExcerpt(msg.id)}: $excerpt")
        }

        val command = parseCommand(msg.body, channel, registry)
            ?: return Decision.Reject(Receipts.unknown(), "unknown-command ($name): $excerpt")

        val s = stop()
        if (s.all || (channel == Channel.FAMILY && s.family)) {
            return Decision.Reject(Receipts.disabled(command), "disabled ($name): ${command.text}")
        }
        return Decision.Run(command, channel)
    }
}
