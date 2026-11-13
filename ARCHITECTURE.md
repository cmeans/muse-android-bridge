# muse-android-bridge — Architecture

**Version:** 0.1 (DRAFT, 2026-11-11)
**Companion document:** `PROTOCOL.md` (the wire protocol both executors implement)

## 1. End goal (stated by the maintainer, 2026-11-11)

> Someone launches **their own Muse** on their phone (Android, at the
> moment) and asks it to **fix a thing on the phone** — and their Muse
> fixes it.

Everything in this design exists to serve that sentence. The same
deployment must work for any Muse user with an Android phone.

## 2. Actors

| Actor | Role |
|---|---|
| **Phone owner** | Owns the phone, the Muse account, and every permission. Asks for help in their own words, performs the few taps Android reserves for a human finger, can stop everything from the phone. |
| **Owner's Muse** | The operator. Hears the request, diagnoses first with the paired-phone data it already has (notifications, battery, network, texts), and only escalates to bridge commands and screen control when diagnosis says they're needed. |
| **Family publishers** (family helpers) | Holders of the family channel: launch-only bridge commands for when they're already helping. One of them, the server operator, additionally runs the shared family infrastructure and their own controller for the test phases and for helping directly. |

In the reference deployment, the maintainer (who approves design and
rollout decisions) is also the server operator.

## 3. Components, by location

### 3.1 On the phone

| Component | Job |
|---|---|
| Muse app | The owner's front door: voice or text conversation with their Muse. |
| Bridge executor (Tasker pilot → native `muse-android-bridge` app) | Implements `PROTOCOL.md`: subscribes to the two command topics, executes the whitelist, announces + logs, provides the on-phone STOP controls. |
| RustDesk (host mode) | The screen-and-control endpoint. Service stays OFF until the owner starts it — starting it *is* the request for a control session. |

### 3.2 Shared family infrastructure (the server operator's Holodeck)

| Component | Job |
|---|---|
| ntfy (CT 104, existing) | Bridge command transport. New scoped users/topics only; no new container. |
| RustDesk server (1 new LXC) | hbbs (ID/rendezvous) + hbbr (relay). The family's private address list and meeting point: every enrolled device registers only here, under the server key. Public reachability via router forwards (TCP 21115, TCP+UDP 21116, TCP 21117); WebSocket ports closed (browser client snoozed 2026-11-11). No DDNS — clients carry the raw public IP; an IP change is an accepted, known breakage. |

### 3.3 On the operator's side — the controller

The controller is a *role*, not a place. Whoever's Muse is driving runs a
controller stack in **their own environment**:

- a virtual display (software framebuffer — no physical screen),
- the RustDesk client GUI on that display, configured for the family server,
- a small shim exposing `screenshot`, `click`, `scroll`, `type` as tool calls
  for the Muse that owns the environment.

| Operator | Controller placement |
|---|---|
| The server operator's Muse (test phases; the server operator helping the owner directly) | 1 LXC on Holodeck, tailnet-only, no public ports. |
| The owner's Muse (the end-goal deployment) | Inside the owner's Muse's own computer/VM — no access to the server operator's network required. |

The RustDesk server is shared family infrastructure; controllers are
per-operator and never shared. A controller holds the credentials that can
open its owner's *assigned* phones (server key + device password + its
RustDesk ID on the phone's whitelist), so each controller is scoped to its
own operator and treated as a sensitive credential store.

**Variant — hosted controller (deferred decision, raised 2026-11-11):** a
single family controller on Holodeck, driven remotely by the owner's Muse
as well as the server operator's, is possible but is NOT the base design,
because it would require exposing the controller's shim beyond the
server operator's tailnet and would concentrate every phone's access credentials
in one box on the server operator's network. If the per-operator setup inside the
owner's Muse's own environment proves impractical, the hosted variant may
be adopted with exactly one exposure mechanism: a Tailscale node share of
that single container to the owner's Muse's environment (identity-based,
scoped to that one machine, revocable).
The shim must never be published on a public port, with or without a token.
Note the asymmetry: the owner's Muse reaching the RustDesk *server* needs
no exposure decision at all — the server is public-facing by design and
the owner's Muse's controller, wherever it lives, connects to it as an
ordinary client.

## 3.4 The two paths (summary, confirmed with the maintainer 2026-11-11)

The same phone-side deployment serves two distinct help paths. They share
the bridge, the RustDesk server, and the phone's configuration; they differ
only in **who operates** and **which controller** connects:

