package com.intfar.mabridge.protocol

/** How long a confirm prompt waits after the phone handles the command: 2 minutes (design §4). */
const val CONFIRM_TIMEOUT_MS = 120_000L

/** Whether [command] from [source] waits for a tap first: only `launch rustdesk` from the family channel. */
fun needsConfirm(command: Command, source: Source): Boolean =
    command == Command.Launch("rustdesk") && source.channel == Channel.FAMILY

/**
 * The one request waiting for a tap. [member] is the member's slug, never the display name.
 * [deadlineMs] is on `SystemClock.elapsedRealtime`'s clock, so it means nothing after a reboot,
 * where only [id] is used ([ConfirmEvent.RESTARTED]).
 */
data class PendingConfirm(val id: String, val member: String, val deadlineMs: Long)

/** What happened to the waiting request. */
enum class ConfirmEvent { OPEN, NOT_NOW, TIMEOUT, STOPPED, RESTARTED }

/** What to do about a [ConfirmEvent]: launch, or answer with [receipt] (bare, no `id=`) and write [log]. */
sealed interface ConfirmOutcome {
    object Launch : ConfirmOutcome
    data class Answer(val receipt: String, val log: String) : ConfirmOutcome
}

private val RUSTDESK = Command.Launch("rustdesk")

/**
 * The outcome of [event] for [pending], or null when nothing is pending or [eventId] isn't the
 * pending request's id (a stale tap). Receipts are bare; the service adds `id=` with [Receipts.withId].
 */
fun confirmOutcome(pending: PendingConfirm?, event: ConfirmEvent, eventId: String): ConfirmOutcome? {
    if (pending == null || eventId != pending.id) return null
    val who = "family/${pending.member}"
    return when (event) {
        ConfirmEvent.OPEN -> ConfirmOutcome.Launch
        ConfirmEvent.NOT_NOW ->
            ConfirmOutcome.Answer(Receipts.declined(RUSTDESK), "declined ($who): ${RUSTDESK.text}")
        ConfirmEvent.TIMEOUT ->
            ConfirmOutcome.Answer(Receipts.notConfirmed(RUSTDESK), "not-confirmed ($who): ${RUSTDESK.text} (timeout)")
        ConfirmEvent.RESTARTED ->
            ConfirmOutcome.Answer(Receipts.notConfirmed(RUSTDESK), "not-confirmed ($who): ${RUSTDESK.text} (restart)")
        ConfirmEvent.STOPPED ->
            ConfirmOutcome.Answer(Receipts.disabled(RUSTDESK), "disabled ($who): ${RUSTDESK.text} (while waiting)")
    }
}

/**
 * The event to decide on at [nowMs]: an OPEN or NOT_NOW tap on a request whose deadline has
 * passed counts as [ConfirmEvent.TIMEOUT], so a late tap (the timer stops in deep sleep, or the
 * unlock took past the deadline) answers `not-confirmed` and launches nothing. Every other
 * event, and any event with nothing pending, is unchanged.
 */
fun effectiveEvent(pending: PendingConfirm?, event: ConfirmEvent, nowMs: Long): ConfirmEvent =
    if (pending != null && (event == ConfirmEvent.OPEN || event == ConfirmEvent.NOT_NOW) && isExpired(pending, nowMs)) {
        ConfirmEvent.TIMEOUT
    } else {
        event
    }

/** Whether [pending] has run out at [nowMs] (same clock as its deadline). */
fun isExpired(pending: PendingConfirm, nowMs: Long): Boolean = nowMs >= pending.deadlineMs

/**
 * The new pending request, or null when one is already waiting (the caller sends
 * [Receipts.pending]). The caller resolves an expired one as [ConfirmEvent.TIMEOUT] first.
 */
fun onConfirmRequest(pending: PendingConfirm?, id: String, member: String, nowMs: Long): PendingConfirm? =
    if (pending != null) null else PendingConfirm(id, member, nowMs + CONFIRM_TIMEOUT_MS)
