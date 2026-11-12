# Native App Executor v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `app/android/` spike into a protocol-exact executor of `PROTOCOL.md` that can be installed on the pilot phone (`chris-razr`) and run test groups A and B.

**Architecture:** All protocol decisions live in plain Kotlin under `org.intfar.mabridge.protocol` (no Android imports), so JVM unit tests cover check order, expiry, duplicates, parsing and receipts. The Android layer (`BridgeService`, `MainActivity`, a Keystore credential store) only does I/O: one ntfy streaming connection, announcing, launching, ringing, logging, posting receipts.

**Tech Stack:** Kotlin 2.2.10, Android Gradle plugin 8.13.2, Gradle 8.14.3 wrapper, compileSdk/targetSdk 36, minSdk 29, JUnit 4.13.2. No runtime dependencies beyond the Android platform.

**Spec:** `PROTOCOL.md` (v0.1, the contract), `TEST-PLAN.md` groups A and B, `app/DEV-HANDOFF.md`.

## Decisions (Chris approved all three recommendations, 2026-11-12 11:28 CT)

The documents disagreed, and Task 3's tests encode the answer.

- **D1: Check order.** PROTOCOL §5: (1) expiry and duplicate check, dropped with a log line only; (2) exact registry match, else `err unknown-command`; (3) channel enabled, else `err disabled`. The Tasker build sheet (`tasker/EXECUTOR.md` §1–3), the handoff and the spike put STOP first and drop silently, with no receipt. **Recommended: PROTOCOL's order.** It marks a message as seen before STOP is checked, so a cached copy can't fire after STOP is turned off again. It also tells the sender why nothing happened.
- **D2: Receipt wording.** PROTOCOL §6 receipts are `<result> <command> [detail]` with no message ID (`dropped-stale launch meet`). The spike and Tasker send `err stale <id>` and add the ID to error receipts. **Recommended: PROTOCOL's wording, pinned exactly:** `ok <command>`, `err <command> not-installed`, `err <command> disabled`, `err <command> not-implemented`, `err unknown-command` (never echoes the body), `dropped-stale <command>` (or a bare `dropped-stale` when the stale body isn't a registry command), `ok status report battery=<n> version=<app version> protocol=<version> owner=<on|off> family=<on|off>`. Duplicates get no receipt, since the first receipt stands. PROTOCOL §6 gets these lines in Task 2.
- **D3: Announce timing.** The spike announces "Opening Meet" before checking that Meet is installed. **Recommended:** resolve the launch intent first. If it's missing, send `err launch <app> not-installed` with no announcement. Otherwise announce, then launch.

## Global Constraints

- Expiry: discard any message whose ntfy `time` is more than **120 s** from the phone's clock at receipt, in either direction (PROTOCOL §2.3, "clock-skew allowance included").
- Duplicates: ntfy message ID, rolling **24 h** window, surviving service restarts and reboots (§2.3).
- Commands are lowercase `<verb> <arg>` with a single space, matched exactly. No trimming, case folding or partial matching (§2.2, §5.2).
- The family channel accepts only `launch <app>` and `ring find` (§4.2).
- `bootstrap rustdesk` and `update rustdesk` answer `err <command> not-implemented` until Phase 3 is approved (DEV-HANDOFF non-goals).
- Act only on messages whose `topic` field is exactly `mab-<device>-owner` or `mab-<device>-family` (§5).
- Never chain or retry commands (§5).
- No location or personal data in any receipt (§4.1 `status report`).
- Exactly one executor live per phone: the Tasker MAB profile must be disabled before the app subscribes (§3).
- The phone password is never stored in plain text and is never shown again after entry (DEV-HANDOFF gaps).
- No new runtime dependencies. Test-only dependencies are fine.
- American English in code, docs and receipts.

## Review Focus

