#!/usr/bin/env bash
# Generates the Swift Codable types from the JSON Schema (AT-16, design 31.10) with quicktype, pinned.
# Output: swift/Sources/AtlasMeasurementSchema/Generated.swift. It is git-ignored in this repo (the JSON Schema is the
# source of truth); the published atlas-measurement-schema-swift repository carries it (see .github/scripts/
# assemble-swift-package.sh) so Kits pull the package by version and never generate or commit anything.
# Needs node/npx (quicktype is fetched on demand).
set -euo pipefail

QUICKTYPE_VERSION="23.0.170"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMAS="$HERE/../json-schema/v1"
OUT="$HERE/Sources/AtlasMeasurementSchema/Generated.swift"

# The batch request schema is not an input: quicktype cannot share the LiveMeasurement types across two entry
# schemas and would emit a duplicate set, so ClientMeasurementsBatchRequest is the one hand-written type
# (Sources/AtlasMeasurementSchema/ClientMeasurementsBatchRequest.swift), checked against its schema example in tests.
npx --yes "quicktype@$QUICKTYPE_VERSION" --src-lang schema --lang swift --access-level public --no-initializers \
  --src "$SCHEMAS/live-measurement.schema.json" --src "$SCHEMAS/client-capture.schema.json" -o "$OUT"
echo "generated $OUT"
# the bundled profiles and vectors (SwiftPM resources) are staged from the repository files in the same step
"$HERE/stage-resources.sh"
