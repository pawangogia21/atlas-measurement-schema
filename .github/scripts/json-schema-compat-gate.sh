#!/usr/bin/env bash
# AT-16 sub-task L: runs the JSON Schema backward-compatibility gate (tools/schema-gate, JsonSchemaCompatibilityGate) for
# a CI job, the same baseline logic as avro-compat-gate.sh: compares the working tree's json-schema/ against the baseline
# ref (the PR target, or the commit before a push). With no baseline commit, or no json-schema/ at the baseline, there is
# nothing to compare and the gate passes with a message. Approvals: json-schema/.enum-approvals.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE_JAR="$REPO_ROOT/tools/schema-gate/target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar"
APPROVALS_FILE="$REPO_ROOT/json-schema/.enum-approvals"

BASELINE_REF="${1:-}"
if [ -z "$BASELINE_REF" ]; then
  echo "Usage: json-schema-compat-gate.sh <baseline-git-ref>" >&2
  exit 2
fi

if ! git -C "$REPO_ROOT" rev-parse --verify "$BASELINE_REF^{commit}" >/dev/null 2>&1; then
  echo "Baseline ref '$BASELINE_REF' does not exist yet - skipping the JSON Schema gate."
  exit 0
fi

BASELINE_DIR="$(mktemp -d)"
trap 'rm -rf "$BASELINE_DIR"' EXIT
git -C "$REPO_ROOT" archive "$BASELINE_REF" json-schema 2>/dev/null | tar -x -C "$BASELINE_DIR" 2>/dev/null || true
if [ ! -d "$BASELINE_DIR/json-schema" ]; then
  echo "No json-schema/ at $BASELINE_REF - nothing to compare, JSON Schema gate passes trivially."
  exit 0
fi

echo "Baseline: $BASELINE_REF"
java -cp "$GATE_JAR" com.atlas.schemagate.JsonSchemaCompatibilityGate \
  "$BASELINE_DIR/json-schema" "$REPO_ROOT/json-schema" "$APPROVALS_FILE"
