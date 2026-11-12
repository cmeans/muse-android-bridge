package org.intfar.mabridge.protocol

/** ntfy message IDs seen in the rolling dedup window (PROTOCOL.md §2.3). */
interface SeenStore {
    /** True if [id] wasn't seen in the last [DEDUP_WINDOW_MS]; records it either way. */
    fun markIfNew(id: String, nowMs: Long): Boolean

    /** True if [id] was seen in the window; records nothing. */
    fun wasSeen(id: String, nowMs: Long): Boolean
}

const val DEDUP_WINDOW_MS = 24L * 60 * 60 * 1000

/** Drops entries older than the window from [seen], in place. */
fun pruneSeen(seen: MutableMap<String, Long>, nowMs: Long) {
    seen.entries.removeAll { nowMs - it.value > DEDUP_WINDOW_MS }
}

class InMemorySeenStore : SeenStore {
    private val seen = HashMap<String, Long>()

    @Synchronized
    override fun markIfNew(id: String, nowMs: Long): Boolean {
        pruneSeen(seen, nowMs)
        if (id in seen) return false
        seen[id] = nowMs
        return true
    }

    @Synchronized
    override fun wasSeen(id: String, nowMs: Long): Boolean {
        pruneSeen(seen, nowMs)
        return id in seen
    }
}
