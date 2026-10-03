#!/usr/bin/env bash
# Proves the governance gate (governance-gate.sh), the baseline resolver and the trusted runner on a throwaway repository:
# red for every kind of unapproved change (new or edited vector or profile versions, the spec, the smoke cases, the schemas,
# new vector sets and stray files, the enum approval files), red for a deleted released version, a bad approval, an edited
# approvals file and a profile folder with the wrong version, green once approved or retired, green for README edits, and
# digests that ignore untracked files and see symlinks. Also: a tag build resolves its baseline, a stale branch is not blamed
# for what main did, and a change that weakens the gate script still fails because the baseline's copy runs.
set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fresh() { # a throwaway repository with one commit on main
  rm -rf "$T/r" && mkdir -p "$T/r" && cd "$T/r"
  mkdir -p .github/scripts tolerance/1.0.0 vectors/boundary/1.0.0 vectors/negative/1.0.0 json-schema/v1 avro governance
  cp "$SCRIPTS/governance-gate.sh" "$SCRIPTS/resolve-baseline.sh" "$SCRIPTS/run-trusted-gate.sh" .github/scripts/
  echo '{ "version": "1.0.0", "rows": [] }' > tolerance/1.0.0/tolerance-profile.json
  echo '{"case": 1}' > vectors/boundary/1.0.0/cases.json
  echo '{"case": 2}' > vectors/negative/1.0.0/cases.json
  echo spec > algorithm-spec.md
  echo '{"cases": []}' > tolerance/smoke-cases.json
  echo '{}' > tolerance/tolerance-profile.schema.json
  echo '{}' > json-schema/v1/live-measurement.schema.json
  echo '# approvals' > json-schema/.enum-approvals
  echo '# approvals' > avro/.enum-approvals
  echo '{}' > vectors/manifest.json
  echo readme > vectors/README.md
  echo '# qa approvals' > governance/qa-approvals.txt
  git init -q -b main . && git config user.email t@t && git config user.name t && git add -A && git commit -q -m base
  git update-ref refs/remotes/origin/main HEAD
}
GATE=".github/scripts/governance-gate.sh"
expect() { # expect <pass|fail> <description> <gate args...>
  local want="$1" what="$2"; shift 2
  if "$GATE" "$@" >/dev/null 2>&1; then got=pass; else got=fail; fi
  [ "$got" = "$want" ] || { echo "SELFTEST FAIL: $what (wanted $want, got $got)"; "$GATE" "$@" || true; exit 1; }
  echo "ok: $what"
}
digest() { "$GATE" --digests | awk -v a="$1" -v v="$2" '$1==a && $2==v {print $3}'; }
approve() { echo "$1 $2 $(digest "$1" "$2") qaAgent 2026-10-03 AT-16" >> governance/qa-approvals.txt; }

fresh
expect pass "nothing changed" HEAD
echo '{"case": 9}' > vectors/boundary/1.0.0/cases.json
expect fail "released vector version edited" HEAD
approve vectors-boundary 1.0.0
expect fail "released vector version edited, even with an approval line" HEAD
git checkout -q . && git clean -fdq

fresh
mkdir vectors/boundary/1.1.0 && echo '{"case": 3}' > vectors/boundary/1.1.0/cases.json && git add -A
expect fail "new vector version without approval" HEAD
approve vectors-boundary 1.1.0
expect pass "new vector version with approval" HEAD
sed -i.bak 's/2026-10-03/yesterday/' governance/qa-approvals.txt
expect fail "approval line with a bad date" HEAD
git checkout -q governance && approve vectors-boundary 1.1.0 && sed -i.bak 's/1.1.0 [0-9a-f]*/1.1.0 0000/' governance/qa-approvals.txt
expect fail "approval line for a different digest" HEAD
rm -f governance/qa-approvals.txt.bak

