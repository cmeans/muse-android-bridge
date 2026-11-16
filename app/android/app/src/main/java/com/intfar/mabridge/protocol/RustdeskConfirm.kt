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

/**
 * What happened to the waiting request. SETUP_CHANGED: a setup save changed the server or the
 * device name ([setupSaveAnswer]).
 */
enum class ConfirmEvent { OPEN, NOT_NOW, TIMEOUT, STOPPED, RESTARTED, SETUP_CHANGED }

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
        ConfirmEvent.SETUP_CHANGED ->
            ConfirmOutcome.Answer(Receipts.notConfirmed(RUSTDESK), "not-confirmed ($who): ${RUSTDESK.text} (setup changed)")
        ConfirmEvent.STOPPED ->
            ConfirmOutcome.Answer(Receipts.disabled(RUSTDESK), "disabled ($who): ${RUSTDESK.text} (while waiting)")
    }
}

/**
 * The event to decide on at [nowMs]: an OPEN, NOT_NOW or STOPPED on a request whose deadline has
 * passed counts as [ConfirmEvent.TIMEOUT], so a late answer (the timer stops in deep sleep, or
 * the unlock took past the deadline) is `not-confirmed` whatever it was (PROTOCOL §5) and
 * launches nothing. RESTARTED and SETUP_CHANGED already answer `not-confirmed` and keep their
 * own log reasons.
 * Every other event, and any event with nothing pending, is unchanged.
 */
fun effectiveEvent(pending: PendingConfirm?, event: ConfirmEvent, nowMs: Long): ConfirmEvent =
    if (pending != null && event in LATE_IS_TIMEOUT && isExpired(pending, nowMs)) {
        ConfirmEvent.TIMEOUT
    } else {
        event
    }

private val LATE_IS_TIMEOUT = setOf(ConfirmEvent.OPEN, ConfirmEvent.NOT_NOW, ConfirmEvent.STOPPED)

/** Whether [pending] has run out at [nowMs] (same clock as its deadline). */
fun isExpired(pending: PendingConfirm, nowMs: Long): Boolean = nowMs >= pending.deadlineMs

/**
 * The new pending request, or null when one is already waiting (the caller sends
 * [Receipts.pending]). The caller resolves an expired one as [ConfirmEvent.TIMEOUT] first.
 */
fun onConfirmRequest(pending: PendingConfirm?, id: String, member: String, nowMs: Long): PendingConfirm? =
    if (pending != null) null else PendingConfirm(id, member, nowMs + CONFIRM_TIMEOUT_MS)

/**
 * The event the service decides a waiting request on, given [running] and [stop] as read under
 * the confirm lock, before [effectiveEvent]. Null on a stopped bridge: nothing resolves, since a
 * receipt could no longer be sent and an OPEN would launch with its receipt dropped; the stored
 * request is answered as RESTARTED at the next start. An OPEN while STOP all or STOP family is
 * on becomes STOPPED (`disabled`), so a STOP switched on after the tap but before the lock can't
 * let RustDesk open. Every other event is unchanged.
 */
fun eventUnderLock(event: ConfirmEvent, running: Boolean, stop: StopState): ConfirmEvent? = when {
    !running -> null
    event == ConfirmEvent.OPEN && (stop.all || stop.family) -> ConfirmEvent.STOPPED
    else -> event
}

/** What a new family `launch rustdesk` gets once it holds the confirm lock ([confirmRequestFor]). */
sealed interface ConfirmRequest {
    /** STOP all or STOP family is on: answer `disabled`, store nothing, post no prompt. */
    object Disabled : ConfirmRequest
    /** Another request is waiting: answer `pending`. */
    object Busy : ConfirmRequest
    /** Store [pending] and ask the owner. */
    data class Ask(val pending: PendingConfirm) : ConfirmRequest
}

/**
 * The decision for a new family `launch rustdesk`, made under the confirm lock with [stop] read
 * there too: the gate read STOP earlier, and a STOP switched on since must not leave the owner a
 * prompt after pressing it (its listener's STOPPED finds nothing stored yet). Any STOP wins, even
 * over a waiting request, which that STOPPED answers. The caller resolves an expired request as
 * [ConfirmEvent.TIMEOUT] first.
 */
fun confirmRequestFor(stop: StopState, pending: PendingConfirm?, id: String, member: String, nowMs: Long): ConfirmRequest {
    if (stop.all || stop.family) return ConfirmRequest.Disabled
    return onConfirmRequest(pending, id, member, nowMs)?.let { ConfirmRequest.Ask(it) } ?: ConfirmRequest.Busy
}

/**
 * Whether saving [new] over [old] moves where receipts go: the server, or the device name in the
 * ack topic `mab-<device>-ack`. State that belonged to the old place ("last connected", the
 * resume id, a waiting request) doesn't carry over.
 */
fun ackDestinationChanged(old: Setup, new: Setup): Boolean = old.server != new.server || old.slug != new.slug

/**
 * What a setup save does with the waiting request: when it moves the receipts' destination
 * ([ackDestinationChanged]), the save answers [pending] as SETUP_CHANGED (`not-confirmed`) on the
 * old server and ack topic, with the old credential, before writing the new setup (QA r1);
 * answered at the next start instead, it would go to the new place, which has no use for it and
 * may refuse it. Null when nothing waits or the destination stays: every save restarts a running
 * bridge, which then answers it as RESTARTED on the unchanged server.
 */
fun setupSaveAnswer(pending: PendingConfirm?, old: Setup, new: Setup): ConfirmOutcome.Answer? {
    if (pending == null || !ackDestinationChanged(old, new)) return null
    return confirmOutcome(pending, ConfirmEvent.SETUP_CHANGED, pending.id) as ConfirmOutcome.Answer
}
