#!/usr/bin/env bash
# AT-16 sub-task K (design 31.4, security review S4/S5): a change to the contract, the vectors or the tolerance profile is a
# new release and needs a recorded qaAgent approval. The governed paths, each a (artifact, version) with a content digest:
#   tolerance-profile  <v>   tolerance/<v>/                     one folder per released profile version
#   vectors-<set>      <v>   vectors/<set>/<v>/                 any set folder (conformance, boundary, negative, a new one)
#   file               <path> every other tracked file of: algorithm-spec.md, tolerance/, vectors/, json-schema/ (schemas,
#                            examples, .enum-approvals) and avro/.enum-approvals; README.md files are documentation, not governed
# digest = sha256 of the sorted "<path in the artifact>:<mode>:<sha256 of the content>" lines, listed NUL-separated with
# `git ls-files -z` (the index) for the working tree and `git ls-tree -z` for a ref: untracked files never count, the executable
# bit counts, a path with a control character or a space is hashed byte for byte (hex) so that no name can hide a change.
# The current tree must also be clean of the path tricks the digest cannot judge, each a FAIL: a symlink or gitlink under a
# governed path (a link's content lives outside the digest), a path with a control character or a space under a governed path,
# a path that is only a different case of a governed one (Vectors/, Tolerance/, Json-Schema/, vectors/Negative/ next to
# vectors/negative/: they collide on a case-insensitive checkout and CODEOWNERS does not match them), and a governance/
# qa-approvals.txt that is missing, a symlink or not a regular tracked file. Rules, against the baseline (resolve-baseline.sh: the merge-base with the PR target; the previous release
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
# Usage: governance-gate.sh <baseline-git-ref> | --digests | --digests-ref <git-ref>
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

# NUL-separated "<mode> <path>" records of every tracked file of the tree at $1 (a git ref, or WORKTREE for the index)
records() {
  local rec
  if [ "$1" = WORKTREE ]; then
    git_ ls-files -s -z
  else
    git_ ls-tree -r -z --full-tree "$1"
  fi | while IFS= read -r -d '' rec; do printf '%s %s\0' "${rec%% *}" "${rec#*$'\t'}"; done
}

# a path or name that is safe to print and to put in a space-separated line; anything else is its bytes in hex
enc() {
  case "$1" in
    *[[:cntrl:][:space:]]*) printf 'x-%s' "$(printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n')" ;;
    *) printf '%s' "$1" ;;
  esac
}

# sha256 of the content of a tracked regular file
content_sha() { # ref mode path
  if [ "$1" = WORKTREE ]; then
    if [ -f "$REPO_ROOT/$3" ] && [ ! -L "$REPO_ROOT/$3" ]; then
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
    algorithm-spec.md | avro/.enum-approvals | json-schema/* ) echo "file $(enc "$p") -" ;;
    tolerance/*)
      dir="${p#tolerance/}"
      v="${dir%%/*}"
      if [[ "$dir" == */* && "$v" =~ $SEMVER ]]; then echo "tolerance-profile $v $(enc "${dir#*/}")"; else echo "file $(enc "$p") -"; fi
      ;;
    vectors/*)
      dir="${p#vectors/}"
      set="${dir%%/*}"
      dir="${dir#*/}"
      v="${dir%%/*}"
      if [[ "$p" == vectors/*/*/* && "$v" =~ $SEMVER ]]; then echo "vectors-$(enc "$set") $v $(enc "${dir#*/}")"; else echo "file $(enc "$p") -"; fi
      ;;
    *) return 0 ;;
  esac
}

# the current tree must not hold what the digest cannot judge (see the header); exits 1 with one line per offender
check_paths() {
  local tmp rec mode path lower bad=0 dir collisions
  tmp="$(mktemp)"
  records WORKTREE > "$tmp"
  : > "$tmp.dirs"
  while IFS= read -r -d '' rec; do
    mode="${rec%% *}"
    path="${rec#* }"
    lower="$(printf '%s' "$path" | LC_ALL=C tr 'A-Z' 'a-z')"
    case "$lower" in
      tolerance/* | vectors/* | json-schema/* | algorithm-spec.md | avro/.enum-approvals | governance/qa-approvals.txt) ;;
      *) continue ;;
    esac
    case "$path" in
      tolerance/* | vectors/* | json-schema/* | algorithm-spec.md | avro/.enum-approvals | governance/qa-approvals.txt) ;;
      *) echo "FAIL: $(enc "$path") differs only in case from a governed path (it collides with it on a case-insensitive checkout and CODEOWNERS does not match it)" >&2; bad=1; continue ;;
    esac
    case "$path" in
      *[[:cntrl:][:space:]]*) echo "FAIL: governed path $(enc "$path") has a control character or a space in its name" >&2; bad=1; continue ;;
    esac
    case "$mode" in
      100644 | 100755) ;;
      120000) echo "FAIL: governed path $path is a symlink (its content lives outside the digest)" >&2; bad=1 ;;
      *) echo "FAIL: governed path $path has git mode $mode (only regular files are governed)" >&2; bad=1 ;;
    esac
    # every directory prefix and the path itself, lower-cased next to the original, to find case collisions between tracked paths
    dir="$path"
    while [ -n "$dir" ]; do
      printf '%s\t%s\n' "$(printf '%s' "$dir" | LC_ALL=C tr 'A-Z' 'a-z')" "$dir" >> "$tmp.dirs"
      case "$dir" in */*) dir="${dir%/*}" ;; *) dir="" ;; esac
    done
  done < "$tmp"
  collisions="$(LC_ALL=C sort -u "$tmp.dirs" | awk -F'\t' '{c[$1]++} END {for (k in c) if (c[k] > 1) print k}')"
  rm -f "$tmp" "$tmp.dirs"
  if [ -n "$collisions" ]; then
    echo "FAIL: tracked paths that differ only in case: $(echo "$collisions" | tr '\n' ' ')" >&2
    bad=1
  fi
  [ "$bad" -eq 0 ] || { echo "Governance gate: FAIL" >&2; exit 1; }
}

# "<artifact> <version> <digest>" lines for the tree at $1
artifacts() {
  local ref="$1" rec mode path c artifact version rel
  [ "$ref" != WORKTREE ] || check_paths
  records "$ref" | while IFS= read -r -d '' rec; do
    mode="${rec%% *}"
    path="${rec#* }"
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
if [ "${1:-}" = "--digests-ref" ] && [ $# -eq 2 ]; then # the digests of a ref, without the path checks (selftest, forensics)
  artifacts "$2"
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

# the approvals file is a regular tracked file of this repository, not a link to a file outside governance/
approvals_mode="$(git_ ls-files -s -- governance/qa-approvals.txt | cut -d' ' -f1)"
if { [ "$approvals_mode" != 100644 ] && [ "$approvals_mode" != 100755 ]; } || [ -L "$REPO_ROOT/governance/qa-approvals.txt" ]; then
  echo "FAIL: governance/qa-approvals.txt must be a regular tracked file (git mode '${approvals_mode:-untracked}', symlink: $([ -L "$REPO_ROOT/governance/qa-approvals.txt" ] && echo yes || echo no))" >&2
  echo "Governance gate: FAIL" >&2
  exit 1
fi

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
