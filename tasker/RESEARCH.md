# Tasker executor — research notes

## A1 receive mechanism (desk research, 2026-11-11; device test still owed)

The ntfy Android app officially integrates with automation apps via Android
intents (ntfy docs, "subscribe/phone"):

- On each incoming message the app broadcasts
  `io.heckel.ntfy.MESSAGE_RECEIVED`. Tasker can trigger on this with an
  **Intent Received** event, reading the message extras (topic, message,
  and more — the docs defer to the app source for the full extras list).
- The same docs suggest muting the topic in the ntfy app when topics drive
  automation, so commands don't pile up as visible notifications.
- Sending is symmetric (`io.heckel.ntfy.SEND_MESSAGE`), but Tasker's own
  HTTP Request action is simpler for publishing receipts to the ack topic.

### Candidate ranking for the pilot

1. **Broadcast intent → Tasker Intent Received** (primary). Documented,
   purpose-built, no notification scraping.
2. **Notification intercept** (Profile → Event → Notification, app = ntfy).
   Fallback; fragile against notification formatting and muting behaviour.
3. **Tasker HTTP long-poll loop** on the topic's JSON endpoint. Rejected
   unless 1–2 fail: worst battery profile of the three.

### Open specifics the A1 device test must answer

- Full extras list of `MESSAGE_RECEIVED` on the installed app version —
  critically whether a **message ID** (dedup, PROTOCOL §2.3) and a
  **timestamp** (TTL check) are included. If no timestamp extra exists, the
  executor needs a fallback freshness check (e.g. reject when the intent
  arrives >120 s after the executor's own subscribe poll, or query the
  topic JSON once per command). Log every extra on the pilot phone.
- Does the broadcast still fire when the topic is muted in the ntfy app?
- Delivery path on this self-hosted server: the Play app uses FCM only for
  ntfy.sh itself; for this server it relies on its own persistent
  connection plus the server's `upstream-base-url` wake-up. Measure
  real-world latency with the phone's VPN on (test A1/A2).
- Instant-delivery / persistent-connection battery cost over 48 h (A5).

## Provisioning verification already on record

The 2026-11-11 ntfy provisioning checks (see `server/ntfy-provisioning.md`)
already cover the core of test F2: the phone credential can read only its
two command topics, cannot publish to them (403), cannot read the ack topic
or unrelated topics (403). F2's remaining piece is doing it from a second
device rather than the server itself.
