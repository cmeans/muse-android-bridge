#!/usr/bin/env bash
# qa-gate.yml: the QA Gate status from the labels (#2, #4, #6, #8) and from when the approvals
# came relative to the head commit (#7, #9). Run by tests/run.sh.
set -u
. "$(dirname "$0")/lib.sh"
S=$(extract qa-gate.yml qa-approved "Set QA status") || exit 2

A='"Muse Approved"' F='"Muse Failed"' Q='"QA Approved"'
OK="success: QA Approved and Muse Approved"
CHECK_FAILED="pending: QA Gate check failed; change a label to retry"

# approved: both approvals on, the head's first run at 09:05, approvals at 09:10 and 09:11.
approved() {
  new_case; live "[$Q,$A]"
  run_on "$(t 09:05:00)"
  event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
}

# case <name> <action> <want outcome>
case_() {
  go "$S" ACTION="$2"
  check "$1" "$3"
}

# --- The labels -------------------------------------------------------------------------------
approved
case_ "both approvals after the head: success" labeled "labels=[$Q,$A] status=$OK rc=0"

for action in opened synchronize reopened; do
  approved
  case_ "$action: always pending, whatever the labels" "$action" \
    "labels=[$Q,$A] status=pending: Waiting for: QA Approved (maintainer), Muse Approved rc=0"
done

approved; live "[$Q]"
case_ "QA Approved alone: waiting for Muse" labeled \
  "labels=[$Q] status=pending: Waiting for: Muse Approved rc=0"

approved; live "[$A]"
case_ "Muse Approved alone: waiting for the maintainer" unlabeled \
  "labels=[$A] status=pending: Waiting for: QA Approved (maintainer) rc=0"

approved; live "[$Q,$F]"
case_ "Muse Failed on: waiting for its removal" labeled \
  "labels=[$Q,$F] status=pending: Waiting for: Muse Approved, Muse Failed removed rc=0"

approved; live "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later "[$Q,$A]"
case_ "both Muse labels, then on-label removes Failed: success" labeled \
  "labels=[$Q,$A] status=$OK rc=0"
check "  ... after two 5 s waits" "2" "$(reads '^sleep')"

approved; live "[$Q,$A,$F]"; later "[$Q,$A,$F]"; later "[$Q,$F]"
case_ "both Muse labels, then on-label removes Approved: pending" labeled \
  "labels=[$Q,$F] status=pending: Waiting for: Muse Approved, Muse Failed removed rc=0"

approved; live "[$Q,$A,$F]"
case_ "both Muse labels for a minute: pending, says what to do" labeled \
  "labels=[$Q,$A,$F] status=pending: Both Muse labels were on for a minute; remove the one that doesn't apply, or change a label to re-check if one is gone rc=0"
check "  ... after twelve 5 s waits" "12" "$(reads '^sleep')"

approved; later "[$Q,$A]"; later "[$Q]"
case_ "Muse Approved removed right after the success post: pending wins" labeled \
  "labels=[$Q] status=pending: Waiting for: Muse Approved rc=0"

approved; pr_set '.head.sha = "def456"'
case_ "a newer head: this event's run leaves the status alone" labeled \
  "labels=[$Q,$A] status=none rc=0"

# --- Failed reads fail closed: pending, and the job fails ------------------------------------
approved; fail pulls
case_ "PR read fails: pending" labeled "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

approved; fail runs
case_ "runs read fails: pending" labeled "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

approved; fail events
case_ "events read fails: pending" labeled "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

approved; pr_set '.head.repo = null'
case_ "deleted fork (no head repository): pending" labeled \
  "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

approved; echo '[{"event":"labeled","created_at":"2026-11-14T09:10:00.5Z","label":{"name":"QA Approved"}},{"event":"labeled","created_at":"2026-11-14T09:11:00Z","label":{"name":"Muse Approved"}}]' > "$STATE/events.json"
case_ "a time format jq doesn't know: pending" labeled \
  "labels=[$Q,$A] status=$CHECK_FAILED rc=5"

approved; fail status.success
case_ "the success post fails: the trap posts pending, the job fails" labeled \
  "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

approved; touch "$STATE/html.pulls"
case_ "the PR read answers with an HTML page: pending, the job fails" labeled \
  "labels=[$Q,$A] status=$CHECK_FAILED rc=1"

# --- When the approvals came (#7) -------------------------------------------------------------
new_case; live "[$Q,$A]"; run_on "$(t 09:20:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "approvals before the head arrived: pending" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved, Muse Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:10:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "an approval in the head's own second counts as before" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:05:00)"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "an approval with no labeled event: pending" labeled \
  "labels=[$Q,$A] status=pending: No record of when QA Approved was added; remove and re-add rc=0"

new_case; live "[$Q,$A]"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "no run dates the head: pending" labeled \
  "labels=[$Q,$A] status=pending: QA Gate couldn't tell when this commit arrived; change a label to retry rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:05:00)"; run_on "$(t 09:40:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
event head_ref_force_pushed "$(t 09:30:00)"
case_ "force push away and back: the head dates from after it" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved, Muse Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:05:00)"; run_on "$(t 09:40:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
event reopened "$(t 09:30:00)"
case_ "reopened: the head dates from after the reopen" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved, Muse Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 08:00:00)"; run_on "$(t 09:20:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "an earlier PR on the same branch name doesn't date the head" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved, Muse Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:02:00)" feat fork/r; run_on "$(t 09:20:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "a fork's run on a same-named branch doesn't date the head" labeled \
  "labels=[$Q,$A] status=pending: Approved before this commit; remove and re-add: QA Approved, Muse Approved rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:00:00)"
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "a run in the PR's opening second doesn't date the head" labeled \
  "labels=[$Q,$A] status=pending: QA Gate couldn't tell when this commit arrived; change a label to retry rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:05:00)" other-branch
event labeled "$(t 09:10:00)" "QA Approved"; event labeled "$(t 09:11:00)" "Muse Approved"
case_ "a run on another branch doesn't date the head" labeled \
  "labels=[$Q,$A] status=pending: QA Gate couldn't tell when this commit arrived; change a label to retry rc=0"

new_case; live "[$Q,$A]"; run_on "$(t 09:05:00)"
event labeled "$(t 08:50:00)" "QA Approved"; event labeled "$(t 09:10:00)" "QA Approved"
event labeled "$(t 09:11:00)" "Muse Approved"
case_ "an approval re-added after the head counts from its latest add" labeled \
  "labels=[$Q,$A] status=$OK rc=0"

finish "qa-gate"
