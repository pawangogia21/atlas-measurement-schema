#!/usr/bin/env bash
# AT-16 S3/S11: publishes the assembled Swift package to its own repository (default <owner>/atlas-measurement-schema-swift)
# as one commit on the default branch plus the annotated tag v<version>, in that order: the tag exists only after the content
# was pushed. Fails closed on every problem (missing repository, bad or expired token, push rejected); the caller decides
# whether to skip this step at all (repository variable SWIFT_REPO_ENABLED=false). Idempotent for a re-run:
#   - the tag exists and the tagged tree equals the package -> nothing to do (success);
#   - the tag exists with different content -> FAIL (a released version is never moved);
#   - the release commit is on the branch but the tag is missing -> only the tag is created.
# The token never goes into a URL or a remote config: it is passed to git through GIT_CONFIG_* environment variables as an
# http.extraheader, and the encoded form is registered as a mask for the log.
# Env: SWIFT_REPO_TOKEN (fine-grained token, Contents: write on the Swift repository only), GITHUB_REPOSITORY_OWNER,
# optional SWIFT_REPO (full name), SWIFT_REPO_URL_BASE (https://github.com, selftest only).
# Usage: publish-swift-package.sh <package-dir> <version>
set -euo pipefail
PKG="${1:?usage: publish-swift-package.sh <package-dir> <version>}"
VERSION="${2:?usage: publish-swift-package.sh <package-dir> <version>}"
TOKEN="${SWIFT_REPO_TOKEN:?SWIFT_REPO_TOKEN is not set: the Swift package cannot be published (set the secret, or SWIFT_REPO_ENABLED=false to skip it on purpose)}"
SWIFT_REPO="${SWIFT_REPO:-${GITHUB_REPOSITORY_OWNER:?GITHUB_REPOSITORY_OWNER is required}/atlas-measurement-schema-swift}"
URL_BASE="${SWIFT_REPO_URL_BASE:-https://github.com}"
TAG="v$VERSION"
[ -f "$PKG/Package.swift" ] || { echo "FAIL: $PKG is not an assembled Swift package (no Package.swift)" >&2; exit 1; }

BASIC="$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')"
echo "::add-mask::$BASIC"
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="http.$URL_BASE/.extraheader"
export GIT_CONFIG_VALUE_0="AUTHORIZATION: basic $BASIC"
export GIT_TERMINAL_PROMPT=0

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
if ! git clone --quiet "$URL_BASE/$SWIFT_REPO.git" "$WORK/repo"; then
  echo "FAIL: cannot clone $SWIFT_REPO: the repository does not exist, or the token cannot read it, or it expired. Nothing was published to it." >&2
  exit 1
fi
cd "$WORK/repo"
git config user.name "atlas-release"
git config user.email "atlas-release@users.noreply.github.com"

same_tree_as_package() { # directory content equals the package, ignoring .git
  diff -r -q -x .git "$1" "$PKG" >/dev/null
}

if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  git fetch --quiet origin "refs/tags/$TAG:refs/tags/$TAG"
  git worktree add --quiet --detach "$WORK/tagged" "refs/tags/$TAG"
  if same_tree_as_package "$WORK/tagged"; then
    echo "Swift package $TAG already published with identical content: nothing to do"
    exit 0
  fi
  echo "FAIL: $SWIFT_REPO already has tag $TAG with different content: a released version is never moved. Release the next patch version." >&2
  exit 1
fi

BRANCH="$(git symbolic-ref --quiet --short HEAD || echo main)"
git checkout --quiet -B "$BRANCH"
if git rev-parse --verify --quiet HEAD >/dev/null && [ "$(git log -1 --format=%s)" = "Release $TAG" ] && same_tree_as_package "$WORK/repo"; then
  echo "The release commit for $TAG is already on $BRANCH (an earlier run stopped before the tag): only the tag is missing"
else
  git rm -rq --ignore-unmatch . >/dev/null
  cp -R "$PKG"/. .
  git add -A
  git commit --quiet --allow-empty -m "Release $TAG"
  git push --quiet origin "HEAD:refs/heads/$BRANCH"
fi
git tag -a "$TAG" -m "$TAG"
git push --quiet origin "refs/tags/$TAG"
echo "Published the Swift package $TAG to $SWIFT_REPO ($BRANCH)"
