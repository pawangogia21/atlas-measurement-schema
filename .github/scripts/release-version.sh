#!/usr/bin/env bash
# AT-16 (design 31.10): one release tag v<major>.<minor>.<patch> drives every artifact (Maven jars, the vector artifact,
# the Swift package tag). Prints the version for a valid tag name (v1.0.0 -> 1.0.0), exits 1 for anything else.
set -euo pipefail
TAG="${1:-}"
if [[ "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "${BASH_REMATCH[1]}"
else
  echo "not a release tag (expected v<major>.<minor>.<patch>): '$TAG'" >&2
  exit 1
fi
