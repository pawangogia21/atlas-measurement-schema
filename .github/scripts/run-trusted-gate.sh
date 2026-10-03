#!/usr/bin/env bash
# AT-16 S4: runs a gate script from the BASELINE commit instead of from the change under test, so a pull request cannot
# weaken or skip the gate it is judged by (a PR's own copy of governance-gate.sh, json-schema-compat-gate.sh, ... is
# never the one that runs). The gate still inspects the working tree. When the baseline does not have the script yet
# (the very change that introduces it) the working-tree copy runs and a warning says so.
# Usage: run-trusted-gate.sh <gate-script-name> <baseline-ref-or-empty> [more args for the gate]
#   e.g. run-trusted-gate.sh governance-gate.sh "$BASELINE"
# The helper scripts a gate calls (resolve-baseline.sh) are taken from the baseline too. The gate jar
# (tools/schema-gate/target) is built from the change under test by an earlier CI step; tools/schema-gate is CODEOWNERS-protected.
set -euo pipefail
NAME="${1:?usage: run-trusted-gate.sh <gate-script-name> <baseline-ref> [args]}"
REF="${2:-}"
shift 2 || true
ROOT="$(git rev-parse --show-toplevel)"
BASELINE="$("$ROOT/.github/scripts/resolve-baseline.sh" "$REF")"
TRUSTED="$(mktemp -d)"
trap 'rm -rf "$TRUSTED"' EXIT
if [ -n "$BASELINE" ] && git cat-file -e "$BASELINE:.github/scripts/$NAME" 2>/dev/null; then
  mkdir -p "$TRUSTED/.github/scripts"
  for f in "$NAME" resolve-baseline.sh; do
    git show "$BASELINE:.github/scripts/$f" > "$TRUSTED/.github/scripts/$f" 2>/dev/null || cp "$ROOT/.github/scripts/$f" "$TRUSTED/.github/scripts/$f"
    chmod +x "$TRUSTED/.github/scripts/$f"
  done
  # The gates locate the repository from their own path (avro-compat-gate.sh, json-schema-compat-gate.sh): give the baseline
  # copy a root that is the real checkout seen through symlinks (everything but .github/, which holds the trusted scripts).
  for entry in "$ROOT"/* "$ROOT"/.[!.]*; do
    [ -e "$entry" ] || continue
    [ "$(basename "$entry")" = ".github" ] || ln -s "$entry" "$TRUSTED/$(basename "$entry")"
  done
  echo "Running $NAME from the baseline ($BASELINE), not from the change under test."
  GATE_REPO_ROOT="$ROOT" "$TRUSTED/.github/scripts/$NAME" "${BASELINE:-$REF}" "$@"
else
  echo "::warning::$NAME does not exist at the baseline (the change that introduces it): running the working-tree copy"
  GATE_REPO_ROOT="$ROOT" "$ROOT/.github/scripts/$NAME" "${BASELINE:-$REF}" "$@"
fi
