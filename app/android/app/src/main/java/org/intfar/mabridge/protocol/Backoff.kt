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
}
