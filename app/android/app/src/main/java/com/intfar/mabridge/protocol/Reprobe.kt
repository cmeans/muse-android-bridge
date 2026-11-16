package com.intfar.mabridge.protocol

/** How often the refused member topics are asked about again (design §2). */
const val PROBE_EVERY_MS = 15 * 60_000L

/**
 * When the refused member topics are next asked about, on `SystemClock.elapsedRealtime`'s clock
 * (it counts deep sleep, unlike the Handler timer's uptime): [nowMs] plus [PROBE_EVERY_MS], or
 * null when nothing is refused.
 */
fun reprobeDueAt(refused: List<String>, nowMs: Long): Long? =
    if (refused.isEmpty()) null else nowMs + PROBE_EVERY_MS

/**
 * Whether the re-probe due at [dueAtMs] (from [reprobeDueAt]) should run at [nowMs]. The service
 * checks it from the Handler tick and from every line read, so deep sleep can't stretch the
 * 15 minutes much past the next keepalive.
 */
fun isReprobeDue(dueAtMs: Long?, nowMs: Long): Boolean = dueAtMs != null && nowMs >= dueAtMs

/** What a subscribe does after ntfy refused the whole list and each topic was asked about alone. */
sealed interface ProbeRound {
    /** The owner topic answered [code], not 200: logged as itself, with the usual backoff. */
    data class OwnerRefused(val code: Int) : ProbeRound

    /** Subscribe to [topics]; [refused] are the member slugs left out, in setup order. */
    data class Partial(val topics: List<String>, val refused: List<String>) : ProbeRound

    /**
     * Every topic answered 200 alone, so the refusal came from a grant made in between. With
     * [reconnectNow] the bridge subscribes again at once; false when the round before ended the
     * same way, so a server that keeps doing it gets the usual backoff, not a tight loop.
     */
    data class AllReadable(val reconnectNow: Boolean) : ProbeRound
}

/**
 * The [ProbeRound] for [probed], the round's answers (the [owner] topic first; a member not
 * reached counts as refused). [memberSlugs] are the members set up on [device], in setup order.
 * [retriedAllReadable]: the previous round was [ProbeRound.AllReadable] and reconnected at once.
 */
fun probeRound(
    owner: String,
    device: String,
    memberSlugs: List<String>,
    probed: Map<String, Int>,
    retriedAllReadable: Boolean,
): ProbeRound {
    val ownerCode = probed[owner] ?: -1
    if (ownerCode != 200) return ProbeRound.OwnerRefused(ownerCode)
    val readable = topicsAfterProbe(owner, probed)
    val refused = memberSlugs.filter { memberTopic(device, it) !in readable }
    if (refused.isEmpty()) return ProbeRound.AllReadable(reconnectNow = !retriedAllReadable)
    return ProbeRound.Partial(readable, refused)
}

/**
 * Whether [stream] is the one the bridge ended itself, to reconnect with a newly readable member
 * topic: [target] is the stream it meant to end. Tied to the stream, not a flag, so a target that
 * had already closed can't mute the next stream's "stream ended" or skip its backoff.
 */
fun endedByBridge(target: Any?, stream: Any?): Boolean = target != null && target === stream
