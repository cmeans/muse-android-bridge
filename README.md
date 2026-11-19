# muse-android-bridge

[![Project Status: WIP](https://www.repostatus.org/badges/latest/wip.svg)](https://www.repostatus.org/#wip)
[![CI](https://github.com/cmeans/muse-android-bridge/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/cmeans/muse-android-bridge/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 10+ (API 29)](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white)

> **Status: work in progress.** Not ready for use on anyone's phone yet
> (see [Status](#status)).

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
  phone. Its implementation notes are in `app/DEV-HANDOFF.md`.
- `tasker/` — an earlier Tasker executor, kept as research. It validated
  the wire protocol; the app replaces it.
- `server/` — provisioning notes for the ntfy topics and user ACLs.
- `controller/` — the per-operator controller stack (virtual display,
  RustDesk client, screenshot and input shim). Not built yet.
- `tests/` — tests for the repository's own CI workflows.

## Status

Early development. The design documents are written, and the wire protocol
has been validated end to end on a test phone. The Android app builds and has
passed its first device tests, but it isn't ready for anyone to install yet,
and there is no release.

## Acknowledgments

This project is being built with a **Spec-First Coding** approach: a
human-AI collaboration model where design comes before implementation, and
the specs are the contract between the two.

Unlike vibe coding, where you describe what you want and let the AI generate
code on the fly, spec-first coding treats design as its own, deliberate phase.
`ARCHITECTURE.md`, `PROTOCOL.md` and `TEST-PLAN.md` were worked out by the
maintainer in conversation before any code was written: trade-offs explored,
alternatives rejected, decisions written down with their reasons.
`TEST-PLAN.md` turns every assumption in the design into a test with a pass
bar.

Running the Tasker pilot on a real phone tested the design. It confirmed one
assumption: the ntfy app delivered cached messages as soon as it subscribed,
so the protocol's 120-second freshness limit and deduplication are
load-bearing, not decorative (`tasker/RESEARCH.md`). It also showed something
the design hadn't settled: Tasker's setup was workable for a helper but wrong
for a phone its owner should never have to reconfigure, so a native app
replaced the pilot (`tasker/EXECUTOR.md`).

The work is split into roles. [Chris Means](https://github.com/cmeans) designs
the project, makes the decisions and approves every merge. Since the
maintainer handed implementation over, after writing the app's first
scaffold, a [Claude Code](https://claude.com/claude-code) Developer session
keeps the specs current and writes the app and its tests. A separate Claude
Code QA session reviews every pull request on its own, and the maintainer's
own Muse (the AI assistant the bridge is built for) manages the ntfy server's
users and access rules and reviews each pull request from the side of the
assistant that will use it, with its approval required to merge.
The agents share project state through
[mcp-awareness](https://github.com/cmeans/mcp-awareness).

## License

Apache License 2.0: see [LICENSE](LICENSE).