1. **A clock-skewed or future-dated message** (`time` 10 minutes ahead) must be dropped as stale, not run. The spike's `now - time > TTL` passes negative ages. Pinned in Task 3.
2. **A redelivered message after STOP is turned off** must not run. Only PROTOCOL's order (D1) gives this. Pinned in Task 3.
3. **A message with no `id`, or a non-numeric `time`**, must cause no action and no crash (log only). Pinned in Task 4 (parser) and Task 3 (gate).
4. **A hostile body in the log**: multi-kilobyte bodies, newlines or control characters must not forge log lines. The log stores a sanitized excerpt of at most 64 characters. Pinned in Task 3 (`LogLine`).
5. **A dropped ntfy connection while the phone sleeps** must reconnect with capped backoff, without a busy loop or a thread per attempt. Backoff is pinned in Task 6. Real behavior is measured in Task 8 (A1, A5).

## File Structure

```
app/android/app/src/main/java/org/intfar/mabridge/
  protocol/Channel.kt        Channel enum + topic mapping
  protocol/Command.kt        Command sealed type + parseCommand()
  protocol/Registry.kt       app slug -> (display name, package)
  protocol/SeenStore.kt      SeenStore interface + InMemorySeenStore
  protocol/Gate.kt           IncomingMessage, Decision, Gate.decide()
  protocol/Receipts.kt       receipt strings (D2)
  protocol/LogLine.kt        sanitized log formatting
  protocol/NtfyEvent.kt      parse one ntfy JSON stream line
  protocol/Backoff.kt        reconnect delay policy
  FileSeenStore.kt           SeenStore on filesDir, atomic rewrite
  CredentialStore.kt         Keystore AES-GCM wrapper for the password
  BridgeService.kt           I/O only, driven by Gate decisions
  Config.kt                  prefs: server, slug, username, STOP flags
  MainActivity.kt            setup, STOP switches, ring dismiss, log view
app/android/app/src/test/java/org/intfar/mabridge/protocol/
  CommandTest.kt  GateTest.kt  NtfyEventTest.kt  BackoffTest.kt  LogLineTest.kt
```

Build and test command (used in every task): from `app/android`,
`JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk ./gradlew --no-daemon testDebugUnitTest`,
run under `systemd-run --user --scope -p MemoryMax=8G -p MemorySwapMax=0 -p RuntimeMaxSec=900`. Expected: `BUILD SUCCESSFUL`.

---

### Task 1: Command parsing

**Files:** Create `protocol/Channel.kt`, `protocol/Command.kt`, `protocol/Registry.kt`; test `CommandTest.kt`.

**Interfaces:**
- Produces: `enum class Channel { OWNER, FAMILY }`; `fun Channel.topic(slug: String): String` (`mab-$slug-owner` / `mab-$slug-family`); `fun channelForTopic(topic: String, slug: String): Channel?`
- Produces: `sealed interface Command { val text: String }` with `data class Launch(val app: String)`, `object RingFind`, `object StatusReport`, `object BootstrapRustdesk`, `object UpdateRustdesk`; `text` is the canonical command string (`"launch meet"`).
- Produces: `fun parseCommand(body: String, channel: Channel, registry: Registry): Command?`. Returns null for anything not exactly a command allowed on that channel.
- Produces: `class Registry(val apps: Map<String, App>)`, `data class App(val displayName: String, val pkg: String)`.

- [ ] **Step 1: Write failing tests** in `CommandTest.kt`:
  - `parseCommand("launch meet", OWNER, reg) == Launch("meet")`, and the same on FAMILY.
  - `parseCommand("ring find", FAMILY, reg) == RingFind`
  - `parseCommand("status report", OWNER, reg) == StatusReport`; `parseCommand("status report", FAMILY, reg) == null`
  - `bootstrap rustdesk` / `update rustdesk`: an object on OWNER, null on FAMILY.
  - Null for each of: `"Launch meet"`, `" launch meet"`, `"launch meet "`, `"launch  meet"`, `"launch\tmeet"`, `"launch meet\n"`, `"launch"`, `"launch zoom"` (not in the registry), `"launch meet now"`, `""`, `"launch méet"`, `"launch " + "a".repeat(10_000)`, `"ring"`, `"ring find please"`.
- [ ] **Step 2: Run, expect a compile failure** (types not defined).
- [ ] **Step 3: Implement.** Exact string comparison against the set of commands generated from the registry and channel. No regex, no trim.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `protocol: exact command parsing per channel (PROTOCOL §2.2, §4)`.

### Task 2: Verified registry and protocol doc fixes

