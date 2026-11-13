package org.intfar.mabridge.protocol

/**
 * The cap on `dropped-stale` receipts for a backlog replayed on reconnect (PROTOCOL §6).
 * ntfy sends an `open` event, with its server time, before the cached messages it
 * replays; a stale message older than that time is part of the backlog. The first [CAP]
 * of those get receipts and the rest are counted, so a fresh command's receipt isn't
 * queued behind them. A stale message newer than the open event (a phone clock that's
 * off, say) always gets its receipt: it's the sign something is wrong.
 */
class StaleReceipts {
    private var openTimeSec: Long? = null
    private var sent = 0
    private var suppressed = 0

    /** Whether a stale message with server time [timeSec] gets a receipt (one sent in the open's own second is live). */
    @Synchronized
    fun shouldSend(timeSec: Long): Boolean {
        val open = openTimeSec ?: return true
        if (timeSec >= open) return true
        if (sent < CAP) { sent++; return true }
        suppressed++
        return false
    }

    /** A new connection opened at server time [openTimeSec]. Returns how many receipts the last backlog left unsent. */
    @Synchronized
    fun connected(openTimeSec: Long): Int = suppressed.also {
        this.openTimeSec = openTimeSec
        sent = 0
        suppressed = 0
    }

    /**
     * The backlog's unsent count, cleared: called when the replay is over (the first live
     * message, the stream's end, the service stopping), so the log line lands on time.
     */
    @Synchronized
    fun flush(): Int = suppressed.also { suppressed = 0 }

    companion object {
        const val CAP = 5
    }
}
