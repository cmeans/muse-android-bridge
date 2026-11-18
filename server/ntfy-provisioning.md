# ntfy provisioning — pilot device `pilot-phone`

> **Protocol 0.5 (2026-11-16):** the family channel is now one topic per
> member, and the shared `mab-pilot-phone-family` topic is retired. The
> sections below up to "Still to provision" record the 0.4 provisioning as it
> was done; "Family members (protocol 0.5)" says what to do now.

> A record of the reference deployment's provisioning, written as a worked
> example. Device names, users, hosts, file locations and credential
> variable names are generic examples (the live server uses its own).
> Substitute your own throughout.

Server: the server operator's existing ntfy, in a container on their home server.
Provisioned 2026-11-11 with the maintainer's go. No service restart was
needed or performed — user/ACL changes are live database operations.

## Principals and grants (per PROTOCOL.md §3, as of 0.4)

| Principal | mab-pilot-phone-owner | mab-pilot-phone-family | mab-pilot-phone-ack |
|---|---|---|---|
| `pilot-phone-phone` (user, on-phone credential; `<device>-phone` per PROTOCOL §3) | read-only | read-only | write-only |
| `owner-pub` (the owner channel's publisher: the owner's agent; a dedicated user, see below) | write-only | — | read-only |
| `admin` (admin) | full (admin role) | full | full |

Credential values live only in a root-only file on the server (0600) as
`PILOT_PHONE_USER` / `PILOT_PHONE_PASSWORD`.

**Recommended, and what the reference deployment does now:** give the owner
channel's publisher a dedicated ntfy user (`owner-pub` above) with only these
grants, and its own token in a 0600 file on its host. Revoking that token then
cuts off the owner channel and nothing else. Make it a plain user, not an ntfy
admin: the admin role ignores grants, so an admin publisher could read the
owner topic (the second check below would catch it).

One `owner-pub` can serve several phones: it then holds these two grants on
each phone's owner and ack topics and no others, and revoking it cuts off the
owner channel on all of them.

How the reference deployment got there:
- **2026-11-11:** the publisher was an existing ntfy user that held other
  grants too, with the grants above added to it.
- **2026-11-14:** after an audit, each phone's owner channel got its own
  dedicated publisher user.
- **2026-11-17:** those were replaced by one dedicated, non-admin `owner-pub`
  for every phone. The per-phone publisher users now hold no grants, and the
  existing user lost all its bridge entries (its ack-topic reads and explicit
  no-access entries on the owner topics); it holds only grants unrelated to
  the bridge. No wildcard grant was used at
  any step.

## Verification (2026-11-11, all as expected)

- publisher token publishes `launch meet` to owner topic → stored
- publisher token reads owner topic → 403 (write-only holds)
- anonymous publish → 403
- phone user reads owner topic → receives the message
- phone user publishes to owner topic → 403
- phone user publishes receipt to ack → 200; reads ack → 403
- phone user reads family topic → 200; reads a topic it has no grant on → 403
- publisher token reads ack topic → receives `ok launch meet`

Re-checked on 2026-11-17 with the dedicated `owner-pub` token, on every
phone's topics: publish to the owner topic → 200, stored; read the owner
topic → 403; read the ack topic → works. The replaced per-phone publisher
tokens now get 403 on publish.

## Family members (protocol 0.5)

For each phone and each family member, in this order. One topic the phone's
user can't read makes ntfy refuse the whole subscription (`403`), which would
cut the owner channel off too; the bridge's per-topic fallback works around
that, but the order below keeps it from happening. Example: device
`pilot-phone`, member slug `m1` (a neutral id, not a name; see PROTOCOL §2.1),
ntfy user `fam-m1`. Every grant names the exact topic, never a wildcard:
`mab-pilot-phone-family-m1*` would also cover `…-family-m10`.