**Files:** Modify `Config.kt` (registry moves to `Registry`), `PROTOCOL.md` §4.3, §5, §6, §9, §10 item 4; `tasker/EXECUTOR.md` (one note line); test `CommandTest.kt`.

**Interfaces:** Produces `val PILOT_REGISTRY: Registry` in `protocol/Registry.kt`.

Packages verified on chris-razr over adb on 2026-11-12:

| slug | display | package |
|---|---|---|
| `meet` | Meet | `com.google.android.apps.tachyon` |
| `whatsapp` | WhatsApp | `com.whatsapp` |
| `facebook` | Facebook | `com.facebook.katana` |
| `chrome` | Chrome | `com.android.chrome` |
| `muse` | Muse | `com.facebook.aura` (launcher `.main.AuraMainActivity`; Muse's own answer in Awareness `handoff-muse-android-bridge-muse-package-answers-2026-11-12`) |
| `rustdesk` | RustDesk | `com.carriez.flutter_hbb` |

- [ ] **Step 1: Failing test** `pilotRegistryMatchesVerifiedPackages`: asserts the six rows above exactly, and that `PILOT_REGISTRY.apps.keys` has exactly six entries.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement `PILOT_REGISTRY`.** Remove `REGISTRY`, `FAMILY_COMMANDS` and `LAUNCHABLE` from `Config.kt`.
- [ ] **Step 4: PROTOCOL.md.** Set the `meet` and `muse` packages in §4.3. Write the D1 order into §5 and the D2 receipt list into §6 as decided. Bump the version to 0.2 with a dated line (§9 requires it for a registry change). Mark §10 item 4 resolved.
- [ ] **Step 5: `tasker/EXECUTOR.md`.** Add one line under the title: the Tasker sheet is frozen research, and where it differs from PROTOCOL v0.2 (order, receipts), PROTOCOL wins.
- [ ] **Step 6: Run tests, expect PASS. Commit** `registry: packages verified on chris-razr; PROTOCOL v0.2`.

### Task 3: The check pipeline

**Files:** Create `protocol/SeenStore.kt`, `protocol/Gate.kt`, `protocol/Receipts.kt`, `protocol/LogLine.kt`; tests `GateTest.kt`, `LogLineTest.kt`. Blocked on D1–D3.

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `interface SeenStore { fun markIfNew(id: String, nowMs: Long): Boolean }` (true means first sighting; prunes entries older than 24 h); `class InMemorySeenStore : SeenStore`.
- Produces: `data class IncomingMessage(val id: String, val timeSec: Long, val topic: String, val body: String)`
- Produces: `data class StopState(val all: Boolean, val family: Boolean)`
- Produces: `sealed interface Decision`: `Ignore(val log: String)` (wrong topic), `DropStale(val receipt: String, val log: String)`, `DropDuplicate(val log: String)`, `Reject(val receipt: String, val log: String)`, `Run(val command: Command, val channel: Channel)`
- Produces: `class Gate(slug: String, registry: Registry, seen: SeenStore, clockMs: () -> Long, stop: () -> StopState)` with `fun decide(msg: IncomingMessage): Decision`
- Produces: `object Receipts` with one function per D2 line, e.g. `ok(c: Command)`, `notInstalled(c: Launch)`, `disabled(c: Command)`, `notImplemented(c: Command)`, `unknown()`, `staleOf(body: String, channel: Channel, registry: Registry)`, `status(battery: Int, appVersion: String, protocol: String, stop: StopState)`
- Produces: `fun logExcerpt(body: String): String`: at most 64 characters; each control character (including `\n`, `\r`, `\t`) becomes `?`; a longer body ends in `…`.

Order (with D1 as recommended): topic → expiry → duplicate → parse → STOP.

- [ ] **Step 1: Failing tests** in `GateTest.kt` (clock fixed at `T = 1_800_000_000_000` ms):
  - `staleByAge`: `timeSec = T/1000 - 121` → `DropStale`, receipt `"dropped-stale launch meet"`
  - `freshAtBoundary`: `- 120` → `Run`
  - `futureDatedIsStale`: `+ 121` → `DropStale`
  - `staleJunkHasBareReceipt`: stale body `"rm -rf /"` → receipt exactly `"dropped-stale"`
  - `duplicateDropped`: the same id twice within 24 h → `Run`, then `DropDuplicate`
  - `duplicateWindowExpires`: the same id 24 h + 1 ms later → `Run` both times
  - `stopFamilyRejectsFamily`: `StopState(false, true)`, family `launch meet` → `Reject("err launch meet disabled")`; an owner message → `Run`
  - `stopAllRejectsBoth`
  - `stoppedMessageIsStillMarkedSeen`: a family message under STOP family → `Reject`; turn STOP off and send the same id again → `DropDuplicate` (Review Focus 2)
  - `unknownCommand`: `"launch meet now"` → `Reject("err unknown-command")`, and the receipt never contains the body
  - `wrongTopicIgnored`: topic `mab-chris-razr-ack` or `mab-other-owner` → `Ignore`, no receipt
  - `blankIdRejected`: `id = ""` → `Ignore`, no action (Review Focus 3)
  - `rustdeskNotImplemented`: owner `bootstrap rustdesk` → `Run(BootstrapRustdesk, OWNER)`, and `Receipts.notImplemented(BootstrapRustdesk) == "err bootstrap rustdesk not-implemented"`
  - `statusReceipt`: `Receipts.status(87, "0.1.0", "0.2", StopState(false, true)) == "ok status report battery=87 version=0.1.0 protocol=0.2 owner=on family=off"`
- [ ] **Step 2: `LogLineTest.kt`:** `logExcerpt("a\nb") == "a?b"`; a 10 000-character body gives a 64-character string ending in `…`; `"launch meet"` is unchanged.
- [ ] **Step 3: Run, expect a compile failure.**
- [ ] **Step 4: Implement** `Gate`, `Receipts`, `InMemorySeenStore`, `logExcerpt`.
- [ ] **Step 5: Run, expect PASS. Commit** `protocol: check pipeline in PROTOCOL order with receipts`.

### Task 4: ntfy stream parsing

**Files:** Create `protocol/NtfyEvent.kt`; test `NtfyEventTest.kt`; modify `app/build.gradle` (test dependency).

**Interfaces:** Produces `fun parseNtfyLine(line: String): IncomingMessage?`: non-null only for `"event":"message"` with a non-blank `id`, an integer `time`, and string `topic` and `message` fields.

`org.json` is an Android stub on the JVM, so add `testImplementation 'org.json:json:<current release>'`, with the version pinned exactly (no `+`).

- [ ] **Step 1: Failing tests:** a real-shape message line → fields copied exactly (the body is not trimmed); `open`, `keepalive` and `poll_request` events → null; invalid JSON → null; a missing `id` → null; `"time":"abc"` → null; a missing `message` → null; a 1 MB line → null or a message, without throwing.
- [ ] **Step 2: Run, expect FAIL. Step 3: Implement. Step 4: PASS. Commit** `protocol: parse ntfy JSON stream lines`.

### Task 5: Credential storage

**Files:** Create `CredentialStore.kt`; modify `Config.kt` (drop `password()`), `MainActivity.kt`.

**Interfaces:** Produces `class CredentialStore(ctx: Context)` with `fun save(password: CharArray)`, `fun load(): CharArray?`, `fun clear()`.

- [ ] **Step 1: Implement** an AES-256-GCM key in `AndroidKeyStore` (alias `mab_cred_v1`, not exportable), and store only the IV and ciphertext in prefs. Don't use `androidx.security:security-crypto`, which is deprecated, and adds a dependency.
- [ ] **Step 2: Setup screen:** the password field is write-only. After saving it shows "Password saved", never the value. Saving again replaces it.
- [ ] **Step 3: Verify on a device** (Keystore isn't available in JVM tests). This step runs with Task 8's install and needs Chris's go. Check: save, restart the service, it subscribes; `adb shell run-as org.intfar.mabridge cat shared_prefs/mabridge.xml` shows no plaintext password.
- [ ] **Step 4: Commit** `app: Keystore-backed phone credential`.

### Task 6: Service wiring

**Files:** Create `protocol/Backoff.kt`, `FileSeenStore.kt`; test `BackoffTest.kt`; rewrite `BridgeService.kt`.

**Interfaces:**
- Produces: `class Backoff(baseMs: Long = 2_000, capMs: Long = 60_000, random: () -> Double)` with `fun nextDelayMs(): Long` (doubling with 0–20 % jitter, capped) and `fun reset()`.
- Consumes: `Gate`, `parseNtfyLine`, `Receipts`, `CredentialStore`, `PILOT_REGISTRY`.

- [ ] **Step 1: `BackoffTest`:** with `random = { 0.0 }`, the delays are 2000, 4000, 8000, 16000, 32000, 60000, 60000; `reset()` returns to 2000; with `random = { 1.0 }`, no delay exceeds `capMs * 1.2`.
- [ ] **Step 2: Implement `Backoff`, PASS.**
- [ ] **Step 3: `FileSeenStore`** implements `SeenStore` on `filesDir/seen_ids.txt`, rewritten through a temp file and `renameTo`, synchronized.
- [ ] **Step 4: Rewrite `BridgeService`:**
  - One connection for both topics: `GET <server>/mab-<slug>-owner,mab-<slug>-family/json`. Each message's channel comes from its `topic` through `Gate`.
  - Every line goes through `parseNtfyLine` → `Gate.decide` → act on the `Decision`.
  - `Run(Launch)`: resolve the launch intent (D3) → announce → `startActivity` → `ok` receipt.
  - `Run(RingFind)`: announce → ring as an alarm for 30 s, with a "Stop ringing" notification action and the ring stopping on STOP all.
  - `Run(StatusReport)`: battery %, `versionName`, protocol `0.2`, channel states.
  - Rustdesk commands: `not-implemented`.
  - Every decision appends one log line: time, channel, `logExcerpt(body)`, result.
  - Receipts go to `mab-<slug>-ack` on one single-thread executor (not a thread per receipt).
  - The announcement names the channel ("Opening Meet: asked by family"; PROTOCOL §5.4).
  - Replace the deprecated `setPriority` (rely on the channel's importance).
  - Fix #1: replace the `dataSync` foreground service type, which can't start from `BOOT_COMPLETED` and is limited to about 6 h a day on targetSdk 35+. Choose between `remoteMessaging` and `specialUse` from the Android 16 docs, and update the manifest's `FOREGROUND_SERVICE_*` permission. Implement `onTimeout` defensively. Task 8 confirms it with a reboot and a run longer than 6 h.
- [ ] **Step 5: Run unit tests and `assembleDebug`, expect `BUILD SUCCESSFUL`. Commit** `app: service driven by the protocol gate`.

### Task 7: Owner screen

**Files:** Modify `MainActivity.kt`, `Config.kt`.

- [ ] **Step 1:** Two large, separately labeled switches: "Stop family commands" and "Stop all commands". The state is in prefs, read on every `Gate.decide` (so it applies instantly), and survives reboot (PROTOCOL §7).
- [ ] **Step 2:** A status line showing service running or stopped, the channels on, and the last connection time. Show the last 50 log lines, newest first.
- [ ] **Step 3:** Setup fields: server, device slug, username, and the password (Task 5). It refuses to start the service until all four are set.
- [ ] **Step 4:** `assembleDebug` passes. **Commit** `app: owner screen with STOP switches and log`.

### Task 8: Pilot install and device gates (each step needs Chris's go)

- [ ] **Step 1:** Chris disables the Tasker MAB profile (one executor per phone, §3). Confirm by sending `status report` and getting no Tasker receipt.
- [ ] **Step 2:** `adb install -r app-debug.apk`. Chris enters the phone credential on the setup screen. Then Task 5 Step 3.
- [ ] **Step 3:** Run B1, B3, B4, B6 and A3, A4 from TEST-PLAN.md. Record the results in `tasker/RESEARCH.md`'s successor, `app/DEVICE-RESULTS.md`.
- [ ] **Step 4:** A1, A2 and A5 (a day of commands, VPN on and off, 48 h of battery), then B2 and B5.
- [ ] **Step 5:** Update `app/DEV-HANDOFF.md` and the README status with the results.

Release signing (a kept key, so later builds update in place) is a separate small PR before any phone other than chris-razr. It's out of scope here.
