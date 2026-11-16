#!/usr/bin/env bash
# Ported from mcp-phone-commands (its main at 524646e): a bare #N in this file is
# mcp-phone-commands #N, not this repo's.
# on-label (pr-labels.yml) with the two Muse verdicts (#4, #8, PR #11): which verdict wins, how
# it fails closed, and what the job reports. Run by tests/run.sh.
set -u
. "$(dirname "$0")/lib.sh"
S=$(extract pr-labels.yml on-label "Clean up stale labels") || exit 2

A='"Muse Approved"' F='"Muse Failed"' Q='"QA Approved"'
NO_A="pending: Waiting for: Muse Approved, Muse Failed removed"
NO_QA_A="pending: Waiting for: QA Approved (maintainer), Muse Approved, Muse Failed removed"

# case <name> <added label> <event's labels> <want outcome> <want events reads> [want edits]
# The edits are the label changes made, joined with "; " (none by default).
case_() {
  go "$S" ADDED_LABEL="$2" EVENT_LABELS="$3"
  check "$1" "$4 reads=$5 edits=${6:-}" \
    "$(outcome) reads=$(reads '/events') edits=$(paste -sd ';' "$STATE/edits" | sed 's/;/; /g')"
}

# --- The Muse Approved run --------------------------------------------------------------------
new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:11)" "Muse Approved"
case_ "approved: Failed 11 s earlier is removed" "Muse Approved" "[$F,$A]" \
  "labels=[$A] status=none rc=0" 1 "remove Muse Failed"

new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:10)" "Muse Approved"
case_ "approved: 10 s apart counts as together" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=0" 1

new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:00)" "Muse Approved"
case_ "approved: same second, both stay" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=0" 1

new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:01)" "Muse Approved"
case_ "approved: one call straddling a second, both stay" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=0" 1

new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:30)" "Muse Failed"
case_ "approved: Failed is newer, both stay" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=0" 1

new_case; live "[$F,$A]"; fail events
case_ "approved: events read fails, Failed stays, job fails" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=1" 1

new_case; live "[$F,$A]"
event labeled "$(t 10:00:00)" "Muse Failed"
case_ "approved: own labeled event missing, re-read twice, fails closed" "Muse Approved" \
  "[$F,$A]" "labels=[$F,$A] status=none rc=1" 3

new_case; live "[$F,$A]"
event labeled "2026-11-14T10:00:00.5Z" "Muse Failed"; event labeled "$(t 10:00:30)" "Muse Approved"
case_ "approved: a time that doesn't parse is final, not re-read" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=1" 1

new_case; live "[$A]"; fail events
case_ "approved: Failed not in the event's list, no events read" "Muse Approved" "[$A]" \
  "labels=[$A] status=none rc=0" 0

new_case; live "[$A]"; fail events
case_ "approved: Failed already off, no events read" "Muse Approved" "[$F,$A]" \
  "labels=[$A] status=none rc=0" 0

new_case; live "[$Q,$F,$A]"
event labeled "$(t 09:00:00)" "Muse Failed"; event unlabeled "$(t 09:30:00)" "Muse Failed"
event labeled "$(t 10:00:00)" "Muse Approved"
case_ "approved: Failed's re-add not in the events yet, fails closed" "Muse Approved" "[$F,$A]" \
  "labels=[$Q,$F,$A] status=none rc=1" 3

new_case; live "[$F,$A]"
event labeled "$(t 09:00:00)" "Muse Failed"; event unlabeled "$(t 09:30:00)" "Muse Failed"
event labeled "$(t 09:40:00)" "Muse Failed"; event labeled "$(t 10:00:00)" "Muse Approved"
case_ "approved: Failed re-added long before, removed" "Muse Approved" "[$F,$A]" \
  "labels=[$A] status=none rc=0" 1 "remove Muse Failed"

new_case; live "[$F,$A]"
event labeled "$(t 09:00:00)" "Muse Failed"; event unlabeled "$(t 09:40:00)" "Muse Failed"
event labeled "$(t 09:40:00)" "Muse Failed"; event labeled "$(t 10:00:00)" "Muse Approved"
case_ "approved: removal and re-add in one second count as on" "Muse Approved" "[$F,$A]" \
  "labels=[$A] status=none rc=0" 1 "remove Muse Failed"

new_case; live "[$F,$A]"
event labeled "$(t 09:00:00)" "Muse Failed"; event labeled "$(t 09:10:00)" "Muse Approved"
event unlabeled "$(t 09:20:00)" "Muse Approved"
case_ "approved: own re-add not in the events yet, fails closed" "Muse Approved" "[$F,$A]" \
  "labels=[$F,$A] status=none rc=1" 3

new_case; live "[$Q,$F,$A]"; later "[$Q,$F,$A]"; later "[$Q,$F]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:01:40)" "Muse Approved"
event unlabeled "$(t 10:01:43)" "Muse Approved"
case_ "approved: own label taken off between reads, nothing done" "Muse Approved" "[$F,$A]" \
  "labels=[$Q,$F] status=none rc=0" 3

