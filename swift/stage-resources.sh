#!/usr/bin/env bash
# AT-16 F9 (design 31.10, section 35.5): stages the files the Swift package bundles as SwiftPM resources, from the
# repository's single sources, so nothing is copied by hand and nothing staged is committed (git-ignored):
#   Sources/AtlasMeasurementTolerance/Resources/tolerance/  every released tolerance-profile.json (<version>/), the profile
#                                                           schema and manifest.json
#   Sources/AtlasMeasurementVectors/Resources/vectors/      conformance, boundary and negative vectors and manifest.json
# manifest.json carries the release version as the placeholder @project.version@; with ATLAS_RELEASE_VERSION set (the
# release assembly) it is replaced by that version. Run by generate-types.sh and by assemble-swift-package.sh.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
TOL="$HERE/Sources/AtlasMeasurementTolerance/Resources/tolerance"
VEC="$HERE/Sources/AtlasMeasurementVectors/Resources/vectors"

rm -rf "$HERE/Sources/AtlasMeasurementTolerance/Resources" "$HERE/Sources/AtlasMeasurementVectors/Resources"
mkdir -p "$TOL" "$VEC"
for dir in "$ROOT"/tolerance/*/; do
  [ -f "$dir/tolerance-profile.json" ] || continue
  mkdir -p "$TOL/$(basename "$dir")"
  cp "$dir/tolerance-profile.json" "$TOL/$(basename "$dir")/"
done
cp "$ROOT/tolerance/tolerance-profile.schema.json" "$TOL/"
# the vectors, without the READMEs (the same selection as the vector artifact)
(cd "$ROOT/vectors" && find . -type f ! -name README.md ! -name .DS_Store | LC_ALL=C sort | while read -r f; do
  mkdir -p "$VEC/$(dirname "$f")"
  cp "$f" "$VEC/$f"
done)
for manifest in "$TOL/manifest.json:$ROOT/tolerance/manifest.json" "$VEC/manifest.json:$ROOT/vectors/manifest.json"; do
  sed "s/@project.version@/${ATLAS_RELEASE_VERSION:-@project.version@}/" "${manifest#*:}" > "${manifest%%:*}"
done
echo "staged the SwiftPM resources (version ${ATLAS_RELEASE_VERSION:-placeholder})"
