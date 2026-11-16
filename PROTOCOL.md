# muse-android-bridge — Wire Protocol

**Version:** 0.5 (DRAFT, 2026-11-16). 0.5 gives each family member their
own topic, so the phone knows who asked (§2.1, §3), names that member in
announcements (§5 step 4), and holds a family `launch rustdesk` until the
owner taps Open on the phone (§5, confirm flow); it adds the `declined`,
`not-confirmed` and `pending` receipts (§6) and retires the shared family
topic (§9); the maintainer approved the approach on 2026-11-15.

0.4 adds the command's message id
to every receipt (§6), so a publisher can match each receipt to its
command exactly; the maintainer decided it on 2026-11-13 (mcp-phone-commands
design Q1), reversing 0.3's no-ids rule; and the phone's clock to `status
report` (`time=`, maintainer, 2026-11-14). 0.3 adds the `cannot-launch`,
`blocked` and `rate-limited` receipts (§6), per-channel rate limits
(§3, §5), a cap on `dropped-stale` receipts for a replayed backlog
(§6), and `launch-check` in `status report`; the maintainer decided the
receipts, limits and cap on issues #7 and #8 on 2026-11-12, and the
details (what counts toward a limit, `launch-check`) come from review
the same day. 0.2 records the packages verified on the pilot phone
(§4.3), fixes the receipt list (§6), and states that a channel disabled
by STOP still marks a message as seen (§5); the maintainer approved
those on 2026-11-12. Five more came from review on the same day, and the
maintainer approved them on the PR later that day: the `cannot-announce` and
`failed` receipts (§6), the cache catch-up note (§2.3), every message on
a command topic recorded as seen, stale or not (§5), and no receipt for
a duplicate, even a replayed copy of a message already handled (§6).
0.1: 2026-11-11.
**Status:** Phase 0 deliverable. This document is the contract. The Tasker pilot
and the native app are interchangeable executors of this protocol; neither may
extend it unilaterally.

## 1. Purpose and non-goals

Give a Muse agent (and, on separate per-member channels, designated family members) the
ability to ask an Android phone to perform a small, fixed set of actions —
initially, launching apps — that Muse's paired-phone commands do not cover.

Non-goals:

