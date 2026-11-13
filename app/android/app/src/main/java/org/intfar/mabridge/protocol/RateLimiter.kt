package org.intfar.mabridge.protocol

/**
 * Per-channel limits on the commands that act on the phone (PROTOCOL §3, §6):
 * at most [RING_LIMIT] `ring find` and [LAUNCH_LIMIT] launches (all apps
 * together) per [WINDOW_MS] on each channel. Every command that passes the
 * STOP switches counts ([allows], then [record] before it runs), whatever it
 * then does: one that joins a ring already going still announces, so it counts
 * too. A command refused here doesn't count. Status reports and the RustDesk
 * placeholders aren't limited.
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
        // A run of refusals ended: the next refusal gets its receipt again.
        refusalReceipts.remove(channel to kind)
    }

    private val refusalReceipts = HashMap<Pair<Channel, String>, Long>()

    /**
     * Whether a refused command gets an `err <command> rate-limited` receipt: the first
     * refusal of a run (per channel and kind) does, and the rest of that run within a
     * window are logged only, so a looping publisher can't fill the receipt queue. A
     * command that runs ends the run.
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
