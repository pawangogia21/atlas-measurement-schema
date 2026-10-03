#!/usr/bin/env bash
# AT-16 sub-task K (design 31.4, security review S4/S5): a change to the contract, the vectors or the tolerance profile is a
# new release and needs a recorded qaAgent approval. The governed paths, each a (artifact, version) with a content digest:
#   tolerance-profile  <v>   tolerance/<v>/                     one folder per released profile version
#   vectors-<set>      <v>   vectors/<set>/<v>/                 any set folder (conformance, boundary, negative, a new one)
#   file               <path> every other tracked file of: algorithm-spec.md, tolerance/, vectors/, json-schema/ (schemas,
#                            examples, .enum-approvals) and avro/.enum-approvals; README.md files are documentation, not governed
# digest = sha256 of the sorted "<path in the artifact>:<mode>:<sha256 of the content>" lines, listed with `git ls-files` (the
# index) for the working tree and `git ls-tree` for a ref: untracked files never count, a symlink counts as its target, the
# executable bit counts. Rules, against the baseline (resolve-baseline.sh: the merge-base with the PR target; the previous release
# tag or origin/main for a tag build):
#   1. Immutability: a versioned artifact (tolerance-profile, vectors-*) that exists at the baseline must have the same digest: a
#      retune is 1.1.0 in a new folder, never an edit of 1.0.0.
#   2. Approval: an artifact version that is new, or a `file` that changed, needs a line in governance/qa-approvals.txt
#         <artifact> <version> <digest> <approved-by> <yyyy-mm-dd> <ticket>
#      with the digest printed by `governance-gate.sh --digests`.
#   3. Deletion: an artifact that exists at the baseline and is gone fails, unless the file holds the line
#         <artifact> <version> RETIRE <approved-by> <yyyy-mm-dd> <ticket>
#   4. The approvals file is append-only: a line present at the baseline must still be there, unchanged.
#   5. A tolerance profile folder must hold the version it is named after.
# Limit, stated plainly: the approver is free text, so the file proves what was approved, not who approved it. Who may add a
# line is decided by the repository (CODEOWNERS on governance/, vectors/, tolerance/, json-schema/, .github/ and a required
# review from the code owner). The gate script itself is run from the baseline by run-trusted-gate.sh, so a change cannot weaken
# the gate it is judged by. GOVERNANCE_APPROVALS (a different approvals file, for local checks and the selftest) is ignored when
# GITHUB_ACTIONS is set.
# Usage: governance-gate.sh <baseline-git-ref> | --digests
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${GATE_REPO_ROOT:-$(cd "$HERE/../.." && pwd)}"
APPROVALS="$REPO_ROOT/governance/qa-approvals.txt"
if [ -z "${GITHUB_ACTIONS:-}" ] && [ -n "${GOVERNANCE_APPROVALS:-}" ]; then
  APPROVALS="$GOVERNANCE_APPROVALS"
fi
SEMVER='^[0-9]+\.[0-9]+\.[0-9]+$'

sha() { shasum -a 256 | cut -d' ' -f1; }
git_() { git -C "$REPO_ROOT" "$@"; }

# "<mode> <path>" for every tracked file of the tree at $1 (a git ref, or WORKTREE for the index)
records() {
  if [ "$1" = WORKTREE ]; then
    git_ ls-files -s -z | while IFS= read -r -d '' rec; do printf '%s %s\n' "${rec%% *}" "${rec#*$'\t'}"; done
  else
    git_ ls-tree -r -z --full-tree "$1" | while IFS= read -r -d '' rec; do printf '%s %s\n' "${rec%% *}" "${rec#*$'\t'}"; done
  fi
}

# sha256 of the content of a tracked file; a symlink is its target
content_sha() { # ref mode path
  if [ "$1" = WORKTREE ]; then
    if [ "$2" = 120000 ]; then
      printf '%s' "$(readlink "$REPO_ROOT/$3")" | sha
    elif [ -f "$REPO_ROOT/$3" ]; then
      sha < "$REPO_ROOT/$3"
    else
      echo MISSING
    fi
  else
    git_ cat-file blob "$1:$3" | sha
  fi
}

# prints "<artifact> <version> <path in the artifact>" for a governed path, nothing for an ungoverned one
classify() {
  local p="$1" dir v set
  case "$p" in
    */README.md) return 0 ;;
    algorithm-spec.md | avro/.enum-approvals | json-schema/* ) echo "file $p -" ;;
    tolerance/*)
      dir="${p#tolerance/}"
      v="${dir%%/*}"
      if [[ "$dir" == */* && "$v" =~ $SEMVER ]]; then echo "tolerance-profile $v ${dir#*/}"; else echo "file $p -"; fi
      ;;
    vectors/*)
      dir="${p#vectors/}"
      set="${dir%%/*}"
      dir="${dir#*/}"
      v="${dir%%/*}"
      if [[ "$p" == vectors/*/*/* && "$v" =~ $SEMVER ]]; then echo "vectors-$set $v ${dir#*/}"; else echo "file $p -"; fi
      ;;
    *) return 0 ;;
  esac
}

