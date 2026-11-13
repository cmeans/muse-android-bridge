package org.intfar.mabridge.protocol

/**
 * Reconnect delays: [baseMs] doubling to [capMs], each with 0-20 % added
 * jitter so a restarted server isn't hit by every phone at once.
 */
class Backoff(
    private val baseMs: Long = 2_000,
    private val capMs: Long = 60_000,
    private val random: () -> Double = Math::random,
) {
    private var current = baseMs

    fun nextDelayMs(): Long {
        val delay = current
        current = (current * 2).coerceAtMost(capMs)
        return delay + (delay * 0.2 * random()).toLong()
    }

    fun reset() {
        current = baseMs
    }

    /**
     * A stream that stayed up [stableMs] or longer resets the delays. ntfy sends an
     * `open` line first on every connect, so resetting on the first line would let a
     * connection dropped right after opening reconnect every 2 s forever.
     */
    fun streamEnded(lifetimeMs: Long, stableMs: Long = 60_000) {
        if (lifetimeMs >= stableMs) reset()
    }
}
