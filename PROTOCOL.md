# muse-android-bridge — Wire Protocol

**Version:** 0.1 (DRAFT, 2026-11-11)
**Status:** Phase 0 deliverable. This document is the contract. The Tasker pilot
and the native app are interchangeable executors of this protocol; neither may
extend it unilaterally.

## 1. Purpose and non-goals

Give a Muse agent (and, on a separate channel, designated family members) the
ability to ask an Android phone to perform a small, fixed set of actions —
initially, launching apps — that Muse's paired-phone commands do not cover.

Non-goals:

- No remote screen viewing or control (that is RustDesk's job, out of scope).
- No general-purpose command execution. A trigger can never become code.
- No unlocking the phone. A launched app is usable only if the phone is
  unlocked or is unlocked by its owner.
- No silent operation. Every executed command is announced on the phone and
  logged on the phone.

## 2. Transport

Commands travel as messages on a self-hosted ntfy server (the operator's
existing instance; `auth-default-access: deny-all`).

### 2.1 Topics (per phone)

| Topic | Direction | Purpose |
|---|---|---|
| `mab-<device>-owner` | agent → phone | Owner channel: full command registry (§4.1) |
| `mab-<device>-family` | family → phone | Family channel: launch-only subset (§4.2) |
| `mab-<device>-ack` | phone → publishers | Execution receipts (§6) |

`<device>` is a short lowercase slug chosen at provisioning (`chris-razr`,
`michele-pixel`, `peggy-phone`, …). Topic names use hyphens only.

### 2.2 Message format

Publish with ntfy's JSON API. The **message body is exactly one command** from
the registry — lowercase, one verb, one argument, single space:

```
{"topic": "mab-chris-razr-family", "message": "launch meet"}
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

## 3. Authentication and authorization

The ntfy server's ACL is the enforcement point. Per phone, provision:

| Principal | Access |
|---|---|
| `<device>-phone` user | read `mab-<device>-owner`, read `mab-<device>-family`, write `mab-<device>-ack` — nothing else |
| Owner's agent token | write `mab-<device>-owner`, read `mab-<device>-ack` |
| Each family publisher | write `mab-<device>-family`, read `mab-<device>-ack` |
| Server admin (operator) | full access (server role, not a bridge credential) |

Rules:

- The phone's credential is read-mostly by design: stealing it lets an
  attacker *watch* commands and *forge receipts*, never issue commands.
- A leaked publisher token can only fire registry commands on one channel of
  one phone. Worst case is an app opening. Revocation = rotate that token /
  disable that user; the other channel is unaffected.
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
| `launch <app>` | Same as owner channel |
| `ring find` | Same as owner channel |

Family channel has **no** bootstrap/update/status commands. Installing
software is an owner-channel-only act.

### 4.3 Launchable apps (initial registry)

| `<app>` | Android package | Notes |
|---|---|---|
| `meet` | `com.google.android.apps.meetings` | Verify on device at provisioning |
| `whatsapp` | `com.whatsapp` | |
| `facebook` | `com.facebook.katana` | |
| `chrome` | `com.android.chrome` | |
| `muse` | TBD at provisioning | Resolve from the installed Muse app |
| `rustdesk` | `com.carriez.flutter_hbb` | Present only where RustDesk is deployed |

Package names are verified against the actual phone at provisioning and
recorded in the executor config. The registry may differ per phone; the
*command vocabulary* may not.

## 5. Executor behaviour

On receiving a message, an executor MUST:

1. Check freshness and dedup (§2.3). Drop silently (log only).
2. Match the body against the registry for that channel. No match → publish
   `err unknown-command` receipt, take no other action. Never attempt to
   interpret, complete, or "helpfully" parse a non-registry body.
3. Check the channel's local enable state (§7). Disabled → `err disabled`.
4. Announce on the phone *before* acting: a persistent visual banner plus
   spoken announcement where the executor supports it, naming the action and
   the channel — e.g. "Opening Meet — asked by family." The phone's owner
   must always be able to tell, afterwards, who asked for what.
5. Perform the action.
6. Append to the on-phone log: timestamp, channel, command, result.
7. Publish a receipt (§6).

Executors MUST NOT chain commands, retry a failed command on their own, or
act on messages from any topic other than the two command topics.

## 6. Receipts (`mab-<device>-ack`)

Body format: `<result> <command>` with an optional short detail token:

```
ok launch meet
err launch meet not-installed
err unknown-command
dropped-stale launch meet        (also published when connectivity allows)
```

Receipts confirm what the *executor did*, not what the user did next
(`ok launch meet` means Meet came to the foreground, not that anyone
joined a call).

## 7. On-phone controls (owner sovereignty)

The executor MUST provide, reachable by the phone's owner without help:

- A visible indicator that the bridge is installed and which channels are on.
- A **STOP control** that disables the family channel immediately, and a
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
weak point there); compromise of the ntfy server itself (operator's existing
server hardening applies); social engineering of the phone's owner (addressed
by deployment-stage hardening, not by this protocol).

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

## 10. Open questions for the pilot (Phase 1)

1. Tasker receive path: react to the ntfy Android app's notifications, or
   poll/subscribe directly? Measure latency and battery on the pilot phone.
2. Announce mechanism in the Tasker executor: Tasker `Say` + flash vs a
   persistent notification. (Native app will do banner + TTS.)
3. Does `status report` belong in the pilot, or is it app-phase scope?
4. Exact Muse app package name and Meet package name on the pilot phone.
5. Ack-topic behaviour while the phone is on a VPN (pilot phone runs a
   consumer VPN almost daily) — verify publish and subscribe both survive it.