# "<artifact> <version> <digest>" lines for the tree at $1
artifacts() {
  local ref="$1" mode path c artifact version rel
  records "$ref" | while read -r mode path; do
    case "$path" in
      *" "*) case "$path" in algorithm-spec.md | avro/.enum-approvals | json-schema/* | tolerance/* | vectors/*) echo "unsupported governed path with a space: $path" >&2; exit 1 ;; esac ;;
    esac
    c="$(classify "$path")"
    [ -n "$c" ] || continue
    read -r artifact version rel <<< "$c"
    echo "$artifact $version $rel:$mode:$(content_sha "$ref" "$mode" "$path")"
  done | LC_ALL=C sort | {
    prev=""
    buf=""
    while read -r artifact version line; do
      key="$artifact $version"
      if [ -n "$prev" ] && [ "$key" != "$prev" ]; then
        echo "$prev $(printf '%s' "$buf" | sha)"
        buf=""
      fi
      prev="$key"
      buf="$buf$line"$'\n'
    done
    if [ -n "$prev" ]; then
      echo "$prev $(printf '%s' "$buf" | sha)"
    fi
  }
}

if [ "${1:-}" = "--digests" ]; then
  artifacts WORKTREE
  exit 0
fi

if [ $# -lt 1 ]; then
  echo "Usage: governance-gate.sh <baseline-git-ref> | --digests" >&2
  exit 2
fi
BASELINE_REF="$(cd "$REPO_ROOT" && "$HERE/resolve-baseline.sh" "$1")"

BASELINE=""
BASELINE_APPROVALS=""
if [ -n "$BASELINE_REF" ]; then
  BASELINE="$(artifacts "$BASELINE_REF")"
  BASELINE_APPROVALS="$(git_ show "$BASELINE_REF:governance/qa-approvals.txt" 2>/dev/null || true)"
  echo "Baseline: $1 -> $BASELINE_REF"
else
  echo "Baseline '$1' does not resolve - every governed artifact needs an approval and nothing counts as released."
fi
CURRENT="$(artifacts WORKTREE)"

fail=0
# an approval line: <artifact> <version> <digest or RETIRE> <approved-by> <yyyy-mm-dd> <ticket>
approved() { # artifact version digest
  awk -v a="$1" -v v="$2" -v d="$3" \
    '$1!~/^#/ && NF>=6 && $1==a && $2==v && $3==d && $4!="" && $5~/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/ && $6~/^[A-Z]+-[0-9]+$/ {found=1} END {exit !found}' \
    "$APPROVALS" 2>/dev/null
}

# 4. the approvals file is append-only
if [ -n "$BASELINE_APPROVALS" ]; then
  while IFS= read -r line; do
    case "$line" in '' | '#'*) continue ;; esac
    if ! grep -qxF -- "$line" "$APPROVALS" 2>/dev/null; then
      echo "FAIL: an approval line present at the baseline was removed or edited: $line" >&2
      fail=1
    fi
  done <<< "$BASELINE_APPROVALS"
fi

# 5. a profile folder holds its own version
while read -r artifact version _; do
  [ "$artifact" = tolerance-profile ] || continue
  labelled="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REPO_ROOT/tolerance/$version/tolerance-profile.json" 2>/dev/null | head -1)"
  if [ "$labelled" != "$version" ]; then
    echo "FAIL: tolerance/$version/tolerance-profile.json holds version '$labelled': a folder holds the version it is named after" >&2
    fail=1
  fi
done <<< "$CURRENT"

# 3. deletions: a baseline artifact that is gone
while read -r artifact version digest; do
  [ -n "$artifact" ] || continue
  if ! echo "$CURRENT" | awk -v a="$artifact" -v v="$version" '$1==a && $2==v {found=1} END {exit !found}'; then
    if ! approved "$artifact" "$version" RETIRE; then
      echo "FAIL: $artifact $version exists at the baseline and was removed (a released version never disappears)" >&2
      echo "      to retire it on purpose, a QA approver adds: $artifact $version RETIRE <qa-approver> <yyyy-mm-dd> <ticket>" >&2
      fail=1
    fi
  fi
done <<< "$BASELINE"

# 1 and 2. immutability and approval
while read -r artifact version digest; do
  [ -n "$artifact" ] || continue
  base_digest="$(echo "$BASELINE" | awk -v a="$artifact" -v v="$version" '$1==a && $2==v {print $3}')"
  if [ "$base_digest" = "$digest" ]; then
    continue
  fi
  if [ -n "$base_digest" ] && [ "$artifact" != file ]; then
    echo "FAIL: $artifact $version is released at the baseline and was edited (digest $base_digest -> $digest): a change is a new version, never an edit" >&2
    fail=1
    continue
  fi
  if ! approved "$artifact" "$version" "$digest"; then
    echo "FAIL: $artifact $version (digest $digest) is new or changed and has no approval line in governance/qa-approvals.txt" >&2
    echo "      add: $artifact $version $digest <qa-approver> <yyyy-mm-dd> <ticket>" >&2
    fail=1
  fi
done <<< "$CURRENT"

if [ "$fail" -ne 0 ]; then
  echo "Governance gate: FAIL" >&2
  exit 1
fi
echo "Governance gate: PASS"
