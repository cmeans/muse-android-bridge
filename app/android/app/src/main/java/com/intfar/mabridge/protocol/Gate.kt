package com.intfar.mabridge.protocol

/** One ntfy message, as the parser hands it over. [timeSec] is ntfy's server time. */
data class IncomingMessage(val id: String, val timeSec: Long, val topic: String, val body: String)

/** The owner's STOP switches (PROTOCOL.md §7). */
data class StopState(val all: Boolean, val family: Boolean) {
    /** Whether these switches turn [channel] off: STOP all turns off both, STOP family only the family channel. */
    fun blocks(channel: Channel): Boolean = all || (channel == Channel.FAMILY && family)
}

/** What a command that passed the gate does once it holds the service's run lock ([atRunLock]). */
enum class RunLockCheck {
    /** Run it. */
    RUN,
    /** STOP turned its channel off while it waited: answer `disabled`, run nothing. */
    DISABLED,
    /** The bridge stopped while it waited: run nothing. */
    STOPPED,
}

/**
 * The check made again after a command takes the run lock: it can wait there up to 5 s behind
 * another command's launch check, and STOP or Stop bridge may come in that wait (#33 item 21).
 * [running] and [stop] are read after the lock is taken. A stopped bridge wins over STOP.
 */
fun atRunLock(running: Boolean, stop: StopState, channel: Channel): RunLockCheck = when {
    !running -> RunLockCheck.STOPPED
    stop.blocks(channel) -> RunLockCheck.DISABLED
    else -> RunLockCheck.RUN
}

/** What to do with a message. Receipts go to the ack topic; logs to the on-phone log. */
sealed interface Decision {
    data class Ignore(val log: String) : Decision
    /** [id]: the stale message's own id, so its receipt names it, not a later command (0.4). */
    data class DropStale(val receipt: String, val id: String, val log: String) : Decision
    data class DropDuplicate(val log: String) : Decision
    /** [id]: the rejected message's id, for its receipt (0.4). */
    data class Reject(val receipt: String, val id: String, val log: String = "") : Decision
    data class Run(val command: Command, val source: Source) : Decision
}

const val TTL_SECONDS = 120L

/**
 * The checks of PROTOCOL.md §5, in order: command topic (the owner's or a member's), freshness (§2.3,
 * either direction), dedup (recorded before STOP is read), exact registry
 * match, channel enabled.
 */
class Gate(
    private val slug: String,
    private val registry: Registry,
    private val seen: SeenStore,
    private val clockMs: () -> Long,
    private val stop: () -> StopState,
    private val members: () -> List<FamilyMember>,
) {
    fun decide(msg: IncomingMessage): Decision {
        val excerpt = logExcerpt(msg.body)
        // 0.4 echoes the id in the receipt, so an id outside the grammar gets no receipt at all.
        if (!isValidMessageId(msg.id)) return Decision.Ignore("ignored: bad message id: $excerpt")
        val source = sourceForTopic(msg.topic, slug, members())
            ?: return Decision.Ignore("ignored: topic ${logExcerpt(msg.topic)}: $excerpt")
        val channel = source.channel
        val tag = source.logTag()

        val nowMs = clockMs()
        val nowSec = nowMs / 1000
        // A range check, not abs(now - time): abs overflows for extreme times.
        if (msg.timeSec !in (nowSec - TTL_SECONDS)..(nowSec + TTL_SECONDS)) {
            // Recorded as seen too: a replayed copy of a message already handled, or a
            // second copy of a stale one, is a duplicate whose first receipt stands (§6).
            if (!seen.markIfNew(msg.id, nowMs)) {
                return Decision.DropDuplicate("duplicate ($tag, replayed) ${logExcerpt(msg.id)}: $excerpt")
            }
            val age = if (msg.timeSec <= nowSec) "${nowSec - msg.timeSec}s old" else "${msg.timeSec - nowSec}s ahead"
            return Decision.DropStale(
                Receipts.staleOf(msg.body, channel, registry),
                msg.id,
                "dropped-stale ($tag, $age): $excerpt",
            )
        }
        if (!seen.markIfNew(msg.id, nowMs)) {
            return Decision.DropDuplicate("duplicate ($tag) ${logExcerpt(msg.id)}: $excerpt")
        }

        val command = parseCommand(msg.body, channel, registry)
            ?: return Decision.Reject(Receipts.unknown(), msg.id, "unknown-command ($tag): $excerpt")

        val s = stop()
        if (s.blocks(channel)) {
            return Decision.Reject(Receipts.disabled(command), msg.id, "disabled ($tag): ${command.text}")
        }
        return Decision.Run(command, source)
    }
}
