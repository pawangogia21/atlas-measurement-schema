# Boundary Vectors

Boundary vectors verify the tolerance function's accuracy (design 21.2) and the association rule by asserting that every threshold in `tolerance-profile.json` produces correct classification at exactly the threshold value, just below it (-1e-5 m) and just over it (+1e-5 m).

## Layout

`<version>/` is immutable once released (a change is a new version, never an edit; design 31.4).

- `manifest.json`: vector list with `vectorSet`, `version`, `toleranceProfileVersion`, and `files[]` with `path`, `sha256` and `bytes` for every other file.
- `boundary-cases.json`: threshold vectors (one per tolerance-profile row and tolerance-function term) tested for classification at exactly, just under and just over the threshold; rounding edges; and per-dimension classification.
- `association-cases.json`: association and NOT_COMPARABLE vectors (one per association branch: SEEDED, IOU, GEOMETRIC, DIMENSION_ONLY; and per reason code), with geometric thresholds at exactly, just under and just over. The association is server-side; only the Java reference implementation replays this file (design 21.2, 31.3).

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.BoundaryVectorGenerator vectors/boundary/<version>
```

`ReleasedVectorsTest` regenerates both boundary and negative vectors into temp directories and compares every file with the committed release, so the committed files cannot drift from the generators or from the reference pipeline. The test also verifies that every manifest entry's sha256 hash and byte count match the actual files on disk.
