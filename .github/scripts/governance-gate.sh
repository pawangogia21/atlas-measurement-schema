#!/usr/bin/env bash
# AT-16 sub-task K (design 31.4): a vector or tolerance-profile change is a new release and needs a recorded qaAgent
# approval. Artifacts, each identified by (artifact, version) with a content digest:
#   tolerance-profile   tolerance/tolerance-profile.json            (version = its "version" field)
#   vectors-<set>       vectors/<set>/<version>/ for conformance, boundary, negative (version = the directory name)
# digest = sha256 of the sorted "<path relative to the artifact>:<sha256 of the file>" lines.
# Rules, against the baseline ref (the PR target, or the commit before a push):
#   1. Immutability: an artifact version that exists at the baseline must have the same digest (a retune is 1.1.0,
#      never an edit of 1.0.0).
#   2. Approval: an artifact version that is new or changed relative to the baseline needs a line in
#      governance/qa-approvals.txt:   <artifact> <version> <digest> <approved-by> <yyyy-mm-dd> <ticket>
#      with approved-by = qaAgent (or the name of the QA approver), after review.
# `governance-gate.sh --digests` prints the current artifact lines for the QA approver to copy. With no baseline commit
# the baseline is empty and every artifact version needs an approval.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APPROVALS="${GOVERNANCE_APPROVALS:-$REPO_ROOT/governance/qa-approvals.txt}"
SETS="conformance boundary negative"

sha() { shasum -a 256 | cut -d' ' -f1; }

# prints "<artifact> <version> <digest>" lines for the tree at $1 (a git ref, or WORKTREE)
artifacts() {
  local ref="$1" set version dir
  if [ "$ref" = WORKTREE ]; then
    if [ -f "$REPO_ROOT/tolerance/tolerance-profile.json" ]; then
      version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REPO_ROOT/tolerance/tolerance-profile.json" | head -1)"
      echo "tolerance-profile $version $(sha < "$REPO_ROOT/tolerance/tolerance-profile.json")"
    fi
    for set in $SETS; do
      for dir in "$REPO_ROOT"/vectors/"$set"/*/; do
        [ -d "$dir" ] || continue
        version="$(basename "$dir")"
        echo "vectors-$set $version $(cd "$dir" && find . -type f | sed 's|^\./||' | LC_ALL=C sort | while read -r f; do echo "$f:$(sha < "$f")"; done | sha)"
      done
    done
  else
    if git -C "$REPO_ROOT" cat-file -e "$ref:tolerance/tolerance-profile.json" 2>/dev/null; then
      version="$(git -C "$REPO_ROOT" show "$ref:tolerance/tolerance-profile.json" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
      echo "tolerance-profile $version $(git -C "$REPO_ROOT" show "$ref:tolerance/tolerance-profile.json" | sha)"
    fi
    for set in $SETS; do
      for version in $(git -C "$REPO_ROOT" ls-tree -d --name-only "$ref" "vectors/$set/" 2>/dev/null | sed 's|.*/||'); do
        echo "vectors-$set $version $(git -C "$REPO_ROOT" ls-tree -r --name-only "$ref" "vectors/$set/$version/" | LC_ALL=C sort | while read -r f; do echo "${f#vectors/$set/$version/}:$(git -C "$REPO_ROOT" show "$ref:$f" | sha)"; done | sha)"
      done
    done
  fi
}

if [ "${1:-}" = "--digests" ]; then
  artifacts WORKTREE
  exit 0
fi

BASELINE_REF="${1:-}"
if [ -z "$BASELINE_REF" ]; then
  echo "Usage: governance-gate.sh <baseline-git-ref> | --digests" >&2
  exit 2
fi

BASELINE=""
if git -C "$REPO_ROOT" rev-parse --verify "$BASELINE_REF^{commit}" >/dev/null 2>&1; then
  BASELINE="$(artifacts "$BASELINE_REF")"
  echo "Baseline: $BASELINE_REF"
else
  echo "Baseline ref '$BASELINE_REF' does not exist - every artifact version needs an approval."
fi

fail=0
while read -r artifact version digest; do
  [ -n "$artifact" ] || continue
  base_digest="$(echo "$BASELINE" | awk -v a="$artifact" -v v="$version" '$1==a && $2==v {print $3}')"
  if [ "$base_digest" = "$digest" ]; then
    continue
  fi
  if [ -n "$base_digest" ]; then
    echo "FAIL: $artifact $version is released at the baseline and was edited (digest $base_digest -> $digest): a change is a new version, never an edit" >&2
    fail=1
    continue
  fi
  if ! awk -v a="$artifact" -v v="$version" -v d="$digest" \
      '$1!~/^#/ && NF>=6 && $1==a && $2==v && $3==d && $4!="" && $5~/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/ && $6~/^[A-Z]+-[0-9]+$/ {found=1} END {exit !found}' \
      "$APPROVALS" 2>/dev/null; then
    echo "FAIL: $artifact $version (digest $digest) is new or changed and has no approval line in governance/qa-approvals.txt" >&2
    echo "      add: $artifact $version $digest <qa-approver> <yyyy-mm-dd> <ticket>" >&2
    fail=1
  fi
done <<< "$(artifacts WORKTREE)"

if [ "$fail" -ne 0 ]; then
  echo "Governance gate: FAIL" >&2
  exit 1
fi
echo "Governance gate: PASS"