fresh
mkdir tolerance/1.1.0 && echo '{ "version": "1.1.0", "rows": [] }' > tolerance/1.1.0/tolerance-profile.json && git add -A
expect fail "a profile bump (new profile version) without approval" HEAD
approve tolerance-profile 1.1.0
expect pass "a profile bump with approval" HEAD
echo '{ "version": "1.0.0", "rows": [] }' > tolerance/1.1.0/tolerance-profile.json
approve tolerance-profile 1.1.0
expect fail "a profile folder holding another version than its name" HEAD
git checkout -q . && git clean -fdq
echo '{ "version": "1.0.1", "rows": [] }' > tolerance/1.0.0/tolerance-profile.json
expect fail "the released profile edited in place (version label bumped without a new folder)" HEAD

fresh
git rm -rq vectors/boundary/1.0.0
expect fail "a released vector directory deleted" HEAD
echo "vectors-boundary 1.0.0 RETIRE qaAgent 2026-10-03 AT-16" >> governance/qa-approvals.txt
expect pass "a released vector directory retired with an approval" HEAD
fresh
git rm -rq tolerance/1.0.0
expect fail "the released profile deleted" HEAD
fresh
git rm -rq tolerance/1.0.0 && mkdir tolerance/1.1.0 && echo '{ "version": "1.1.0", "rows": [] }' > tolerance/1.1.0/tolerance-profile.json
approve tolerance-profile 1.1.0
expect fail "a profile bumped to 1.1.0 while 1.0.0 silently disappears" HEAD

# every other governed path needs an approval when it changes
for path in algorithm-spec.md tolerance/smoke-cases.json tolerance/tolerance-profile.schema.json json-schema/v1/live-measurement.schema.json \
            json-schema/.enum-approvals avro/.enum-approvals vectors/manifest.json; do
  fresh
  echo "changed" >> "$path"
  expect fail "a change to $path without approval" HEAD
  approve file "$path"
  expect pass "a change to $path with approval" HEAD
done
fresh
echo "stray" > vectors/boundary/stray.json && git add -A
expect fail "a stray file under vectors/<set>/ without approval" HEAD
fresh
mkdir -p vectors/extra/1.0.0 && echo '{}' > vectors/extra/1.0.0/a.json && git add -A
expect fail "a new vector set without approval" HEAD
approve vectors-extra 1.0.0
expect pass "a new vector set with approval" HEAD
fresh
echo "doc change" >> vectors/README.md
echo "doc change" >> README.md 2>/dev/null || true
expect pass "README edits are not governed" HEAD

# digests: git ls-files, not find
fresh
before="$(digest vectors-boundary 1.0.0)"
echo junk > vectors/boundary/1.0.0/.DS_Store
echo junk > vectors/boundary/1.0.0/untracked.json
[ "$(digest vectors-boundary 1.0.0)" = "$before" ] || { echo "SELFTEST FAIL: an untracked file changed the digest"; exit 1; }
echo "ok: untracked files do not change the digest"
ln -s cases.json vectors/boundary/1.0.0/link.json && git add vectors/boundary/1.0.0/link.json
[ "$(digest vectors-boundary 1.0.0)" != "$before" ] || { echo "SELFTEST FAIL: a tracked symlink did not change the digest"; exit 1; }
echo "ok: a tracked symlink changes the digest"
fresh
before="$(digest vectors-boundary 1.0.0)"
chmod +x vectors/boundary/1.0.0/cases.json && git add -A
[ "$(digest vectors-boundary 1.0.0)" != "$before" ] || { echo "SELFTEST FAIL: the executable bit did not change the digest"; exit 1; }
echo "ok: the executable bit changes the digest"

