package org.intfar.mabridge.protocol

/**
 * Per-channel limits on the commands that act on the phone (PROTOCOL §3, §6):
 * at most [RING_LIMIT] `ring find` and [LAUNCH_LIMIT] launches (all apps
 * together) per [WINDOW_MS] on each channel. A refused command doesn't count
 * toward the limit, so a looping publisher can't keep the window full.
 * Status reports and the RustDesk placeholders aren't limited.
 */
class RateLimiter {
    private val runs = HashMap<Pair<Channel, String>, ArrayDeque<Long>>()

    @Synchronized
    fun allow(channel: Channel, command: Command, nowMs: Long): Boolean {
        val (kind, limit) = when (command) {
            Command.RingFind -> "ring" to RING_LIMIT
            is Command.Launch -> "launch" to LAUNCH_LIMIT
            else -> return true
        }
        val times = runs.getOrPut(channel to kind) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() >= WINDOW_MS) times.removeFirst()
        if (times.size >= limit) return false
        times.addLast(nowMs)
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val RING_LIMIT = 3
        const val LAUNCH_LIMIT = 10
    }
}
