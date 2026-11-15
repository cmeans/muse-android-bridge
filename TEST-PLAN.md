# muse-android-bridge — Unknowns & Test Plan

**Version:** 0.1 (2026-11-11)
Every item here is a genuine unknown: something the design assumes but nobody
has measured yet. Each test names its method and what "pass" looks like.
Phase tags refer to `ARCHITECTURE.md` §6. Nothing on the owner's phone is
touched until every Phase 1–3 item that bears on it has a recorded result.

## A. Bridge transport (ntfy) — Phase 1

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| A1 | Can the executor receive commands reliably, and by which mechanism? | Tasker reacting to the ntfy app's notifications vs any direct-subscribe approach; 50 commands across a day, screen on/off, app usage in between | ≥98% delivered, latency p95 < 10 s, chosen mechanism documented |
| A2 | Does the pilot phone's always-on consumer VPN break publish or subscribe in either direction? | Repeat A1 with VPN on (its normal state) and off | No material difference; if there is, a documented rule (e.g. VPN bypass for the ntfy app) |
| A3 | Do stale commands stay dead? | Phone offline 2 h while commands are published; reconnect | Every command older than 120 s dropped-stale, zero late launches, drops visible in the on-phone log |
| A4 | Dedup under redelivery | Force reconnect/redelivery of the same message IDs | Each command executes at most once |
| A5 | Battery cost of the receiving mechanism | 48 h normal use with executor live vs baseline drain notes | No user-visible battery regression (pilot phone's battery is already marginal) |

## B. Bridge executor (Tasker pilot, later the native app) — Phase 1–2

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| B1 | Exact package names on real hardware (Meet, Muse, RustDesk, …) | Read installed packages on the pilot phone; record per-device registry | Registry in executor config matches; every `launch` target verified |
| B2 | Launch behavior on a locked phone | Fire `launch meet` while locked; unlock minutes later | Nothing weird on the lock screen; app is foreground (or predictably not) after unlock; behavior documented for the family runbook |
| B3 | Announce quality | Trigger each command class; is the banner/TTS understandable and does it name the channel? | A bystander can say afterwards who asked for what |
| B4 | STOP controls | Disable family channel on-phone; fire family + owner commands; reboot; repeat | Family dead instantly, owner alive; states survive reboot; re-enable is owner-only and local |
| B5 | Log integrity | Run a mixed day of commands | On-phone log complete, timestamped, matches ack receipts (past 5 stale messages in a reconnect backlog, the log counts the receipts not sent) |
| B6 | Malformed-input safety | Publish junk bodies, near-miss commands, oversized bodies, wrong-topic messages | Zero actions taken; `err unknown-command` receipts for junk on a command topic, no receipt for other topics (PROTOCOL §5) |
| B8 | Launch and rate-limit receipts (protocol 0.3) | With usage access on: launch an app (`ok`), the same app again while in front (`ok`), launch on a locked phone (`ok`); with "Display over other apps" off (`err launch <app> cannot-launch`); 4 `ring find` in 10 minutes on one channel, each sent after the previous ring ends (the 4th `err ring find rate-limited`, a 5th logged only); a launch that can't come to the front within 5 s (`err launch <app> blocked`); usage access off (`ok`, log says unverified, `status report` says `launch-check=off`) | Every receipt matches PROTOCOL §6; nothing opens or rings when refused |
| B9 | Receipt ids (protocol 0.4) | Send one of each owner command, one junk body, and one stale message (publish, wait > 120 s offline, reconnect) | Every receipt ends with `id=<the id ntfy gave that command>`; `status report` says `protocol=0.4`, and its `time=` is within 5 s of the receipt's ntfy time; matches PROTOCOL §6 |
| B7 | Native-app parity (Phase 2) | A1–A5 + B2–B6 rerun against the app executor, including one boring week of background operation | Same results as Tasker executor, or documented differences accepted before swap |

## C. RustDesk server & Android host — Phase 3

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| C1 | Hole-punch vs relay from real networks | Connect controller → pilot phone on home WiFi, on mobile data, and on a third-party WiFi | Session establishes in all three; direct-vs-relay path recorded per case |
| C2 | The owner's per-session tap count, cold | Kill RustDesk, reboot phone, start a session from scratch, count required human taps (incl. after service kill) | Exact tap list written into the runbook; nothing beyond Start service / Start now + any one-time grants |
| C3 | Accessibility/input persistence | Reboot; check Input Control accessibility state | Survives reboot, or the re-enable steps are in the runbook |
| C4 | Sideload reality on the target Android version | Install official RustDesk build on pilot phone; note Play Protect behavior and the restricted-settings flow step by step | Full step list recorded; no undocumented surprise left for the in-person setup session |
| C5 | Whitelist semantics | Two controller IDs whitelisted; connect from both; remove one; retry | Both work; removed ID refused before password prompt; re-adding restores |
| C6 | IP-change breakage (accepted risk — no DDNS) | Change the configured server address to a wrong IP; observe failure mode; restore | Failure is obvious and diagnosable; recovery procedure written down |

## D. Controller (per-operator stack) — Phase 1 (server operator), Phase 4 (owner)

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| D1 | Headless stability | RustDesk client on a virtual display for a full working session; force a network drop mid-session | No client crash; clean reconnect |
| D2 | Frame readability | Screenshot the controller at phone resolution; read small UI text (settings lists, dialog fine print) | Text legible enough to act on without guessing; if not, a zoom/crop workflow is defined |
| D3 | Input accuracy | Scripted task list: tap 20 assorted targets incl. small ones, scroll a list, type into fields, use Back/Home | ≥19/20 first-try taps; typing lands intact; misses are detectable from the next screenshot |
| D4 | Loop latency | Time tap → next usable screenshot, direct and relayed | Tolerable for a fix-it pace; numbers recorded |
| D5 | The two reference fixes | End to end via controller only: (1) revoke a Chrome site's notification permission; (2) uninstall a dummy app | Both completed by the agent alone (plus owner's §C2 taps) |
| D6 | Secure screens | Open a FLAG_SECURE app (banking) during a session | Controller sees black; agent behavior on black frames = stop and say so |

## E. Owner experience & the other Muse — Phase 4

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| E1 | How does the owner's Muse publish owner-channel commands? | Mechanism + credential storage in their Muse's environment; fire the full registry | Works with credentials never touching the phone or the owner |
| E2 | Voice + control simultaneously | Voice conversation with their Muse open while a control session runs on the same phone | Narration usable; audio routing sane; if not, narration moves to before/after summaries (open decision §7.3) |
| E3 | Drill effectiveness | Teach the Start-service sequence incl. the scam-warning countdown; test cold a week later | They perform it unaided and can state the rule: only when their Muse asks, never for a caller |
| E4 | Fake-alarm end-to-end (the motivating case) | Plant a Chrome site-notification alarm; owner asks their Muse to fix it; nobody helps | Fixed via the §4 session flow; time and tap count recorded |

## F. Security drills — before Phase 4 completes

| # | Unknown | Test | Pass looks like |
|---|---|---|---|
| F1 | Leaked family token | Rotate/disable the family publisher credential; fire old-credential commands | All refused; owner channel unaffected; procedure timed |
| F2 | Phone credential theft simulation | Use the phone's read-mostly credential from another client | Can read topics/forge receipts only; cannot issue a command |
| F3 | Rogue controller attempt | Connect from a non-whitelisted RustDesk ID with the correct device password | Refused at whitelist, before password matters |
