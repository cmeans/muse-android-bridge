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

Phase 1 in progress (2026-11-11). Design documents complete; the pilot
device's ntfy principals are provisioned and verified on the self-hosted
server, and security drills F1 (publisher revocation) and F2 (phone
credential scope) have passed — see `server/ntfy-provisioning.md`.
The Tasker pilot validated the wire protocol end to end on the pilot
phone (test group A1 confirmed live).

**Decision (maintainer, 2026-11-11 22:38 CT, superseding the same-day
"Tasker first, app in parallel" plan): go the native app route NOW.**
Rationale: the Tasker setup experience — imports, project management,
per-version XML dialects — is helper-tolerable but wrong-shaped for a
frozen, never-touched family deployment; the app (store/sideload
install, self-updating, one purpose) is the right surface for the owner.
Tasker work freezes as validated research: PROTOCOL.md is
executor-agnostic, so every finding (topics, ACLs, TTL/dedup,
cached-replay behavior, registry, STOP semantics) carries over
unchanged. Next: native Android app v1 — own ntfy subscription,
registry launch, announce, ack, STOP UI — tested on the pilot phone
first, same rollout order (pilot phone → helper's phone → owner's
phone). The maintainer will hand implementation to claude-dev once
the path is understood; the map, current scaffold status, open
decisions, and acceptance boundary are in `app/DEV-HANDOFF.md`. The
app under `app/android/` builds (since 2026-11-12) and has passed its
first device tests on the pilot phone; it isn't ready for family
phones yet.

Remote: `cmeans/muse-android-bridge` (private). This VM cannot push over
git SSH/HTTPS (egress resets SSH), so history is published as one
API commit per local commit via the GitHub connector's `push-files`
(snapshot per commit, same messages). Remote commit SHAs therefore
differ from local ones; content and order match.

## License

Apache License 2.0: see [LICENSE](LICENSE).
