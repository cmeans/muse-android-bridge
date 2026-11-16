# Native app — development handoff path

_Status: prepared 2026-11-11 as the map; on 2026-11-12 the maintainer handed
implementation to claude-dev, and PR #4 implements steps 1, 3 and 4
below. Step 2 is implemented and awaits its long-run device evidence._

## Objective

Replace the Tasker pilot with one small Android app that implements
`PROTOCOL.md` exactly:

- subscribe to the device's owner topic and one topic per family member
  (protocol 0.5; the shared family topic is retired);
- accept only exact registry commands;
- apply the 120-second TTL, 24-hour message-ID dedup, exact registry
  match and STOP gates, in that order (PROTOCOL v0.5 §5; the Tasker pilot
  checked STOP first);
- announce every accepted action that changes something on the phone
  (`status report` isn't announced, PROTOCOL §5 step 4);
- launch the target app, or perform `ring find`;
- post a receipt to the ack topic and keep a local log;
- restart after reboot and expose a simple owner screen with setup,
  service state, recent log, and separate family/all STOP switches. A family
  `launch rustdesk` waits for the owner's tap on the phone (protocol 0.5).

Non-goals for v1: no accessibility automation, no general intent or
shell runner, no screen control, no credential/PIN entry, no purchases,
and no RustDesk session control beyond launching the RustDesk app.
RustDesk bootstrap/update commands remain `err <command> not-implemented` until
the separate Phase 3 design is approved.

## What is already proven

- The ntfy server, topics, users, and ACL separation are live for the
  pilot phone and were verified end to end (`pilot-phone` is the docs'
  example name; the live server uses its own, see
  `server/ntfy-provisioning.md`).
- Security drills F1 (publisher revocation) and F2 (phone credential
  scope) passed.
- The Tasker probe received real commands on both channels and resolved
  the topic/message data. It also demonstrated the server-cache replay
  on subscribe, which is why TTL and dedup are mandatory.
- `PROTOCOL.md` is executor-agnostic; the app does not need a new wire
  design.

## Current app — past the spike, not yet ready for family phones

`app/android/` contains a Kotlin/Gradle scaffold written to make the
shape concrete: manifest, configuration/registry, a foreground
`BridgeService`, an owner screen (STOP, permissions, recent activity), a setup
screen, and a boot receiver.

It builds (2026-11-12, claude-dev, on the maintainer's workstation: Gradle
wrapper 8.14.7 with a pinned checksum, Android Gradle plugin 8.13.2,
Kotlin 2.2.21, compileSdk/targetSdk 36, built with a JDK 21). The
toolchain stays on the AGP 8 / Gradle 8 line on purpose: AGP 9 needs
Gradle 9 and moves Kotlin into the plugin itself (built-in Kotlin),
which is a migration of its own rather than a version bump, so it waits
for a PR of its own. AGP 8.13.2 is the last 8.x release. Kotlin stays
on the 2.2 line, at its last patch (2.2.21), to keep this baseline's
bump small while the executor work is stacked on it; 2.3 and 2.4 also
build on this toolchain and can follow separately. The executor (PR
#4) has run on the pilot phone since 2026-11-12; the device results are
on PR #4. Muse's VM never completed an SDK install
(`sdkmanager` downloads failed in its HTTP proxy tunnel), so builds run
on the workstation. Claude-dev may retain, refactor, or replace the
scaffold; the protocol and tests are the contract, not this code.

**Building.** From `app/android`, run
`./gradlew assembleDebug testDebugUnitTest`. It needs a JDK 17 or newer
(Android Studio's bundled JBR 21 works: `JAVA_HOME=/opt/android-studio/jbr`
on Linux) and the Android SDK, located by `ANDROID_HOME` or by
`sdk.dir=<path>` in `app/android/local.properties` (ignored by git),
with the `android-36` platform and build-tools 35.0.0 installed.
Without an SDK location Gradle stops with "SDK location not found".
The debug APK lands in `app/build/outputs/apk/debug/`.

**Application id.** The app is `com.intfar.mabridge` (it was
`org.intfar.mabridge` until the 2026-11 rename). Android treats the two ids as
different apps, so a new build installs beside an old one instead of
replacing it, and once both are set up, both bridges run every command. On a phone
that still has `org.intfar.mabridge`, uninstall it before installing,
then set the bridge up again (server, password, permissions, battery
exemption). The phones move over with the release-key build.

Known gaps before any family deployment. On 2026-11-12, PR #4 carried out
Tasks 1-7 of the executor plan (now `docs/plans/2026-11-12-app-executor-v1.md`
in the private muse-android-bridge-infra repo): Keystore credential storage,
the verified registry, and unit tests for check order, TTL, dedup and
malformed input. What follows is still open:

- prove reconnect/backoff over long runs (6 h and more), Doze, NordVPN,
  and 48-hour battery cost (locking the phone passed on 2026-11-12, for
  launches and rings, and again with the protocol 0.3 launch check;
  whether a locked phone shows a launched app like Meet usefully is
  device test B2);
- a reproducible signing setup (the Gradle wrapper is generated and
  pinned).

## Recommended implementation path

1. **Build baseline.** Done (2026-11-12): the SDK/Gradle setup, a clean
   debug build, and unit tests around the protocol state machine.
2. **Transport** (implemented in PR #4; long-run evidence pending).
   Implement the ntfy subscription as a foreground service with
   reconnect/backoff. Decide from device evidence whether a direct
   streaming connection is reliable enough on the pilot phone under Doze and
   NordVPN; do not silently fall back to interpreting ntfy-app
   notifications without recording that design change.
3. **Executor** (implemented in PR #4). Implement gates in protocol
   order, exact-match parsing, receipts, and append-only local logging.
   A malformed or unknown message must cause no action beyond an error
   receipt/log entry.
4. **Owner surface** (implemented in PR #4). Helper-oriented setup
   screen; large, unambiguous STOP controls; STOP state persisted
   across reboot and service restart. The phone owner's delivered
   configuration is frozen: no workflow may require them to import,
   update settings, or maintain the app.
5. **Signing and distribution.** Use a debug/sideload build only for
   the pilot phone. Before rollout to the helper's and owner's phones,
   settle release signing, update delivery, and whether Play internal
   testing is worth its account/process overhead. An update must never
   change the app's signing identity unexpectedly.
6. **Device gates.** Run the app equivalents of test groups A and B on
   the pilot phone: delivery latency/reliability, VPN, stale replay, dedup,
   locked phone, STOP persistence, malformed-input safety, and battery.
   targetSdk 36 opts the app into Android 16's behavior changes, on
   top of Android 15's, which already applied at targetSdk 35 (such as
   the foreground-service rules behind issue #1); these gates cover
   both. Only after they pass does the app move to a
   helper's phone, then the owner's.

## Decisions still open

- The owner's phone make/model and Android version; this affects minimum
  SDK, background limits, RustDesk behavior, and setup instructions.
- Final distribution route for family phones: sideloaded release APK
  versus Play internal testing.
- Whether direct ntfy streaming is sufficient or the production app
  needs an additional wake mechanism; decide from A-group measurements,
  not by assumption.
- How the owner's own Muse will publish owner-channel commands and where
  that credential will live on their side (test E1).
- RustDesk server/controller work remains Phase 3 and needs its own
  explicit go; it is not bundled into this app handoff.

## Acceptance boundary

The handoff is for implementation and test evidence. It does not by
itself authorize the RustDesk server, router changes, installation on
the helper's or owner's phones, or a production rollout. Those stay with
the maintainer's explicit decisions.
