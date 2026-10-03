#!/usr/bin/env bash
# Generates the Swift Codable types from the JSON Schema (AT-16, design 31.10) with quicktype, pinned by swift/quicktype/
# package-lock.json (exact version, integrity hash for every transitive dependency; installed with npm ci --ignore-scripts).
# Output: swift/Sources/AtlasMeasurementSchema/Generated.swift. It is git-ignored in this repo (the JSON Schema is the
# source of truth); the published atlas-measurement-schema-swift repository carries it (see .github/scripts/
# assemble-swift-package.sh) so Kits pull the package by version and never generate or commit anything.
# Needs node/npm (the locked quicktype is installed into swift/quicktype/node_modules, git-ignored).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMAS="$HERE/../json-schema/v1"
OUT="$HERE/Sources/AtlasMeasurementSchema/Generated.swift"
TOOL="$HERE/quicktype"

# The batch request schema is not an input: quicktype cannot share the LiveMeasurement types across two entry
# schemas and would emit a duplicate set, so ClientMeasurementsBatchRequest is the one hand-written type
# (Sources/AtlasMeasurementSchema/ClientMeasurementsBatchRequest.swift), checked against its schema example in tests.
(cd "$TOOL" && npm ci --ignore-scripts --no-audit --no-fund --loglevel=error)
"$TOOL/node_modules/.bin/quicktype" --src-lang schema --lang swift --access-level public --no-initializers \
  --src "$SCHEMAS/live-measurement.schema.json" --src "$SCHEMAS/client-capture.schema.json" -o "$OUT"
echo "generated $OUT"
# the bundled profiles and vectors (SwiftPM resources) are staged from the repository files in the same step
"$HERE/stage-resources.sh"