new_case; live "[$Q,$F,$A]"; later "[$Q,$F,$A]"; later "[$Q,$F]"
event labeled "$(t 11:00:00)" "Muse Failed"; event labeled "$(t 12:00:00)" "Muse Approved"
case_ "approved: own label off by the re-read, Failed not removed" "Muse Approved" "[$F,$A]" \
  "labels=[$Q,$F] status=none rc=0" 1

new_case; live "[$F,$A]"; later "[$F,$A]"; later "[$A]"
event labeled "$(t 11:00:00)" "Muse Failed"; event labeled "$(t 12:00:00)" "Muse Approved"
case_ "approved: Failed off by the re-read, nothing removed" "Muse Approved" "[$F,$A]" \
  "labels=[$A] status=none rc=0" 1

new_case; live "[$F,$A]"; later "[$F,$A]"; later FAIL
event labeled "$(t 11:00:00)" "Muse Failed"; event labeled "$(t 12:00:00)" "Muse Approved"
case_ "approved: the re-read fails, order unknown, Failed stays, job fails" "Muse Approved" \
  "[$F,$A]" "labels=[$F,$A] status=none rc=1" 1

# --- The Muse Failed run ----------------------------------------------------------------------
new_case; live "[$Q,$A,$F]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:11)" "Muse Failed"
case_ "failed: Approved 11 s earlier is removed, gate pending" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$F] status=$NO_A rc=0" 1 "remove Muse Approved"

new_case; live "[$Q,$A,$F]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:00)" "Muse Failed"
case_ "failed: same second, both stay" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$A,$F] status=none rc=0" 1

new_case; live "[$Q,$A,$F]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:01)" "Muse Approved"
case_ "failed: straddle, Failed first, both stay" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$A,$F] status=none rc=0" 1

new_case; live "[$Q,$A,$F]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:01)" "Muse Failed"
case_ "failed: straddle, Approved first, both stay" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$A,$F] status=none rc=0" 1

new_case; live "[$A,$F]"
event labeled "$(t 10:00:00)" "Muse Failed"; event labeled "$(t 10:00:30)" "Muse Approved"
case_ "failed: Approved is newer, both stay" "Muse Failed" "[$A,$F]" \
  "labels=[$A,$F] status=none rc=0" 1

new_case; live "[$A,$F]"; fail events
case_ "failed: events read fails, Approved removed, specific pending, job fails" "Muse Failed" \
  "[$A,$F]" "labels=[$F] status=$NO_QA_A rc=1" 1 "remove Muse Approved"

new_case; live "[$F]"; fail events
case_ "failed: Approved already off, no events read" "Muse Failed" "[$A,$F]" \
  "labels=[$F] status=none rc=0" 0

new_case; live "[$A,$F]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:00)" "Muse Failed"
event labeled "$(t 10:00:20)" "Muse Failed"
case_ "failed: Failed re-added 20 s later, Approved removed" "Muse Failed" "[$A,$F]" \
  "labels=[$F] status=$NO_QA_A rc=0" 1 "remove Muse Approved"

new_case; live "[$Q,$A,$F]"
event labeled "$(t 09:00:00)" "Muse Approved"; event unlabeled "$(t 09:30:00)" "Muse Approved"
event labeled "$(t 10:00:00)" "Muse Failed"
case_ "failed: Approved's re-add not in the events yet, Approved removed, job fails" \
  "Muse Failed" "[$A,$F]" "labels=[$Q,$F] status=$NO_A rc=1" 3 "remove Muse Approved"

new_case; live "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later "[$Q,$A]"
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:01:40)" "Muse Failed"
event unlabeled "$(t 10:01:43)" "Muse Failed"
case_ "failed: own label taken off between reads, nothing done" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$A] status=none rc=0" 3

new_case; live "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later "[$Q,$A]"
event labeled "$(t 11:00:00)" "Muse Approved"; event labeled "$(t 12:00:00)" "Muse Failed"
case_ "failed: own label off by the re-read, Approved not removed" "Muse Failed" "[$A,$F]" \
  "labels=[$Q,$A] status=none rc=0" 1

new_case; live "[$A,$F]"; fail edit
event labeled "$(t 10:00:00)" "Muse Approved"; event labeled "$(t 10:00:11)" "Muse Failed"
case_ "failed: the removal fails, the trap posts the generic pending" "Muse Failed" "[$A,$F]" \
  "labels=[$A,$F] status=pending: QA Gate check failed; change a label to retry rc=1" 1

new_case; live "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later FAIL
event labeled "$(t 11:00:00)" "Muse Approved"; event labeled "$(t 11:00:00)" "Muse Failed"
case_ "failed: the re-read fails, order unknown, Approved removed, job fails" "Muse Failed" \
  "[$A,$F]" "labels=[$Q,$F] status=$NO_A rc=1" 1 "remove Muse Approved"

# --- Not a Muse label: no events read ---------------------------------------------------------
new_case; live '["Ready for QA Signoff","QA Approved"]'
case_ "QA Approved removes Ready for QA Signoff" "QA Approved" '["Ready for QA Signoff","QA Approved"]' \
  "labels=[$Q] status=none rc=0" 0 "remove Ready for QA Signoff"

new_case; live '["Dev Active"]'
case_ "added label already gone, nothing to clean up" "QA Active" '["QA Active"]' \
  'labels=["Dev Active"] status=none rc=0' 0

finish "on-label"
