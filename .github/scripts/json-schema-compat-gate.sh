#!/usr/bin/env bash
# AT-16 sub-task L: runs the JSON Schema backward-compatibility gate (tools/schema-gate, JsonSchemaCompatibilityGate) for
# a CI job, the same baseline logic as avro-compat-gate.sh: compares the working tree's json-schema/ against the baseline
# ref (the PR target, or the commit before a push). With no baseline commit, or no json-schema/ at the baseline, there is
# nothing to compare and the gate passes with a message (only until the first release tag exists). Approvals: json-schema/.enum-approvals.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE_JAR="$REPO_ROOT/tools/schema-gate/target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar"
APPROVALS_FILE="$REPO_ROOT/json-schema/.enum-approvals"

# The baseline is the merge-base with the given ref; a tag build or a first push (empty or all-zero ref) compares against the
# previous release tag or origin/main (resolve-baseline.sh). A baseline that cannot be resolved fails a tag build (never
# fail-open on a release) and is skipped otherwise.
BASELINE_REF="$(cd "$REPO_ROOT" && "$(dirname "${BASH_SOURCE[0]}")/resolve-baseline.sh" "${1:-}")"
if [ -z "$BASELINE_REF" ]; then
  if [[ "${GITHUB_REF:-}" == refs/tags/* ]]; then
    echo "FAIL: no baseline to compare the JSON Schemas against on a release build (expected the previous release tag or origin/main)" >&2
    exit 1
  fi
  echo "Baseline '${1:-}' does not resolve - skipping the JSON Schema gate."
  exit 0
fi

BASELINE_DIR="$(mktemp -d)"
trap 'rm -rf "$BASELINE_DIR"' EXIT
git -C "$REPO_ROOT" archive "$BASELINE_REF" json-schema 2>/dev/null | tar -x -C "$BASELINE_DIR" 2>/dev/null || true
if [ ! -d "$BASELINE_DIR/json-schema" ]; then
  # the bootstrap exception ends with the first release: once a v* tag exists a baseline without schemas is a deletion
  if [ -n "$(git -C "$REPO_ROOT" tag --list 'v[0-9]*')" ]; then
    echo "FAIL: json-schema/ is missing at the baseline $BASELINE_REF but a release exists: the schemas were deleted or the baseline is wrong" >&2
    exit 1
  fi
  echo "No json-schema/ at $BASELINE_REF and no release yet - nothing to compare, JSON Schema gate passes (bootstrap)."
  exit 0
fi

echo "Baseline: $BASELINE_REF"
java -cp "$GATE_JAR" com.atlas.schemagate.JsonSchemaCompatibilityGate \
  "$BASELINE_DIR/json-schema" "$REPO_ROOT/json-schema" "$APPROVALS_FILE"