# the approvals file is append-only and its override is ignored in CI
fresh
mkdir vectors/boundary/1.1.0 && echo '{"case": 3}' > vectors/boundary/1.1.0/cases.json && git add -A
approve vectors-boundary 1.1.0
git add -A && git commit -q -m "release 1.1.0"
git update-ref refs/remotes/origin/main HEAD
mkdir vectors/boundary/1.2.0 && echo '{"case": 4}' > vectors/boundary/1.2.0/cases.json && git add -A
approve vectors-boundary 1.2.0
expect pass "a second approval appended" origin/main
grep -v "vectors-boundary 1.1.0" governance/qa-approvals.txt > governance/qa-approvals.new && mv governance/qa-approvals.new governance/qa-approvals.txt
expect fail "an approval line of the baseline removed" origin/main
git checkout -q governance && approve vectors-boundary 1.2.0
cp governance/qa-approvals.txt "$T/elsewhere.txt"
git checkout -q governance
if GITHUB_ACTIONS=true GOVERNANCE_APPROVALS="$T/elsewhere.txt" "$GATE" origin/main >/dev/null 2>&1; then
  echo "SELFTEST FAIL: GOVERNANCE_APPROVALS was honoured in CI"; exit 1
fi
echo "ok: GOVERNANCE_APPROVALS is ignored in CI"
GOVERNANCE_APPROVALS="$T/elsewhere.txt" "$GATE" origin/main >/dev/null 2>&1 || { echo "SELFTEST FAIL: GOVERNANCE_APPROVALS not honoured locally"; exit 1; }
echo "ok: GOVERNANCE_APPROVALS works outside CI"

# the baseline of a tag build, and a stale branch
fresh
git tag v1.0.0
echo '{"case": 5}' > vectors/boundary/1.0.0/cases.json && git commit -qam "edit after the tag"
git tag v1.1.0
[ "$(.github/scripts/resolve-baseline.sh 0000000000000000000000000000000000000000)" = "$(git rev-parse v1.0.0)" ] \
  || { echo "SELFTEST FAIL: a tag build must resolve to the previous release tag"; exit 1; }
echo "ok: a tag build resolves to the previous release tag"
expect fail "a tag build still detects the edit of a released version (baseline = previous tag)" 0000000000000000000000000000000000000000
fresh
[ "$(.github/scripts/resolve-baseline.sh 0000000000000000000000000000000000000000)" = "$(git rev-parse HEAD)" ] \
  || { echo "SELFTEST FAIL: with no release tag the baseline is origin/main"; exit 1; }
echo "ok: with no release tag the baseline is origin/main"
[ -z "$(.github/scripts/resolve-baseline.sh refs/heads/does-not-exist)" ] || { echo "SELFTEST FAIL: an unknown ref must resolve to nothing"; exit 1; }
echo "ok: an unknown baseline resolves to nothing (callers fail closed)"
git checkout -q -b stale
git checkout -q main && mkdir vectors/boundary/1.3.0 && echo '{}' > vectors/boundary/1.3.0/c.json && git add -A && git commit -q -m "main moves on"
git update-ref refs/remotes/origin/main HEAD
git checkout -q stale
expect pass "a stale PR branch is not blamed for what main changed since it branched" origin/main

# the trusted runner executes the baseline's copy of the gate
fresh
git checkout -q -b weaken
printf '#!/usr/bin/env bash\necho "weakened gate"\nexit 0\n' > .github/scripts/governance-gate.sh
echo '{"case": 9}' > vectors/boundary/1.0.0/cases.json && git add -A && git commit -q -m "weaken the gate and edit a released vector"
if .github/scripts/run-trusted-gate.sh governance-gate.sh origin/main >/dev/null 2>&1; then
  echo "SELFTEST FAIL: a change that weakens the gate script got through the trusted runner"; exit 1
fi
echo "ok: a PR that weakens governance-gate.sh is still judged by the baseline's copy"
if ! "$GATE" origin/main >/dev/null 2>&1; then :; else
  echo "ok: (control) the weakened copy run directly passes, which is exactly why it is not the one that runs"
fi
echo "governance gate selftest: PASS"
