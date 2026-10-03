#!/usr/bin/env bash
# AT-16 (design 19.1, 31.10): assembles the tree of the published Swift package atlas-measurement-schema-swift. SwiftPM
# resolves a package from the repository root and a version tag, so the monorepo's swift/ folder is published to its own
# repository with Package.swift at the root. The tree holds the generated types (run swift/generate-types.sh first), the
# hand-written support files, the tolerance port with every released tolerance-profile.json as a resource, and the vectors
# (AtlasMeasurementVectors, for test targets only), each with a manifest.json (version, sha256 per file); test targets are
# dropped (they read the monorepo's files). The release version comes from the second argument, or from the tag when
# GITHUB_REF_NAME is a release tag (v<major>.<minor>.<patch>), or is 0.0.0-dev.
# Usage: assemble-swift-package.sh <outDir> [version]
set -euo pipefail
OUT="${1:?usage: assemble-swift-package.sh <outDir> [version]}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWIFT="$ROOT/swift"

if [ ! -f "$SWIFT/Sources/AtlasMeasurementSchema/Generated.swift" ]; then
  echo "swift/Sources/AtlasMeasurementSchema/Generated.swift is missing: run swift/generate-types.sh first" >&2
  exit 1
fi
VERSION="${2:-}"
if [ -z "$VERSION" ] && [[ "${GITHUB_REF_NAME:-}" == v* ]]; then
  VERSION="$("$ROOT/.github/scripts/release-version.sh" "$GITHUB_REF_NAME")"
fi
VERSION="${VERSION:-0.0.0-dev}"
# stage the bundled profiles and vectors from the repository sources, with the release version in the manifests
ATLAS_RELEASE_VERSION="$VERSION" "$SWIFT/stage-resources.sh"
mkdir -p "$OUT"
cp -R "$SWIFT/Sources" "$OUT/Sources"
grep -v 'testTarget' "$SWIFT/Package.swift" > "$OUT/Package.swift"
cat > "$OUT/README.md" <<'README'
# atlas-measurement-schema-swift

Published artifact of atlas-measurement-schema (AT-16): the Swift types generated from the measurement JSON Schema, the
tolerance function with every released tolerance profile as a resource (`ToleranceProfile.bundled(version:)`), and the
conformance, boundary and negative vectors (`AtlasMeasurementVectors`, for test targets only). Each resource folder has a
`manifest.json` (version, sha256 per file, toleranceProfileVersion). Do not edit: this repository is written by the schema repository's release job, one tag per release
(`v<major>.<minor>.<patch>`, the same version as every other artifact). Add it by version:

```swift
.package(url: "https://github.com/pawangogia21/atlas-measurement-schema-swift", exact: "1.0.0")
```
README
# the staged manifests must carry the release version, never the placeholder
if grep -rq '@project.version@' "$OUT/Sources"; then
  echo "a manifest still holds the version placeholder" >&2
  exit 1
fi
echo "assembled the Swift package $VERSION in $OUT"
