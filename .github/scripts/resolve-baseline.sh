#!/usr/bin/env bash
# AT-16 S5: resolves the baseline commit the compatibility and governance gates compare against, so that every build
# event has one and none is fail-open:
#   - a ref (a PR target such as origin/main, or the commit before a push): the merge-base of that ref and HEAD, so a
#     stale PR branch is not blamed for what the base branch changed since it branched;
#   - an empty ref or the all-zero sha (a tag push, the first push of a branch): the previous release tag (v<semver>)
#     reachable from HEAD, or origin/main when there is none.
# Prints the commit sha, or nothing when no baseline exists; callers decide (a tag build must fail closed).
# Usage: resolve-baseline.sh [ref]        (run inside the repository)
set -euo pipefail
ref="${1:-}"
if [ -z "$ref" ] || [[ "$ref" =~ ^0+$ ]]; then
  head_sha="$(git rev-parse HEAD)"
  ref=""
  while read -r tag; do
    [ -n "$tag" ] || continue
    if [ "$(git rev-list -n 1 "$tag")" != "$head_sha" ]; then
      ref="$tag"
      break
    fi
  done < <(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname)
  ref="${ref:-origin/main}"
fi
if ! git rev-parse --verify --quiet "$ref^{commit}" >/dev/null; then
  exit 0
fi
git merge-base "$ref" HEAD 2>/dev/null || git rev-parse "$ref^{commit}"
