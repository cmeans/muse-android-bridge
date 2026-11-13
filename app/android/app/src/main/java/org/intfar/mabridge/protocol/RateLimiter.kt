package org.intfar.mabridge.protocol

/**
 * Per-channel limits on the commands that act on the phone (PROTOCOL §3, §6):
 * at most [RING_LIMIT] `ring find` and [LAUNCH_LIMIT] launches (all apps
 * together) that ran per [WINDOW_MS] on each channel. [allows] checks before a
 * command runs; [record] counts it once it did something (opened an app,
 * started a ring), so a refused command, a launch of an app that isn't
 * installed, or a `ring find` that joined a ring already going doesn't use up
 * the budget. Status reports and the RustDesk placeholders aren't limited.
 *
 * Times must come from a clock that never goes back (SystemClock.elapsedRealtime
 * on Android): a wall-clock correction would otherwise strand stored times in
 * the future and refuse commands for as long as the jump.
 */
class RateLimiter {
    private val runs = HashMap<Pair<Channel, String>, ArrayDeque<Long>>()

    private fun budget(command: Command): Pair<String, Int>? = when (command) {
        Command.RingFind -> "ring" to RING_LIMIT
        is Command.Launch -> "launch" to LAUNCH_LIMIT
        else -> null
    }

    private fun recent(channel: Channel, kind: String, nowMs: Long): ArrayDeque<Long> {
        val times = runs.getOrPut(channel to kind) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() >= WINDOW_MS) times.removeFirst()
        return times
    }

    @Synchronized
    fun allows(channel: Channel, command: Command, nowMs: Long): Boolean {
        val (kind, limit) = budget(command) ?: return true
        return recent(channel, kind, nowMs).size < limit
    }

    @Synchronized
    fun record(channel: Channel, command: Command, nowMs: Long) {
        val (kind, _) = budget(command) ?: return
        recent(channel, kind, nowMs).addLast(nowMs)
    }

    private val refusalReceipts = HashMap<Pair<Channel, String>, Long>()

    /**
     * Whether a refused command gets an `err <command> rate-limited` receipt: the first
     * refusal per channel and kind in a window does, the rest are logged only, so a looping
     * publisher can't fill the receipt queue.
     */
    @Synchronized
    fun shouldReceiptRefusal(channel: Channel, command: Command, nowMs: Long): Boolean {
        val (kind, _) = budget(command) ?: return true
        val last = refusalReceipts[channel to kind]
        if (last != null && nowMs - last < WINDOW_MS) return false
        refusalReceipts[channel to kind] = nowMs
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val RING_LIMIT = 3
        const val LAUNCH_LIMIT = 10
    }
}
