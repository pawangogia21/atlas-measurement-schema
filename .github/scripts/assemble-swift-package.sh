#!/usr/bin/env bash
# AT-16 (design 19.1, 31.10): assembles the tree of the published Swift package atlas-measurement-schema-swift. SwiftPM
# resolves a package from the repository root and a version tag, so the monorepo's swift/ folder is published to its own
# repository with Package.swift at the root. The tree holds the generated types (run swift/generate-types.sh first), the
# hand-written support files and the tolerance port; test targets are dropped (they read the monorepo's vectors).
# Usage: assemble-swift-package.sh <outDir>
set -euo pipefail
OUT="${1:?usage: assemble-swift-package.sh <outDir>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWIFT="$ROOT/swift"

if [ ! -f "$SWIFT/Sources/AtlasMeasurementSchema/Generated.swift" ]; then
  echo "swift/Sources/AtlasMeasurementSchema/Generated.swift is missing: run swift/generate-types.sh first" >&2
  exit 1
fi
mkdir -p "$OUT"
cp -R "$SWIFT/Sources" "$OUT/Sources"
grep -v 'testTarget' "$SWIFT/Package.swift" > "$OUT/Package.swift"
cat > "$OUT/README.md" <<'README'
# atlas-measurement-schema-swift

Published artifact of atlas-measurement-schema (AT-16): the Swift types generated from the measurement JSON Schema and the
tolerance function. Do not edit: this repository is written by the schema repository's release job, one tag per release
(`v<major>.<minor>.<patch>`, the same version as every other artifact). Add it by version:

```swift
.package(url: "https://github.com/pawangogia21/atlas-measurement-schema-swift", exact: "1.0.0")
```
README
echo "assembled the Swift package in $OUT"
