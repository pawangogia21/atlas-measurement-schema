#!/usr/bin/env bash
# AT-16 S11: before anything is deployed, find out which Maven artifacts of this release version already exist in the
# registry (GitHub Packages), so a re-run after a partial release is safe:
#   none      -> nothing is published yet: deploy everything;
#   complete  -> every artifact of the version exists (a re-run after a later step, such as the Swift push, failed): the Maven
#                deploy is skipped, a published version is never overwritten;
#   partial   -> some but not all exist: FAIL with the list. The registry holds a half release that must be cleaned up by a
#                human (delete the listed package versions, or release the next patch version), never papered over.
# Prints the state and, when GITHUB_OUTPUT is set, writes maven_state=<state> there.
# Env: GITHUB_TOKEN (read access to the repository's packages), GITHUB_ACTOR, GITHUB_REPOSITORY; PACKAGES_BASE_URL overrides
# https://maven.pkg.github.com (selftest only).
# Usage: release-preflight.sh <version>
set -euo pipefail
VERSION="${1:?usage: release-preflight.sh <version>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${PACKAGES_BASE_URL:-https://maven.pkg.github.com}"
TOKEN="${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
ACTOR="${GITHUB_ACTOR:-x-access-token}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
MODULES="codegen-json reference kotlin vectors-artifact codegen"

BASIC="$(printf '%s:%s' "$ACTOR" "$TOKEN" | base64 | tr -d '\n')"
echo "::add-mask::$BASIC"

present=()
absent=()
for module in $MODULES; do
  coords="$(python3 - "$ROOT/$module/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
root = ET.parse(sys.argv[1]).getroot()
print(root.findtext('m:groupId', namespaces=ns), root.findtext('m:artifactId', namespaces=ns))
PY
)"
  group="${coords%% *}"
  artifact="${coords##* }"
  url="$BASE_URL/$REPO/${group//.//}/$artifact/$VERSION/$artifact-$VERSION.pom"
  code="$(printf 'Authorization: Basic %s\n' "$BASIC" | curl -sS -o /dev/null -w '%{http_code}' -H @- "$url")"
  case "$code" in
    200) present+=("$group:$artifact:$VERSION") ;;
    404) absent+=("$group:$artifact:$VERSION") ;;
    *)
      echo "FAIL: cannot tell whether $group:$artifact:$VERSION exists (HTTP $code from the registry): refusing to deploy blind" >&2
      exit 1
      ;;
  esac
done

if [ "${#present[@]}" -eq 0 ]; then
  state=none
elif [ "${#absent[@]}" -eq 0 ]; then
  state=complete
else
  {
    echo "FAIL: release $VERSION is half published in the registry."
    echo "  already published: ${present[*]}"
    echo "  missing:           ${absent[*]}"
    echo "  Versions are immutable. Delete the published package versions above in GitHub Packages and re-run, or release the next patch version."
  } >&2
  exit 1
fi
echo "Maven artifacts of $VERSION: $state"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "maven_state=$state" >> "$GITHUB_OUTPUT"
fi
