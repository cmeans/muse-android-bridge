# muse-android-bridge

[![Project Status: WIP](https://www.repostatus.org/badges/latest/wip.svg)](https://www.repostatus.org/#wip)
[![CI](https://github.com/cmeans/muse-android-bridge/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/cmeans/muse-android-bridge/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 10+ (API 29)](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white)

> **Status: work in progress.** Not ready for use on anyone's phone yet:
> there is no release, and the design is still being tested (see
> [Status](#status) and `TEST-PLAN.md`).

A whitelisted app-launch bridge and remote-fix architecture for a Muse-paired
Android phone.

**End goal:** someone launches *their own* Muse on their phone and asks it to
fix a thing on the phone — and their Muse fixes it. The owner's Muse
triages from paired-phone data, escalates through the bridge (fixed,
announced, logged commands over authenticated ntfy topics), and, when a fix
needs hands, drives the phone via a self-hosted RustDesk server and a
per-operator controller stack, while the owner performs only the taps
Android reserves for a human finger.

Two help paths share one phone deployment:

- **Self-help** — the owner asks their own Muse (owner channel, full menu,
  controller in that Muse's own environment).
- **Family help** — designated family members launch apps for the owner
  (family channel, launch-only, one topic per member; a family RustDesk
  launch waits for the owner's tap on the phone) or drive via their own
  controller.

## Documents

- `ARCHITECTURE.md` — actors, components, the two paths, session walkthrough,
  scope of "fix a thing," rollout phases, open decisions.
- `PROTOCOL.md` — the wire protocol: topics, per-principal ACLs, command
  registry, 120 s TTL + dedup, receipts, on-phone STOP semantics.
- `TEST-PLAN.md` — every unknown the design assumes, with a test and a pass
  bar for each. Nothing reaches the first real user's phone until the
  relevant items have recorded results.

## Layout

- `app/android/` — the native Android app: the bridge that runs on the
  phone.
- `tasker/` — an earlier Tasker executor, kept as research. It validated
  the wire protocol; the app replaces it.
- `server/` — provisioning notes for the ntfy topics and user ACLs.
- `controller/` — the per-operator controller stack (virtual display,
  RustDesk client, screenshot and input shim). Not built yet.

## Status

Early development. The design documents are written, and the wire protocol
has been validated end to end on a test phone. The Android app builds and has
passed its first device tests, but it isn't ready for anyone to install yet,
and there is no release.

## License

Apache License 2.0: see [LICENSE](LICENSE).