- No remote screen viewing or control (that is RustDesk's job, out of scope).
- No general-purpose command execution. A trigger can never become code.
- No unlocking the phone. A launched app is usable only if the phone is
  unlocked or is unlocked by its owner.
- No silent operation. Every executed command that changes something on
  the phone is announced on it, and the phone logs every command it acts
  on or refuses. `status report` only reads and replies, so it needn't be
  announced (§5 step 4); it is still logged and shown on the owner screen.

## 2. Transport

Commands travel as messages on a self-hosted ntfy server (the server
operator's existing instance; `auth-default-access: deny-all`).

### 2.1 Topics (per phone)

| Topic | Direction | Purpose |
|---|---|---|
| `mab-<device>-owner` | agent → phone | Owner channel: full command registry (§4.1) |
| `mab-<device>-family-<member>` | one family member → phone | Family channel, one topic per member: launch-only subset (§4.2) |
| `mab-<device>-ack` | phone → publishers | Execution receipts (§6) |

`<device>` is a short lowercase slug chosen at provisioning (`pilot-phone`,
`helper-pixel`, `alex-phone`, …). Topic names use hyphens only.

`<member>` is a short slug given at provisioning, which the owner types when
adding the member on the phone (`m1`, `helper-a`): lowercase letters and
digits in hyphen-separated words. It is a neutral id, never a person's name: it travels in every
message's topic field and sits in the server's ACL (§2.2 keeps personal
information out of messages). The member's name lives only in the phone's
setup, which uses it in announcements. Rules:

- The whole topic name is at most 64 characters (ntfy's limit,
  `^[-_A-Za-z0-9]{1,64}$`), so a long device slug leaves a short member slug.
- The slug may not contain the word `family`, and its last word may not be
  `owner` or `ack`. That keeps a member topic from equalling any phone's
  owner, ack or former shared family topic, and from equalling another
  device's member topic.
- A phone has at most 8 members.

The user who writes a member topic is the one whose messages the phone
attributes to that member; the server's ACL (§3) is what makes that true.
The former shared topic `mab-<device>-family` is retired (§9): the phone no
longer reads it, and anything sent there gets no receipt.

### 2.2 Message format

Publish with ntfy's JSON API. The **message body is exactly one command** from
the registry — lowercase, one verb, one argument, single space:

```
{"topic": "mab-pilot-phone-family-m1", "message": "launch meet"}
```

No free text is ever valid. A body that does not match a registry entry
exactly is invalid (§5). Publishers must not put personal information in
messages; commands are the whole vocabulary.

### 2.3 Freshness (TTL) and deduplication

ntfy caches messages (72 h on the reference server) and delivers stored
messages when a phone reconnects. A cached `launch` arriving hours late is a
bug, not a feature.

- Every message's ntfy server timestamp is authoritative.
- An executor MUST discard any message older than **120 seconds** at receipt
  time (clock-skew allowance included) and log it as `dropped-stale`.
- An executor MUST deduplicate by ntfy message ID within a rolling 24 h
  window (at-least-once delivery plus redelivery on reconnect).
- Publishers MUST NOT rely on store-and-forward. If the phone is offline, the
  correct outcome is that the command expires, not that it fires later.
- An executor MAY catch up from the server cache on reconnect (ntfy's
  `since=`), so a command sent during a short reconnect gap still arrives;
  the TTL and dedup above make that safe, and every cached message older
  than 120 s is dropped as stale.

## 3. Authentication and authorization

The ntfy server's ACL is the enforcement point. Per phone, provision:

| Principal | Access |
|---|---|
| `<device>-phone` user | read `mab-<device>-owner`, read each member's `mab-<device>-family-<member>`, write `mab-<device>-ack` — nothing else |
| Owner's agent token | write `mab-<device>-owner`, read `mab-<device>-ack` |
| Each family member's user (one user per person, shared across the phones they help) | write their own `mab-<device>-family-<member>`, read `mab-<device>-ack` |
| Server admin (server operator) | full access (server role, not a bridge credential) |

Every grant names its exact topic, never a wildcard: `…-family-m1*` would
also cover `…-family-m10`. The phone's user must be able to read every member
topic it is set up with. ntfy answers `403` to a whole subscription when any
one topic in it is unreadable, so a member topic the phone can't read would
cut the owner channel off too; provision the grant before adding the member
on the phone (`server/ntfy-provisioning.md`). The bridge works around a
refused member by asking about each member topic alone and reading the ones
it may (re-asking every 15 minutes and at each reconnect), but that is a
fallback, not the plan.

Rules:

- The phone's credential is read-mostly by design: stealing it lets an
  attacker *watch* commands and *forge receipts*, never issue commands.
- A leaked publisher token can only fire registry commands on one channel of
  one phone (for a family member, on that member's topic). Worst case is apps opening and the phone ringing at full alarm
  volume, repeatedly, until the owner flips a STOP switch; the per-channel
  rate limits (at most 3 `ring find` and 10 launches per 10 minutes) bound
  how often. Revocation = rotate that token / disable that user; the other
  channels are unaffected. To cut off one family member, `deny` their user on
  their topic (and on the ack topic), and rotate their token or remove the
  user; the owner channel and every other member are unaffected.
- Credentials live with the publishers (agent secret storage, family
  members' password managers). The phone's owner never handles, sees, or
  types a credential for the owner channel.
- Exactly **one executor** may be live per phone at a time (Tasker pilot OR
  native app). Two live executors means every command fires twice.

## 4. Command registry (v1)

### 4.1 Owner channel — full registry

| Command | Action |
|---|---|
| `launch <app>` | Bring `<app>` to the foreground (§4.3) |
| `ring find` | Play the find-my-phone sound at full volume for 30 s or until dismissed on the phone |
| `bootstrap rustdesk` | Download the pinned RustDesk APK (URL + version pinned in executor config, never in the message) and initiate installation. Human completes any protected prompts. |
| `update rustdesk` | If a newer pinned RustDesk build is configured, download and initiate installation as above |
| `status report` | Executor publishes a receipt (§6) with battery level, executor version, and channel states — no location, no personal data |

### 4.2 Family channel — subset

| Command | Action |
|---|---|
| `launch <app>` | Same as owner channel, except `launch rustdesk`: the owner confirms it on the phone before it opens (§5, confirm flow) |
| `ring find` | Same as owner channel |

Family channel has **no** bootstrap/update/status commands. Installing
software is an owner-channel-only act.

### 4.3 Launchable apps (initial registry)

| `<app>` | Android package | Notes |
|---|---|---|
| `meet` | `com.google.android.apps.tachyon` | Verified on the pilot phone 2026-11-12 (Google Meet; `…apps.meetings` is not installed there) |
| `whatsapp` | `com.whatsapp` | |
| `facebook` | `com.facebook.katana` | |
| `chrome` | `com.android.chrome` | |
| `muse` | `com.facebook.aura` | "Muse from Meta"; verified on the pilot phone 2026-11-12 (launcher `.main.AuraMainActivity`) |
| `rustdesk` | `com.carriez.flutter_hbb` | Present only where RustDesk is deployed |

Package names are verified against the actual phone at provisioning and
recorded in the executor config. The registry may differ per phone; the
*command vocabulary* may not.

## 5. Executor behavior

On receiving a message, an executor MUST:

1. Check the message id: a message with no id is dropped; one whose id is
   outside §6's grammar is ignored and logged. Neither gets a receipt.
   Check freshness and dedup (§2.3). Every message on a command topic is
   recorded as seen here, stale or not and before any later check, so a
   copy redelivered after a STOP is lifted, or after the phone's clock is
   corrected, cannot run, and a second copy gets no second receipt. A
   stale message is logged and gets a `dropped-stale` receipt (§6; at
   most 5 for a backlog replayed on reconnect); a duplicate is logged
   only, since the first receipt stands.
2. Match the body against the registry for that channel. No match → publish
   `err unknown-command` receipt, take no other action. Never attempt to
   interpret, complete, or "helpfully" parse a non-registry body.
3. Check the channel's local enable state (§7). Disabled → `err <command> disabled`.
   Then the channel's rate limit (§3): over it → `err <command> rate-limited`
   (the first refusal of a run; repeats are logged only, §6), with no
   announcement or action, and the refused command doesn't count.
4. Announce on the phone *before* acting: a persistent visual banner plus
   spoken announcement where the executor supports it, naming the action and
   who asked — e.g. "Opening Meet: asked by Alex." A family command is
   announced with the name the owner gave that member on the phone (§2.1),
   so the phone's owner must always be able to tell, afterwards, who asked
   for what.
   The owner channel is announced as "your Muse" ("Opening Meet: asked by
   your Muse"), since that's who the owner's channel speaks for.
   `status report` needn't be announced: it only reads and replies (it is
   still logged and shown on the owner screen). The app doesn't announce it.
5. Perform the action.
6. Append to the on-phone log: timestamp, channel, command, result.
7. Publish a receipt (§6).

Executors MUST NOT chain commands, retry a failed command on their own, or
act on messages from any topic other than the owner topic and the member
topics the phone is set up with (§2.1). A message on the former shared family
topic, or on a removed member's topic, is not a command.

**Confirm flow (family `launch rustdesk`).** A `launch rustdesk` on a member
topic is not acted on at step 5 until the owner says yes on the phone.
Steps 1 to 3 run as usual, and the command counts toward the family rate
limit. Then:

a. The launch refusals run first, with their usual receipts (`not-installed`,
   `cannot-launch`, `cannot-announce`; the last also covers the notification
   channel the prompt needs). Nothing is announced or prompted.
b. The phone announces "RustDesk: Alex is asking to open it" (not "Opening":
   nothing opens yet) and posts a prompt with two actions, Open RustDesk and
   Not now. Open needs the phone unlocked.
c. Open runs the usual launch path (the three refusals again, since any can
   have changed during the wait; then the announcement "Opening RustDesk:
   asked by Alex", the launch and the launch check) and answers as it does
   today (§6). Not now answers `declined`. No answer within **2 minutes** of
   the phone handling the command answers `not-confirmed`; so does a tap on
   Open or Not now that comes after that deadline. A swiped-away prompt is
   not an answer: the request waits for its deadline.
d. Only one request waits at a time. A second family `launch rustdesk` while
   one is pending gets `pending` and no second prompt. STOP (family or all)
   while one is pending withdraws it and answers `disabled`; so does an Open
   tapped while STOP is on, and a STOP switched on while a request is being
   checked: it answers `disabled`, and any prompt already posted is withdrawn.
   Past the deadline, STOP answers `not-confirmed` like a late tap (c).
e. The pending request is kept across a restart: the next start withdraws it
   and answers `not-confirmed`. A setup save that changes the server or the
   device name withdraws it at once instead, before the new setup is written,
   and answers `not-confirmed` on the old server and ack topic. That receipt
   is sent once: if the send fails (no network, or the old server refuses
   it), it is lost and the phone logs `receipt failed (old server <host>,
   mab-<old device>-ack): …`; once sent, the phone logs `receipt sent (setup
   changed)`. If the app is killed before it goes, it is lost with no trace
   beyond the `not-confirmed … (setup changed)` line. Any other
   setup save restarts a running bridge, which answers a waiting request with
   `not-confirmed` then, on the unchanged server; a stopped bridge answers it
   at its next start.

There is at most one receipt per message, sent when the outcome is known (§6);
a send that fails, or a kill before it goes, leaves none (e).

## 6. Receipts (`mab-<device>-ack`)

Body format: `<result> <command>`, an optional short detail token, then
` id=<id>`. Every receipt ends with ` id=<id>`: the ntfy message id of the
command message it answers, as ntfy delivered it (1 to 32 characters
`[A-Za-z0-9]`; ntfy's are 12). A message whose id doesn't fit is ignored and
logged, with no receipt. The complete list:

```
ok <command> id=<id>                       e.g. ok launch meet id=Ab12Cd34Ef56
err <command> not-installed id=<id>        launch only; nothing is announced (except on a confirmed family RustDesk launch, after the request was announced)
err <command> disabled id=<id>             channel off by STOP (§7); also a family RustDesk request withdrawn by STOP while waiting for the owner
err <command> not-implemented id=<id>      bootstrap rustdesk / update rustdesk until Phase 3
err <command> cannot-announce id=<id>      the phone can't show the announcement (§5.4), so nothing was done
err <command> failed id=<id>               the action couldn't run (e.g. no sound to ring); not retried
err <command> cannot-launch id=<id>        launch only; "Display over other apps" is off, so Android would block it; nothing was done (except on a confirmed family RustDesk launch, after the request was announced)
err <command> blocked id=<id>              launch only; announced and sent, but the app wasn't seen in front within 5 s; it may still appear (don't resend at once)
err <command> rate-limited id=<id>         over the channel's limit (§3): 3 ring find or 10 launches per 10 minutes; nothing was done
err <command> declined id=<id>             family launch rustdesk only; the owner tapped Not now (§5, confirm flow); nothing was opened
err <command> not-confirmed id=<id>        family launch rustdesk only; no tap within 2 minutes, a tap or STOP after that, the bridge restarted while waiting, or a setup save moved the server or device name (answered on the old ones); nothing was opened
err <command> pending id=<id>              family launch rustdesk only; another request is already waiting for the owner; no second prompt
err unknown-command id=<id>                never echoes the body
dropped-stale <command> id=<id>            stale and a registry command on its channel
dropped-stale id=<id>                      stale and not a registry command
ok status report battery=<n> version=<app version> protocol=<version> owner=<on|off> family=<on|off> launch-check=<on|off> time=<unix seconds> id=<id>
```

`time` is the phone's own clock when it built the receipt, in whole Unix
seconds, so a publisher can compare it with the receipt's ntfy time and
warn about a phone clock that's off (§2.3's freshness check uses that
clock).

A family `launch rustdesk` that reaches the confirm step (§5) answers when the
outcome is known, not when it arrives. That is at most about 2 minutes after
the phone handles it, and the phone accepts a command up to 2 minutes old
(§2.3), so a publisher can wait up to about 4 minutes. After deep sleep it can
be later: the deadline is noticed at the next line read from the server, up to
about 45 s late on a live connection, and on a dead connection not until the
phone next wakes. If the bridge was stopped while a request waited, the answer
comes at its next start, or at once from a setup save that changes the server
or device name (§5e; sent once, and lost with no network or if the app is
killed at that moment). Publishers must not resend while waiting.

A duplicate gets no receipt, including a replayed copy of a message already
handled that is now older than 120 s (its first receipt stands).
`dropped-stale` receipts are also published when connectivity allows. A
backlog replayed on reconnect (cached messages older than the server
time of the subscription's `open` event) gets at most 5; the rest are
logged only, with a count, so the receipt for a fresh command isn't
queued behind them. A stale message that arrives live (a phone clock
that's off, for instance) always gets its receipt.

The rate limits (§3) count every `ring find` and launch that passes the
STOP check, whatever it then does: one that joins a ring already going
still announces, so it counts, and so does a launch of an app that isn't
installed. A command refused by the limit doesn't count. The first
refusal of a run (per channel and kind) gets a `rate-limited` receipt;
the run's repeats within the window are logged only, and a command that
runs again starts a new run. The limits are counted since the executor
last started. Receipts for messages the limit never sees
(`unknown-command`, `disabled`) are not capped yet.

Receipts confirm what the *executor did*, not what the user did next
(`ok launch meet` means Meet came to the foreground, not that anyone
joined a call). The executor checks that through Android's usage events,
which needs the owner to grant usage access; an app already in front
counts, and so does one opened behind the lock screen. Without usage
access, `ok launch` means the launch was sent and not refused, the
executor's log says it was unverified, and `status report` says
`launch-check=off`. Even with usage access, an `ok launch` is unverified
when the check can't tell (no app resumed in the last 24 h, or the check
failed); the log says so and why.

## 7. On-phone controls (owner sovereignty)

The executor MUST provide, reachable by the phone's owner without help:

- A visible indicator that the bridge is installed and which channels are on.
- A **STOP control** that disables the family channel (every member's topic) immediately, and a
  separate control that disables all channels. State survives reboot.
- The on-phone log (§5.6), readable on the device.

Disabling a channel is local and instant; it does not require contacting any
publisher or the server admin. Re-enabling is likewise the owner's act alone.

## 8. Threat model (summary)

In scope: leaked publisher token (bounded to whitelist mischief, revocable);
lost phone credential (read-only exposure); a malicious or malformed message
on a command topic (registry match rejects it); stale/redelivered commands
(TTL + dedup).

Out of scope: an attacker holding the unlocked phone (the bridge is not the
weak point there); compromise of the ntfy server itself (the server
operator's existing server hardening applies); social engineering of the
phone's owner (addressed by deployment-stage hardening, not by this
protocol).

The bridge deliberately holds no capability worth stealing beyond opening
apps and ringing the phone. Screen access (RustDesk) is a separate system
with its own credentials and is never reachable through this protocol —
no bridge command unlocks, configures, or starts a remote-control session
beyond launching the RustDesk app to its normal home screen.

## 9. Versioning

This document is versioned. Changes to the command registry or message
format require a version bump here first; executors and publishers then
update to match. Executors record the protocol version they implement and
report it in `status report` receipts.

0.5 is additive for publishers on the owner channel. The shared family topic
`mab-<device>-family` is retired: the 0.5 build no longer reads it, so the
family channel is silent on a phone until a member is added (§2.1). Its ACL
grants are revoked only after the phone runs the 0.5 build (or the `403` rule
in §3 would cut it off), and each family user has their member topic granted
and added on the phone. A message sent there afterwards reaches nobody and
gets no receipt. A family
publisher must know which topic its credential writes to. A family `launch
rustdesk` can now answer up to about 4 minutes after publishing (2 minutes of
waiting from when the phone handles it, which can itself be up to 2 minutes
after publishing; a little longer after deep sleep), or at the bridge's next
start if it was stopped meanwhile. The three new receipt details (`declined`,
`not-confirmed`, `pending`) are unknown to a 0.4 parser, which would report
them as unrecognized.

0.4 is not compatible with 0.3 in either direction: a 0.3 parser rejects a
receipt ending in `id=`, and a 0.4 parser rejects one without it. A
publisher therefore records, per phone, which version that phone runs, and
checks it against `status report`'s `protocol=`.

## 10. Open questions for the pilot (Phase 1)

1. Tasker receive path: react to the ntfy Android app's notifications, or
   poll/subscribe directly? Measure latency and battery on the pilot phone.
2. Announce mechanism in the Tasker executor: Tasker `Say` + flash vs a
   persistent notification. (Native app will do banner + TTS.)
3. Does `status report` belong in the pilot, or is it app-phase scope?
4. ~~Exact Muse app package name and Meet package name on the pilot phone.~~
   Resolved 2026-11-12 (§4.3).
5. Ack-topic behavior while the phone is on a VPN (pilot phone runs a
   consumer VPN almost daily) — verify publish and subscribe both survive it.
