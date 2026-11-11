# muse-android-bridge — Architecture

**Version:** 0.1 (DRAFT, 2026-11-11)
**Companion document:** `PROTOCOL.md` (the wire protocol both executors implement)

## 1. End goal (stated by Chris, 2026-11-11)

> Someone (Peggy, in this instance) launches **their own Muse** on their
> phone (Android, at the moment) and asks it to **fix a thing on the phone** —
> and their Muse fixes it.

Everything in this design exists to serve that sentence. Peggy is the first
real user, not the definition: the same deployment must work for any Muse
user with an Android phone.

## 2. Actors

| Actor | Role |
|---|---|
| **Phone owner** (Peggy) | Owns the phone, the Muse account, and every permission. Asks for help in her own words, performs the few taps Android reserves for a human finger, can stop everything from the phone. |
| **Owner's Muse** (Peggy's Muse) | The operator. Hears the request, diagnoses first with the paired-phone data it already has (notifications, battery, network, texts), and only escalates to bridge commands and screen control when diagnosis says they're needed. |
| **Family publishers** (Chris, Michele) | Holders of the family channel: launch-only bridge commands for when they're already helping. Chris additionally operates the shared family infrastructure and his own controller for the test phases and for helping directly. |

## 3. Components, by location

### 3.1 On the phone

| Component | Job |
|---|---|
| Muse app | The owner's front door: voice or text conversation with her Muse. |
| Bridge executor (Tasker pilot → native `muse-android-bridge` app) | Implements `PROTOCOL.md`: subscribes to the two command topics, executes the whitelist, announces + logs, provides the on-phone STOP controls. |
| RustDesk (host mode) | The screen-and-control endpoint. Service stays OFF until the owner starts it — starting it *is* the request for a control session. |

### 3.2 Shared family infrastructure (Chris's Holodeck)

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
| Chris's Muse (test phases; Chris helping Peggy directly) | 1 LXC on Holodeck, tailnet-only, no public ports. |
| Peggy's Muse (the end-goal deployment) | Inside Peggy's Muse's own computer/VM — no access to Chris's network required. |

The RustDesk server is shared family infrastructure; controllers are
per-operator and never shared. A controller holds the credentials that can
open its owner's *assigned* phones (server key + device password + its
RustDesk ID on the phone's whitelist), so each controller is scoped to its
own operator and treated as a sensitive credential store.

**Variant — hosted controller (deferred decision, raised 2026-11-11):** a
single family controller on Holodeck, driven remotely by Peggy's Muse as
well as Chris's, is possible but is NOT the base design, because it would
require exposing the controller's shim beyond Chris's tailnet and would
concentrate every phone's access credentials in one box on Chris's network.
If the per-operator setup inside Peggy's Muse's own environment proves
impractical, the hosted variant may be adopted with exactly one exposure
mechanism: a Tailscale node share of that single container to Peggy's
Muse's environment (identity-based, scoped to that one machine, revocable).
The shim must never be published on a public port, with or without a token.
Note the asymmetry: Peggy's Muse reaching the RustDesk *server* needs no
exposure decision at all — the server is public-facing by design and her
controller, wherever it lives, connects to it as an ordinary client.

## 3.4 The two paths (summary, confirmed with Chris 2026-11-11)

The same phone-side deployment serves two distinct help paths. They share
the bridge, the RustDesk server, and the phone's configuration; they differ
only in **who operates** and **which controller** connects:

| | **Family-help path** | **Self-help path** |
|---|---|---|
| Operator | Chris or Michele (personally, or Chris's Muse) | Peggy, via her own Muse |
| Bridge channel used | Family (launch-only) | Owner (full menu) |
| Controller | Chris's Holodeck LXC (for Chris's Muse) or the helper's own laptop (human driving) | Peggy's Muse's own environment |
| Works from | Anywhere with internet — the server and Chris's controller live at Chris's home and are always on; the *helpers* do not need to be home | Anywhere Peggy is |
| Phone-side requirement | Peggy starts the RustDesk service when asked | Peggy starts the RustDesk service when her Muse asks |

For both paths to exist, Peggy's phone whitelists **both** controller IDs
(her Muse's and Chris's) — one phone configuration, granted once, with
her agreement covering both. Either path can be revoked independently by
removing that controller's ID from the whitelist.

## 4. A fix-it session, end to end (end-goal form)

1. Peggy opens her Muse app and says, e.g., "My phone keeps buzzing with a
   scary virus warning."
2. Her Muse triages from paired-phone data first: reads active
   notifications, identifies the warning as a Chrome site notification from
   an ad page — no screen control needed yet.
3. If a settings change on the phone is required and she can't be talked
   through it reliably, her Muse asks the bridge (owner channel) to
   `launch rustdesk`, and tells her: "Tap Start service, then Start now —
   two green buttons. Then I'll take over."
4. Peggy taps Start service (past the standard scam-warning countdown — a
   screen she has been *taught in advance* to expect only when her Muse
   asks) and Start now.
5. Her Muse's controller connects through the family RustDesk server to her
   phone (its ID is whitelisted; the device password is held by the
   controller, never by Peggy). Muse now sees the screen and can tap/type.
6. Muse performs the fix — e.g., opens Chrome settings, revokes the site's
   notification permission, checks for sibling junk permissions — narrating
   in the voice conversation as it goes ("I'm in your Chrome settings; I'm
   turning off notifications from that site now").
7. Muse ends the session by stopping control; Peggy turns the RustDesk
   service off (or it times out). The bridge log and the controller's
   session record both show what was done.
8. If Muse hits something outside its scope (§5), it stops, explains in
   plain words, and — only via a path Peggy set up in advance — can ask
   Chris or Michele for help. It never silently escalates.

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
  invariant: Peggy is taught that *no one*, including her Muse, ever asks
  her to override those screens remotely.)
- Anything on a screen Android marks secure (FLAG_SECURE — banking and
  similar apps render black to the controller; the agent literally cannot
  see them, which is the correct outcome).
- Unlocking a locked phone.
- Purchases.

## 6. Rollout (decided 2026-11-11)

1. **Phase 0** — protocol spec (`PROTOCOL.md`, done as draft).
2. **Phase 1** — Tasker executor pilot + Chris-side controller, both against
   Chris's phone as host. Graded: trigger latency/battery, controller frame
   readability, click accuracy, headless stability, VPN interference.
3. **Phase 2** — native bridge app reaches parity with the Tasker executor;
   swap executors on Chris's phone, then deploy to Michele's phone.
4. **Phase 3** — RustDesk server LXC stood up (needs Chris's explicit go for
   the container + router forwards); family devices enrolled.
5. **Phase 4** — Peggy: her own Muse set up, phone provisioned in person
   (bridge, RustDesk, restricted-settings step, drills). Her Muse's
   controller runs in her Muse's own environment.

## 7. Open decisions

1. How Peggy's Muse publishes bridge commands — its environment needs the
   owner-channel ntfy token and a publish mechanism; credential storage
   follows the household's established agent-credential pattern (never on
   the phone, never typed by Peggy).
2. ~~Whether Chris's controller is granted access as a second whitelisted
   ID~~ — Resolved 2026-11-11 (§3.4): both paths coexist; Peggy's phone
   whitelists both controller IDs, revocable independently.
3. Session narration channel while control is active (voice conversation
   stays open vs text summaries after the fact).
4. Hosted-controller variant (§3.3): only if per-operator setup in Peggy's
   Muse's environment proves impractical. Default: per-operator.
