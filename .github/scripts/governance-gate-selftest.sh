#!/usr/bin/env bash
# Proves the governance gate (governance-gate.sh) fails and passes, on a throwaway repository: red for a new vector
# version without approval, red for an edited released version, green once approved, green when nothing changed.
set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/.github/scripts" "$T/tolerance" "$T/vectors/boundary/1.0.0" "$T/governance"
cp "$SCRIPTS/governance-gate.sh" "$T/.github/scripts/"
echo '{ "version": "1.0.0", "rows": [] }' > "$T/tolerance/tolerance-profile.json"
echo '{"case": 1}' > "$T/vectors/boundary/1.0.0/cases.json"
cd "$T"
git init -q . && git config user.email t@t && git config user.name t && git add -A && git commit -q -m base
GATE=".github/scripts/governance-gate.sh"
expect() { # expect <pass|fail> <description> <args...>
  local want="$1" what="$2"; shift 2
  if "$GATE" "$@" >/dev/null 2>&1; then got=pass; else got=fail; fi
  [ "$got" = "$want" ] || { echo "SELFTEST FAIL: $what (wanted $want, got $got)"; exit 1; }
  echo "ok: $what"
}

expect pass "nothing changed"  HEAD
echo '{"case": 2}' > vectors/boundary/1.0.0/cases.json
expect fail "released vector version edited"  HEAD
git checkout -q vectors
mkdir vectors/boundary/1.1.0 && echo '{"case": 3}' > vectors/boundary/1.1.0/cases.json
expect fail "new vector version without approval"  HEAD
line="$("$GATE" --digests | awk '$1=="vectors-boundary" && $2=="1.1.0"')"
echo "$line qaAgent 2026-10-03 AT-16" > governance/qa-approvals.txt
expect pass "new vector version with approval"  HEAD
echo "$line qaAgent yesterday AT-16" > governance/qa-approvals.txt
expect fail "approval line with a bad date"  HEAD
echo "$line qaAgent 2026-10-03 AT-16" | sed 's/1.1.0 [0-9a-f]*/1.1.0 0000/' > governance/qa-approvals.txt
expect fail "approval line for a different digest"  HEAD
echo '{ "version": "1.1.0", "rows": [] }' > tolerance/tolerance-profile.json
expect fail "profile change without approval"  HEAD
echo "governance gate selftest: PASS"
