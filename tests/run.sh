#!/usr/bin/env bash
# Runs the workflow suites: the label and QA Gate workflow scripts against a fake gh.
# Ported from mcp-phone-commands (its #3), without its docs-checker suite. Needs bash, jq
# and yq (mikefarah's, as GitHub's ubuntu-latest has). CI runs this on ubuntu-latest so the
# workflow scripts are tested on the jq they run on there, whatever version that is (it's
# printed below): a bug in mcp-phone-commands (its #9) passed on a local jq 1.8 and broke on
# the runner's jq 1.7 of the time, which binds `as` differently.
set -u
cd "$(dirname "$0")/.."
echo "bash ${BASH_VERSION}; $(jq --version); $(yq --version 2>&1 | head -n 1)"
status=0
for suite in tests/workflows/on-label-cases.sh tests/workflows/qa-gate-cases.sh; do
  echo "== $suite"
  bash "$suite" || status=1
done
exit "$status"
