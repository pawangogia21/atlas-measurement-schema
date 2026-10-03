#!/usr/bin/env bash
# AT-16 S1: a release may only be built from a commit that is on main. Fails unless the commit under release (HEAD of the
# checkout, i.e. the commit the tag points at) is an ancestor of origin/main. Needs a full-history checkout (fetch-depth: 0),
# which also fetches origin/main; run it before anything that holds a secret.
# Usage: verify-tag-provenance.sh [base-branch]        (default: main)
set -euo pipefail
BASE="${1:-main}"
if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
  echo "FAIL: shallow checkout: the provenance check needs the full history (actions/checkout fetch-depth: 0)" >&2
  exit 1
fi
if ! git rev-parse --verify --quiet "refs/remotes/origin/$BASE^{commit}" >/dev/null; then
  echo "FAIL: origin/$BASE is not available in the checkout: cannot prove where the tagged commit is" >&2
  exit 1
fi
SHA="$(git rev-parse HEAD)"
if ! git merge-base --is-ancestor "$SHA" "origin/$BASE"; then
  echo "FAIL: commit $SHA is not reachable from origin/$BASE: a release is only built from a commit that is on $BASE" >&2
  exit 1
fi
echo "OK: $SHA is on origin/$BASE"
