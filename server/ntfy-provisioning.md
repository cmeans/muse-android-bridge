# ntfy provisioning — pilot device `chris-razr`

Server: Chris's existing ntfy on CT 104 (Holodeck). Provisioned 2026-11-11
with Chris's go. No service restart was needed or performed — user/ACL
changes are live database operations.

## Principals and grants (per PROTOCOL.md §3)

| Principal | mab-chris-razr-owner | mab-chris-razr-family | mab-chris-razr-ack |
|---|---|---|---|
| `chris-razr-phone` (user, on-phone credential) | read-only | read-only | write-only |
| `muse` (Chris's Muse, existing token) | write-only | — | read-only |
| `chris` (admin) | full (admin role) | full | full |

Credential values live only in CT 104 `/root/ntfy-creds.txt` (0600) as
`RAZR_PHONE_USER` / `RAZR_PHONE_PASSWORD`. Muse's token is unchanged at
`~/.config/ntfy/token` (0600) — the ACL extension applies to the existing
token because grants attach to the user.

## Verification (2026-11-11, all as expected)

- muse token publishes `launch meet` to owner topic → stored (id `8z0ElmpAFVzP`)
- muse token reads owner topic → 403 (write-only holds)
- anonymous publish → 403
- phone user reads owner topic → receives the message
- phone user publishes to owner topic → 403
- phone user publishes receipt to ack → 200; reads ack → 403
- phone user reads family topic → 200; reads Chris's alert topic → 403 (no grant)
- muse token reads ack topic → receives `ok launch meet`

## Still to provision at later phases

- Michele as a family publisher (write family / read ack) when her phone
  or her helper access is set up.
- `mab-michele-*` and `mab-peggy-*` topic families at their phases.
- The phone-side subscription: on the pilot phone, the ntfy app (or the
  executor) subscribes with the `chris-razr-phone` user against
  https://ntfy.intfar.com — password handed over at setup time, never
  written here.

## F1 revocation drill (2026-11-11, PASSED)

Throwaway publisher `drill-pub` (write-only on `mab-chris-razr-family`,
own token), run entirely against the pilot topics:

1. Publish with drill token → **200**.
2. `ntfy access drill-pub mab-chris-razr-family deny` (the revocation move).
3. Publish again with the **same, still-valid token** → **403**. ACL denial
   is immediate and beats token validity — revoking a family member does
   not require chasing their token.
4. Cleanup: drill user removed entirely, token file deleted, user list
   verified clean; publish attempt afterwards → **401**.

Standing revocation procedure for a real family publisher: apply `deny`
on their bridge topics first (instant stop), then revoke their tokens /
remove the user at leisure. Test-plan F1 is satisfied for the ACL
mechanism; it will be re-run with the real family credentials during
Phase 4 provisioning.

## F2 phone-credential scope (2026-11-11, PASSED via the public path)

Re-ran the phone credential's scope checks through the public front door
(https://ntfy.intfar.com via the CT 105 proxy — the exact path the phone
will use), not just localhost: read owner topic → message received;
publish to owner → 403; publish ack receipt → 200; read ack → 403.
Combined with provisioning-day localhost checks (alert topic → 403) and
the F1 drill, test-plan F2 is satisfied. F3 (rogue controller refused)
awaits the Phase 3 RustDesk server.
