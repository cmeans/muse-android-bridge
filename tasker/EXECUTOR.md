# Tasker executor — build sheet (pilot: pilot-phone)

> Frozen research (2026-11-12): the native app replaces this executor. Where
> this sheet differs from PROTOCOL.md (v0.3, and 0.4's receipt ids, which this
> sheet never had) (check order, receipt wording, rate limits, launch checks),
> PROTOCOL wins.

> **Owner constraint (maintainer, 2026-11-11, after working the pilot
> setup themselves):** "This is too much for the phone's owner."
> Correct — and by design the owner never sees any of it. Everything in this sheet is
> helper-side work, done by a family helper, ideally in one in-person
> setup visit. The owner's entire surface is: open Muse, ask for help,
> tap Start service / Start now after asking their Muse for help, and the STOP
> scenes. No imports, no settings, no Tasker UI, ever. Corollary: the
> owner's phone configuration must be treated as frozen once delivered —
> no design step may depend on the owner re-importing, updating, or
> reconfiguring anything. This is also the strongest argument yet for
> the native app on the owner's device: store install (which updates itself; a sideloaded build
> doesn't) beats helper-maintained Tasker XML, so the app-at-parity swap matters
> most for the owner phase, not the pilot.

Build interactively on the phone (Tasker's UI generates valid project XML;
hand-written XML is import-fragile). This sheet is the exact logic to enter,
from PROTOCOL.md. Export the finished project to `tasker/` in this repo.

The exported `tasker/*.xml` files subscribe to the example server
`ntfy.example.com` and the example slug `pilot-phone`. Replace both with your
own before importing them.

## On-phone prerequisites (session checklist)

1. Tasker installed from Play, bought on a family helper's account (Family Library
   source for later phases).
2. ntfy app: subscribe to `mab-pilot-phone-owner` and `mab-pilot-phone-family`
   (pilot may ride the existing admin sign-in; the dedicated
   `pilot-phone-phone` credential is for family devices — its scope is
   already verified server-side, test F2). **Mute both topics** so commands
   don't pile up as notifications; the broadcast intent still fires.
3. Battery: exempt Tasker and the ntfy app from battery optimization
   (needed for honest A1/A5 measurements, and for the real deployment).
4. No accessibility service, no notification access, no ADB for v1 — the
   intent path needs none of them. Grant nothing else during setup.

## Probe task first (answers A1's remaining device questions)

Profile: Event → System → **Intent Received**, Action
`io.heckel.ntfy.MESSAGE_RECEIVED` → Task "MAB Probe": Flash + Write File
appending one line per broadcast: `%TIME | topic=%topic id=%id time=%time
msg=%message muted=%muted`. Purpose: confirm the Tasker variable names for
the extras (source-verified extras: `id`, `time`, `topic`, `message`,
`muted`) and that muted topics still broadcast. Run 5 manual commands,
check the log, then disable the probe.

## Executor task "MAB Execute" (single entry point, both topics)

Inputs from the intent: `%topic`, `%message`, `%id`, `%time`.

1. **STOP gates.** If project variable `%MAB_STOP_ALL` = 1 → log, stop.
   If `%topic` ends in `-family` and `%MAB_STOP_FAMILY` = 1 → log, stop.
   (Project variables persist across reboot — B4.)
2. **TTL.** If `%TIMES - %time > 120` → ack `err stale %id`, log, stop.
3. **Dedup.** Read `Tasker/mab/seen.txt` (lines `id epoch`). If `%id`
   present → log duplicate, stop (no ack — the first receipt stands).
   Else append, and prune lines older than 24 h.
4. **Parse (never interpret).** Exact-match `%message` against the
   registry patterns for the channel:
   - both channels: `launch meet|whatsapp|facebook|chrome|rustdesk|muse`,
     `ring find`
   - owner only: `bootstrap rustdesk`, `update rustdesk`, `status report`
   No match → ack `err unknown-command %id`, log, stop. (B6.)
5. **Announce.** Say: "Bridge: launching Meet" (or the action), media
   stream, before acting. Never silent. (B3.)
6. **Act.**
   - `launch <app>`: Launch App by package name (registry in PROTOCOL §4);
     verify with a short wait + foreground check; ack `ok launch <app>`.
   - `ring find`: play ringtone at max media volume 30 s or until the
     on-phone stop scene is tapped; ack `ok ring find`.
   - `bootstrap/update rustdesk`, `status report`: stub in v1 — ack
     `err not-implemented` and log; real bodies arrive with Phase 3.
7. **Ack.** HTTP Request POST to the ack topic with JSON
   `{"topic":"mab-<device>-ack","message":"<receipt>"}` and the phone
   credential in the Authorization header. Credential is entered once via
   Variable Query in a separate "MAB Setup" task and stored in a project
   variable — **never exported with the shared project file**; the export
   ships with the variable empty.
8. **Log.** Append to `Tasker/mab/log.txt`:
   `ISO-time | topic | id | command | result`.

## STOP scenes (on-phone, no remote input)

Two persistent home-screen shortcuts / Tasker scenes:
- "STOP family" → sets `%MAB_STOP_FAMILY` = 1, Say "Family commands off",
  ack `ok stop-family on`. A second tap clears it (with its own announce).
- "STOP all" → same for `%MAB_STOP_ALL`.

These are the owner's physical override; per PROTOCOL §6 they must work
with no network and survive reboot.

## Test hooks

Every A/B test in TEST-PLAN.md names the log lines and ack receipts it
expects; if a test can't find its evidence in `log.txt` + the ack topic,
the executor gets a logging fix before the test is re-run — evidence
first, per house rules.
