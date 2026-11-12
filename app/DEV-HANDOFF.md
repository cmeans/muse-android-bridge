# Native app — development handoff path

_Status: prepared 2026-11-11. Chris will hand implementation to
claude-dev once the path below is understood and settled. This document
is the map, not an instruction to start._

## Objective

Replace the Tasker pilot with one small Android app that implements
`PROTOCOL.md` exactly:

- subscribe to the device's owner and family ntfy topics;
- accept only exact registry commands;
- apply STOP gates, 120-second TTL, and 24-hour message-ID dedup in
  that order;
- announce every accepted action on the phone;
- launch the target app, or perform `ring find`;
- post a receipt to the ack topic and keep a local log;
- restart after reboot and expose a simple owner screen with setup,
  service state, recent log, and separate family/all STOP switches.

Non-goals for v1: no accessibility automation, no general intent or
shell runner, no screen control, no credential/PIN entry, no purchases,
and no RustDesk session control beyond launching the RustDesk app.
RustDesk bootstrap/update commands remain `err not-implemented` until
the separate Phase 3 design is approved.

## What is already proven

- The ntfy server, topics, users, and ACL separation are live for the
  `chris-razr` pilot and were verified end to end.
- Security drills F1 (publisher revocation) and F2 (phone credential
  scope) passed.
- The Tasker probe received real commands on both channels and resolved
  the topic/message data. It also demonstrated the server-cache replay
  on subscribe, which is why TTL and dedup are mandatory.
- `PROTOCOL.md` is executor-agnostic; the app does not need a new wire
  design.

## Current scaffold — treat as a spike, not a finished app

`app/android/` contains a Kotlin/Gradle scaffold written to make the
shape concrete: manifest, configuration/registry, a foreground
`BridgeService`, setup/STOP/log activity, and a boot receiver.

It builds (2026-11-12, claude-dev, on Chris's workstation: Gradle
wrapper 8.14.7 with a pinned checksum, Android Gradle plugin 8.13.2,
Kotlin 2.2.10, compileSdk/targetSdk 36, built with a JDK 21). It has
**not** been run on a device. Muse's VM never completed an SDK install
(`sdkmanager` downloads failed in its HTTP proxy tunnel), so builds run
on the workstation. Claude-dev may retain, refactor, or replace the
scaffold; the protocol and tests are the contract, not this code.

Known scaffold gaps before any family deployment:

- move the phone password out of plain SharedPreferences into
  Keystore-backed encrypted storage;
- verify every registry package name on real hardware, especially
  Muse;
- add unit tests for gate order, TTL, dedup, and malformed commands;
- prove reconnect/backoff, Doze behaviour, locked-phone behaviour,
  NordVPN behaviour, and 48-hour battery cost;
- a reproducible signing setup (the Gradle wrapper is generated and
  pinned).

## Recommended implementation path

1. **Build baseline.** Finish the SDK/Gradle setup in the development
   environment, produce a clean debug build, and add tests around the
   protocol state machine before polishing UI.
2. **Transport.** Implement the ntfy subscription as a foreground
   service with reconnect/backoff. Decide from device evidence whether
   a direct streaming connection is reliable enough on the Razr+ under
   Doze and NordVPN; do not silently fall back to interpreting ntfy-app
   notifications without recording that design change.
3. **Executor.** Implement gates in protocol order, exact-match parsing,
   receipts, and append-only local logging. A malformed or unknown
   message must cause no action beyond an error receipt/log entry.
4. **Owner surface.** Helper-oriented setup screen; large, unambiguous
   STOP controls; STOP state persisted across reboot and service
   restart. Peggy's delivered configuration is frozen: no workflow may
   require her to import, update settings, or maintain the app.
5. **Signing and distribution.** Use a debug/sideload build only for
   Chris's pilot phone. Before Michele/Peggy rollout, settle release
   signing, update delivery, and whether Play internal testing is worth
   its account/process overhead. An update must never change the app's
   signing identity unexpectedly.
6. **Device gates.** Run the app equivalents of test groups A and B on
   the Razr+: delivery latency/reliability, VPN, stale replay, dedup,
   locked phone, STOP persistence, malformed-input safety, and battery.
   Only after those pass does the app move to Michele, then Peggy.

## Decisions still open

- Peggy's phone make/model and Android version; this affects minimum
  SDK, background limits, RustDesk behaviour, and setup instructions.
- Final distribution route for family phones: sideloaded release APK
  versus Play internal testing.
- Whether direct ntfy streaming is sufficient or the production app
  needs an additional wake mechanism; decide from A-group measurements,
  not by assumption.
- How Peggy's own Muse will publish owner-channel commands and where
  that credential will live on her side (test E1).
- RustDesk server/controller work remains Phase 3 and needs its own
  explicit go; it is not bundled into this app handoff.

## Acceptance boundary

The handoff is for implementation and test evidence. It does not by
itself authorize the RustDesk server, router changes, installation on
Michele's or Peggy's phones, or a production rollout. Those stay with
Chris's explicit decisions.