1. **Grant first.**
   - Create the person's ntfy user if it doesn't exist (one user per person,
     shared across the phones they help).
   - `ntfy access fam-m1 mab-pilot-phone-family-m1 write-only`
   - `ntfy access fam-m1 mab-pilot-phone-ack read-only`
   - `ntfy access pilot-phone-phone mab-pilot-phone-family-m1 read-only`
2. **Then add the member** in the phone's setup (slug `m1` and a display
   name; the name stays on the phone). Adding a member to a phone that's
   already set up goes through the owner's confirm step.
3. **Check:** `fam-m1` publishes `launch meet` to its topic → 200 and the
   phone announces it with the member's name; `fam-m1` publishing to
   `mab-pilot-phone-owner` or another member's topic → 403; the phone user
   reads the new topic → 200.

**Removing a member: phone first.** Remove the member in the phone's setup,
then revoke the grants (`ntfy access fam-m1 mab-pilot-phone-family-m1 deny`,
or `ntfy access --reset fam-m1 mab-pilot-phone-family-m1`, and the phone
user's read with `ntfy access --reset <phone-user> <member-topic>`).
Revoking the phone's read first would leave that member shown
on the phone as unreachable until it's removed.

**Retiring the shared topic (once per phone, not per member; D2).** Phone
first: move the phone to the 0.5 build, which never reads
`mab-pilot-phone-family` (so the family channel is silent on the phone until
a member is added). Then grant and add each family user's member topic (the
steps above). Only after both, revoke every family user's grants on the
shared topic, and the phone user's read on it
(`ntfy access --reset <user> mab-pilot-phone-family`). Revoking before the
phone has the new build would make the phone's subscription fail with `403`,
and revoking before a family user's member topic is granted and added would
cut that person off. Afterwards a message sent to
the shared topic reaches nobody and gets no receipt.

Then re-run the F1 drill below per member, on that member's topic.

## Still to provision at later phases

- A family helper as a family member (steps above) when their phone or
  their helper access is set up.
- `mab-helper-pixel-*` and `mab-alex-phone-*` topic families at their
  phases.
- The phone-side subscription: on the pilot phone, the ntfy app (or the
  executor) subscribes with the `pilot-phone-phone` user against
  https://ntfy.example.com — password handed over at setup time, never
  written here.

## F1 revocation drill (2026-11-11, PASSED; per member under protocol 0.5)

Throwaway publisher `drill-pub` (write-only on `mab-pilot-phone-family`,
own token), run entirely against the pilot topics. The drill was run on the
shared topic. Under 0.5, run it per member with the member's topic in place
of the shared one (`mab-pilot-phone-family-m1`), and check that the other
members' topics and the owner channel still work after the `deny`:

1. Publish with drill token → **200**.
2. `ntfy access drill-pub mab-pilot-phone-family deny` (the revocation move;
   under 0.5, on the member's topic).
3. Publish again with the **same, still-valid token** → **403**. ACL denial
   is immediate and beats token validity — revoking a family member does
   not require chasing their token.
4. Cleanup: drill user removed entirely, token file deleted, user list
   verified clean; publish attempt afterwards → **401**.

Standing revocation procedure for a real family member: apply `deny`
on their member topic (and the ack topic) first (instant stop), then revoke their tokens /
remove the user at leisure. Test-plan F1 is satisfied for the ACL
mechanism; it will be re-run with the real family credentials during
Phase 4 provisioning.

## F2 phone-credential scope (2026-11-11, PASSED via the public path)

Re-ran the phone credential's scope checks through the public front door
(https://ntfy.example.com via the server's reverse proxy — the exact path the phone
will use), not just localhost: read owner topic → message received;
publish to owner → 403; publish ack receipt → 200; read ack → 403.
Combined with provisioning-day localhost checks (an ungranted topic → 403) and
the F1 drill, test-plan F2 is satisfied. F3 (rogue controller refused)
awaits the Phase 3 RustDesk server.
