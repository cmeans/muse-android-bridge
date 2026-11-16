# Shared helpers for the workflow tests (#3). Sourced by the *-cases.sh files, which run under
# bash with `set -u`. Each case builds a fresh $STATE for bin/gh, runs a workflow's run script
# extracted with yq, and compares what happened (labels, status posts, exit code) with what it
# should be.

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BIN="$ROOT/tests/workflows/bin"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PASS=0
FAIL=0

# extract <workflow file> <job> <step name>: that step's run script, written to $WORK. It runs in
# "$(...)", so a caller must stop on its failure: S=$(extract ...) || exit 2.
extract() {
  local out="$WORK/$2.sh"
  yq -r ".jobs.\"$2\".steps[] | select(.name == \"$3\") | .run" "$ROOT/.github/workflows/$1" \
    > "$out"
  if [ ! -s "$out" ] || [ "$(cat "$out")" = null ]; then
    echo "extract: no step \"$3\" with a run script in job $2 of $1" >&2
    return 1
  fi
  echo "$out"
}

# new_case: a fresh state directory, with a PR whose head is abc123 on o/r:feat, opened at 09:00.
new_case() {
  STATE=$(mktemp -d -p "$WORK")
  export STATE
  jq -n '{head: {sha: "abc123", ref: "feat", repo: {full_name: "o/r"}},
          created_at: "2026-11-14T09:00:00Z"}' > "$STATE/pr.json"
  echo '[]' > "$STATE/labels.json"
  echo '[]' > "$STATE/events.json"
  echo '{"workflow_runs": []}' > "$STATE/runs.json"
  : > "$STATE/calls"
  : > "$STATE/statuses"
  : > "$STATE/edits"
}

# t <hh:mm:ss>: a timestamp on 2026-11-14.
t() { echo "2026-11-14T$1Z"; }

live() { echo "$1" > "$STATE/labels.json"; }          # live <json array>
later() { echo "$1" >> "$STATE/labels.queue"; }       # later <json array>: the next read's labels
fail() { touch "$STATE/fail.$1"; }                    # fail <pulls|labels|events|runs|edit|status>
pr_set() {                                            # pr_set <jq filter>: change pr.json
  jq -c "$1" "$STATE/pr.json" > "$STATE/pr.next" && mv "$STATE/pr.next" "$STATE/pr.json"
}

# event <labeled|unlabeled|reopened|head_ref_force_pushed> <time> [label]
event() {
  jq -c --arg e "$1" --arg at "$2" --arg l "${3:-}" \
    '. + [{event: $e, created_at: $at} + (if $l == "" then {} else {label: {name: $l}} end)]' \
    "$STATE/events.json" > "$STATE/events.next"
  mv "$STATE/events.next" "$STATE/events.json"
}

# run_on <time> [branch] [repo]: a pull_request workflow run for abc123.
run_on() {
  jq -c --arg at "$1" --arg b "${2:-feat}" --arg r "${3:-o/r}" \
    '.workflow_runs += [{head_branch: $b, head_repository: {full_name: $r}, created_at: $at}]' \
    "$STATE/runs.json" > "$STATE/runs.next"
  mv "$STATE/runs.next" "$STATE/runs.json"
}

# go <script> [VAR=value ...]: runs the script with the fake gh and sleep first on PATH, and the
# variables the workflow sets (defaults below; arguments override them). Sets RC and OUT.
go() {
  local script=$1
  shift
  RC=0
  OUT=$(env PATH="$BIN:$PATH" GH_TOKEN=fake REPO=o/r PR=1 EVENT_SHA=abc123 "$@" \
    bash "$script" 2>&1) || RC=$?
}

# What a case ends with, as one line: the live labels, the last status posted (or none), the
# exit code.
outcome() {
  local last
  last=$(tail -n 1 "$STATE/statuses")
  echo "labels=$(jq -c . "$STATE/labels.json") status=${last:-none} rc=$RC"
}

reads() { grep -c "$1" "$STATE/calls" || true; }      # reads <pattern>: calls matching it

# check <name> <want> [got]: got defaults to outcome.
check() {
  local got=${3:-$(outcome)}
  if [ "$got" = "$2" ]; then
    PASS=$((PASS + 1))
    echo "ok   $1"
  else
    FAIL=$((FAIL + 1))
    echo "FAIL $1"
    echo "     want: $2"
    echo "     got:  $got"
    sed 's/^/     | /' <<< "$OUT"
  fi
}

finish() {
  echo "$1: $PASS passed, $FAIL failed"
  [ "$FAIL" -eq 0 ]
}
