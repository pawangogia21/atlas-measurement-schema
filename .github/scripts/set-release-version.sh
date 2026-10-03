#!/usr/bin/env bash
# AT-16: stamps one release version into every published Maven module (reference jar, generated types, Kotlin artifact,
# vector artifact, Avro codegen). Works on the CI checkout only; nothing is committed. The Kotlin artifact depends on
# the types jar through ${project.version}, so the stamped versions stay consistent.
set -euo pipefail
VERSION="${1:?usage: set-release-version.sh <version>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
for module in reference codegen-json kotlin vectors-artifact codegen; do
  "$ROOT/mvnw" -B -q -f "$ROOT/$module/pom.xml" org.codehaus.mojo:versions-maven-plugin:2.17.1:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
done
echo "stamped $VERSION"
