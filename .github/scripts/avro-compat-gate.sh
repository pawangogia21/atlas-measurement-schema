#!/usr/bin/env bash
# AT-2 task 3: runs the Avro backward-compatibility + enum-change gate (tools/schema-gate) for a
# CI job. Compares the working tree's avro/*.avsc against the "last released version" baseline:
#   - pull_request: the PR's target branch (origin/<base ref>)
#   - push (e.g. after a merge to main): the commit before this push ($GITHUB_EVENT_BEFORE)
# On the very first commit of a repo/branch (no baseline commit exists yet), the gate is skipped
# with a message, since there is nothing to compare against.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE_JAR="$REPO_ROOT/tools/schema-gate/target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar"
APPROVALS_FILE="$REPO_ROOT/avro/.enum-approvals"
BASELINE_DIR="$(mktemp -d)"

# The baseline is the merge-base with the given ref; a tag build or a first push (empty or all-zero ref) compares against the
# previous release tag or origin/main (resolve-baseline.sh). A baseline that cannot be resolved fails a tag build.
BASELINE_REF="$(cd "$REPO_ROOT" && "$(dirname "${BASH_SOURCE[0]}")/resolve-baseline.sh" "${1:-}")"
if [ -z "$BASELINE_REF" ]; then
  if [[ "${GITHUB_REF:-}" == refs/tags/* ]]; then
    echo "FAIL: no baseline to compare the Avro schemas against on a release build" >&2
    exit 1
  fi
  echo "Baseline '${1:-}' does not exist yet (first commit on this branch) - skipping the gate."
  exit 0
fi

echo "Baseline: $BASELINE_REF"
FOUND_ANY=0
for f in "$REPO_ROOT"/avro/*.avsc; do
  name="$(basename "$f")"
  if git -C "$REPO_ROOT" show "$BASELINE_REF:avro/$name" > "$BASELINE_DIR/$name" 2>/dev/null; then
    FOUND_ANY=1
  else
    rm -f "$BASELINE_DIR/$name"
  fi
done

if [ "$FOUND_ANY" -eq 0 ]; then
  echo "No avro/*.avsc existed at $BASELINE_REF - nothing to compare, gate passes trivially."
  exit 0
fi

java -jar "$GATE_JAR" "$BASELINE_DIR" "$REPO_ROOT/avro" "$APPROVALS_FILE"
