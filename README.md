# muse-android-bridge

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
  (family channel, launch-only) or drive via their own controller.

## Documents

- `ARCHITECTURE.md` — actors, components, the two paths, session walkthrough,
  scope of "fix a thing," rollout phases, open decisions.
- `PROTOCOL.md` — the wire protocol: topics, per-principal ACLs, command
  registry, 120 s TTL + dedup, receipts, on-phone STOP semantics.
- `TEST-PLAN.md` — every unknown the design assumes, with a test and a pass
  bar for each. Nothing reaches the first real user's phone until the
  relevant items have recorded results.

## Layout (to be built)

- `tasker/` — the pilot executor: an importable Tasker project implementing
  the protocol.
- `app/` — the native Android executor that replaces Tasker at parity.
- `controller/` — the per-operator controller stack (virtual display +
  RustDesk client + screenshot/input shim).
- `server/` — provisioning notes/config for the RustDesk server (hbbs/hbbr)
  and the ntfy topic/user ACLs.

## Status

Design phase (2026-11-11). Protocol and architecture drafted; test plan
open; no code yet. Rollout: developer's phone → second family phone → first
real user.
