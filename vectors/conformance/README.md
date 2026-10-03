# Conformance Vectors

Cross-platform numerical-parity vectors for tier A and the shared math of `algorithm-spec.md` (design 24 item 5): analytic boxes and planes rendered to depth with fixed seeds, noise-free and seeded-noisy variants and degenerate cases. Tolerances are per field in the manifest: 1 mm (0.002 rad) noise-free, 3 mm (0.006 rad) noisy, exact for counts and enums. Adapters are excluded.

Both Kit cores must reproduce them on macOS (Accelerate), Linux (scalar) and JVM (verified in AT-19 and AT-21; AT-16 verifies the Java reference).

## Layout

`<version>/` is immutable once released (a change is a new version, never an edit; design 31.4).

- `manifest.json`: vector list with per-field tolerances, and `files[]` with `path`, `sha256` and `bytes` for every other file.
- `primitives.json`: SplitMix64, window seeds, bounded integers, lower median, Kahan sum, Jacobi 3x3.
- `scenes/<id>/`: `input.json` (grid size, depth-grid intrinsics, row-major camera-to-world pose, `windowIndex`), `depth.f32` (Float32 little-endian, row-major, metres, NaN invalid), optional `confidence.f32`, `expected.json` (compared fields plus an informational analytic `truth`).

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.ConformanceVectorGenerator vectors/conformance/<version>
```

`ConformanceVectorsTest` regenerates into a temp directory and compares every file with the committed release, so the committed files cannot drift from the generator or from the reference pipeline.
