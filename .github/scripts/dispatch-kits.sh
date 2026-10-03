#!/usr/bin/env bash
# AT-16 S3 (design 31.10): tells the Kit repositories that a schema release exists (repository_dispatch event
# measurement-schema-released, payload {"version": "<version>"}). Every Kit is attempted, so one failing does not skip the
# other, and the script exits non-zero when any dispatch failed (curl --fail-with-body: an HTTP 4xx/5xx is a failure, nothing
# is swallowed). The token goes to curl through stdin, not the command line.
# Env: KIT_DISPATCH_TOKEN (fine-grained token, Contents: write on the Kit repositories only), GITHUB_REPOSITORY_OWNER,
# optional GITHUB_API_URL (selftest only).
# Usage: dispatch-kits.sh <version> <kit-repo-name>...      e.g. dispatch-kits.sh 1.0.0 atlas-measure-kit-ios atlas-measure-kit-android
set -euo pipefail
VERSION="${1:?usage: dispatch-kits.sh <version> <kit-repo-name>...}"
shift
[ "$#" -gt 0 ] || { echo "no Kit repositories given" >&2; exit 1; }
TOKEN="${KIT_DISPATCH_TOKEN:?KIT_DISPATCH_TOKEN is not set: the Kits cannot be notified (set the secret, or KIT_DISPATCH_ENABLED=false to skip it on purpose)}"
OWNER="${GITHUB_REPOSITORY_OWNER:?GITHUB_REPOSITORY_OWNER is required}"
API="${GITHUB_API_URL:-https://api.github.com}"
failed=()
for kit in "$@"; do
  if printf 'Authorization: Bearer %s\n' "$TOKEN" | curl --fail-with-body -sS -o /dev/null -H @- \
      -H "Accept: application/vnd.github+json" -X POST "$API/repos/$OWNER/$kit/dispatches" \
      -d "{\"event_type\":\"measurement-schema-released\",\"client_payload\":{\"version\":\"$VERSION\"}}"; then
    echo "dispatched measurement-schema-released $VERSION to $OWNER/$kit"
  else
    echo "::error::dispatch to $OWNER/$kit failed" >&2
    failed+=("$kit")
  fi
done
if [ "${#failed[@]}" -gt 0 ]; then
  echo "FAIL: dispatch failed for: ${failed[*]}" >&2
  exit 1
fi
