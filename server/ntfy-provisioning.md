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
