# Tasker executor — research notes

> History (2026-11-16): the topic names below are the pre-0.5 ones. Protocol
> 0.5 retired the shared `mab-pilot-phone-family` topic for per-member
> `mab-<device>-family-<member>` topics (PROTOCOL §2.1). Reference only.

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
   Fallback; fragile against notification formatting and muting behavior.
3. **Tasker HTTP long-poll loop** on the topic's JSON endpoint. Rejected
   unless 1–2 fail: worst battery profile of the three.

### Extras — verified from the app source (2026-11-11)

Read in `binwiederhier/ntfy-android`,
`app/src/main/java/io/heckel/ntfy/msg/BroadcastService.kt` — the source
states these constants "cannot be changed without breaking the contract."
`MESSAGE_RECEIVED` carries, among others:

| Extra | Use in this design |
|---|---|
| `id` | Dedup key (PROTOCOL §2.3) ✔ present |
| `time` | Message timestamp, epoch seconds (Int) — TTL check ✔ present |
| `topic` | Channel routing (owner vs family topic) |
| `message` | The command body |
| `title`, `tags`, `priority` | Available; registry matching ignores them |
| `muted` / `muted_str` | The broadcast itself reports whether the topic is muted — strong sign it fires for muted topics too; device test confirms |

So both protocol mechanisms (dedup + 120 s TTL) are implementable in the
Tasker executor with no fallback freshness hack. In Tasker these arrive as
the Intent Received event's extras (e.g. `%id`, `%time`, `%topic`,
`%message` — variable naming to be confirmed on the pilot phone).

### Still open for the A1 device test

- Confirm the broadcast fires with the topic muted, and log the actual
  Tasker variable names for the extras above.
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

## A1 device confirmation (2026-11-11 22:34 CT, the pilot phone)

- Import dialect settled by trial: **classic export structure works** —
  `<EventCat sr="con0" ve="2">` in the profile, named task with `<pri>`,
  `tv="6.4.15"`. The generator's bare `<Event>` form was rejected by the
  device ("failed to import profile data") even at matching tv.
- Tasker will **not** re-import a project whose name already exists
  ("a project with that name already exists") — delete-then-import, or
  ship new names. Standalone `.tsk.xml` in bare TaskerData form fails
  with "bad packed data format" — do not use that route.
- Send Intent **Target: 0=Broadcast Receiver, 1=Activity, 2=Service**
  (official catalog). v4 shipped 0 and silently did nothing; v5 with 1
  opened the ntfy subscribe screens via `ntfy://` deep links, and both
  bridge topics were subscribed with two taps total, zero typing.
- **Receive path CONFIRMED live** (topic names here are the generic
  example; the live test used the reference deployment's own slug): messages published to both
  `mab-pilot-phone-owner` (`launch meet`) and `mab-pilot-phone-family`
  (`launch whatsapp`) produced the probe Flash with `%topic` and
  `%message` correctly resolved. Cached messages were also delivered on
  subscribe (owner topic showed all 3 earlier tests) — the TTL/dedup
  machinery in PROTOCOL §2 is load-bearing, not decorative.
- Still open in A: muted-topic broadcast behavior, VPN interference,
  50-message reliability run, 48 h battery cost. `%id` accessibility in
  Tasker actions is still unverified (probe logged topic/time/message/
  muted only); executor v1 will carry a harmless `%id` log line to settle
  it — unresolved variables stay literal, which is itself the answer.