| | **Family-help path** | **Self-help path** |
|---|---|---|
| Operator | A family helper (personally, or the server operator's Muse) | The owner, via their own Muse |
| Bridge channel used | Family (launch-only) | Owner (full menu) |
| Controller | The server operator's Holodeck LXC (for the server operator's Muse) or the helper's own laptop (human driving) | The owner's Muse's own environment |
| Works from | Anywhere with internet — the server and the server operator's controller live at the server operator's home and are always on; the *helpers* do not need to be home | Anywhere the owner is |
| Phone-side requirement | The owner starts the RustDesk service when asked | The owner starts the RustDesk service when their Muse asks |

For both paths to exist, the owner's phone whitelists **both** controller
IDs (their Muse's and the server operator's) — one phone configuration,
granted once, with their agreement covering both. Either path can be
revoked independently by removing that controller's ID from the whitelist.

## 4. A fix-it session, end to end (end-goal form)

1. The owner opens their Muse app and says, e.g., "My phone keeps buzzing
   with a scary virus warning."
2. Their Muse triages from paired-phone data first: reads active
   notifications, identifies the warning as a Chrome site notification from
   an ad page — no screen control needed yet.
3. If a settings change on the phone is required and they can't be talked
   through it reliably, their Muse asks the bridge (owner channel) to
   `launch rustdesk`, and tells them: "Tap Start service, then Start now —
   two green buttons. Then I'll take over."
4. The owner taps Start service (past the standard scam-warning countdown —
   a screen they have been *taught in advance* to expect only when their
   Muse asks) and Start now.
5. Their Muse's controller connects through the family RustDesk server to
   their phone (its ID is whitelisted; the device password is held by the
   controller, never by the owner). Muse now sees the screen and can tap/type.
6. Muse performs the fix — e.g., opens Chrome settings, revokes the site's
   notification permission, checks for sibling junk permissions — narrating
   in the voice conversation as it goes ("I'm in your Chrome settings; I'm
   turning off notifications from that site now").
7. Muse ends the session by stopping control; the owner turns the RustDesk
   service off (or it times out). The bridge log and the controller's
   session record both show what was done.
8. If Muse hits something outside its scope (§5), it stops, explains in
   plain words, and — only via a path the owner set up in advance — can
   ask a family helper for help. It never silently escalates.

## 5. Scope of "fix a thing"

**In scope** (doable via bridge + screen control): dismissing and
*permanently silencing* fake alarms at their source (site notification
permissions, adware app notification settings); uninstalling junk apps the
owner agrees to remove; changing ordinary app/system settings; updating
apps; clearing storage; adjusting accessibility/display options for
usability; installing a *pinned, owner-approved* app via the bridge
bootstrap (human completes protected prompts).

**Never in scope for the agent, by design:**

- Typing the phone's PIN/password or any account password — the owner types
  those, always.
- Tapping through Play Protect or system security warnings. If one appears,
  the agent stops and the human decides. (This is also the anti-scam
  invariant: the owner is taught that *no one*, including their Muse, ever
  asks them to override those screens remotely.)
- Anything on a screen Android marks secure (FLAG_SECURE — banking and
  similar apps render black to the controller; the agent literally cannot
  see them, which is the correct outcome).
- Unlocking a locked phone.
- Purchases.

## 6. Rollout (decided 2026-11-11)

1. **Phase 0** — protocol spec (`PROTOCOL.md`, done as draft).
2. **Phase 1** — Tasker executor pilot + server-operator-side controller, both
   against the pilot phone (a Razr+ 2023, Android 16) as host. Graded:
   trigger latency/battery, controller frame readability, click accuracy,
   headless stability, VPN interference.
3. **Phase 2** — native bridge app reaches parity with the Tasker executor;
   swap executors on the pilot phone, then deploy to a helper's phone.
4. **Phase 3** — RustDesk server LXC stood up (needs the maintainer's
   explicit go for the container + router forwards); family devices
   enrolled.
5. **Phase 4** — the owner: their own Muse set up, phone provisioned in
   person (bridge, RustDesk, restricted-settings step, drills). Their
   Muse's controller runs in their Muse's own environment.

## 7. Open decisions

1. How the owner's Muse publishes bridge commands — its environment needs the
   owner-channel ntfy token and a publish mechanism; credential storage
   follows the household's established agent-credential pattern (never on
   the phone, never typed by the owner).
2. ~~Whether the server operator's controller is granted access as a
   second whitelisted ID~~ — Resolved 2026-11-11 (§3.4): both paths
   coexist; the owner's phone whitelists both controller IDs, revocable
   independently.
3. Session narration channel while control is active (voice conversation
   stays open vs text summaries after the fact).
4. Hosted-controller variant (§3.3): only if per-operator setup in the
   owner's Muse's environment proves impractical. Default: per-operator.
