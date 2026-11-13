package org.intfar.mabridge.protocol

/**
 * The cap on `dropped-stale` receipts for a replayed backlog (PROTOCOL §6): after a
 * reconnect, the first [CAP] stale messages get receipts and the rest are logged only,
 * so a fresh command's receipt isn't queued behind them. The cap covers the catch-up
 * replay only: once a fresh message arrives the backlog is over, and later stale ones
 * (a phone clock that's off, say) get their receipts again, since they're the sign.
 */
class StaleReceipts {
    private var sent = 0
    private var inBacklog = true

    /** Stale receipts left unsent in the backlog so far. */
    var suppressed = 0
        private set

    /** Whether this stale message gets a receipt. */
    @Synchronized
    fun shouldSend(): Boolean {
        if (!inBacklog || sent < CAP) { sent++; return true }
        suppressed++
        return false
    }

    /** A fresh message arrived: the backlog is over. Returns how many receipts it left unsent. */
    @Synchronized
    fun freshArrived(): Int = suppressed.also { inBacklog = false; suppressed = 0 }

    /** A new connection: a new backlog may follow. Returns how many the last one left unsent. */
    @Synchronized
    fun connected(): Int = suppressed.also { sent = 0; suppressed = 0; inBacklog = true }

    companion object {
        const val CAP = 5
    }
}
