# Tolerance profile and tolerance function

`tolerance/<version>/tolerance-profile.json` (one folder per released version, e.g. `tolerance/1.0.0/`; `tolerance/manifest.json` lists the versions and the sha256 of every file) holds versioned thresholds (`accuracyGate`, `accuracyGateP95`, `agree`, `minor`, with per-`algorithmMajor` overrides) for the one tolerance function behind the Kit accuracy gates and the AGREE / MINOR_DIFF / MAJOR_DIFF classification (design 21.2). `NOT_COMPARABLE` comes from the association rule, not from these thresholds. `tolerance-profile.schema.json` (in `tolerance/`) validates every profile file (the Java test suite does it).

A released profile version is immutable: a retune publishes `1.1.0`, never edits `1.0.0`. Every comparison stores the `tolerance_profile_version` it used.

## Function (normative)

```
tol(ref, sigmaServer, sigmaClient, row) =
    max(floorM, relFrac * |ref|, min(sigmaK * combinedSigma, sigmaCapM))
combinedSigma = sqrt(sigmaServer^2 + sigmaClient^2)
```

- `sigmaK = null`: the sigma term is omitted (the sigma inputs are ignored). `sigmaCapM = null` with a non-null `sigmaK`: the sigma term is uncapped.
- Sigma inputs must be finite and >= 0, `ref` finite and at most 1e6 m in magnitude; otherwise the call is invalid (an error, never a silent result). The same applies to `units(x)`: a non-finite `x` or `|x|` above 1e6 m is an error (Java and Kotlin throw `IllegalArgumentException`, Swift's `units` and `withinTolerance` are `throws`).
- Comparison (`withinTolerance(diff, tol)`): both values are converted to integer units of 1e-5 m with `floor(x * 1e5 + 0.5)` evaluated in IEEE-754 double, then compared with `<=`. All three languages use this exact expression.
- Classification of an associated pair (`classify`): per dimension, `diff = |client - server|`, `ref = server value`, `sigmaServer`/`sigmaClient` of that dimension. `AGREE` if every dimension is within `agree`; else `MINOR_DIFF` if every dimension is within `minor`; else `MAJOR_DIFF`.

## Overrides (normative)

`overrides` maps an `algorithmMajor` (decimal string, the major of the client hint's `algorithmVersion` for comparisons, of the Kit's own version for the accuracy gates) to a map of row name to a partial row. Resolution of the effective row for `(rowName, algorithmMajor)`: start with the base row; if `overrides[algorithmMajor][rowName]` exists, each field present in it replaces the base field (including `null` for `sigmaK` / `sigmaCapM`); absent fields and absent majors inherit the base. There is no fallback to a nearer major. `1.0.0` ships with `"overrides": {}`.

## Implementations

One implementation per language, each reading the tolerance profile (no constants in code; Kotlin and Swift read the released profiles bundled in their artifact, selected by version) and run against the same vectors (design 21.2, D2b in `AT-16`):

- Java reference: `reference/` (`com.atlas.measurement.tolerance`)
- Swift: `swift/`
- Kotlin (JVM 11): `kotlin/`

`smoke-cases.json` holds a few hand-computed cases that all three test suites replay. The release boundary vectors (exactly, just under, just over every threshold) are delivered separately under `vectors/boundary/`.

## Association Rule (AT-16, design 21.2)

Server-side measurement association and NOT_COMPARABLE reason codes are defined by the reference implementation (`com.atlas.measurement.association.Association` in `reference/`). The rule is normative; Swift and Kotlin do not replay association vectors (the association is server-side only).

### Decision inputs

**Per hint:** `id`, `mode`, dimensions (value and sigma), the hint's own `sessionId` and `worldOriginEpoch` (for the frame mapping), `schemaVersion`, the claimed `depthTier`, optional `supersedes`, and the geometry the schema carries (`obb` for OBJECT_BOX, `geometry.endpointsWorld`, `geometry.plane`).

**Per server measurement:** `id`, `mode`, dimensions, the same geometry fields, and optionally a `seedClientMeasurementId` (the `clientMeasurementId` of the hint that seeded it).

**Per model (context):** `modelDeleted`, `serverState` (READY, PENDING, FAILED), the scan's `sessionId`, `worldOriginEpoch` and whether a `transformToCanonical` exists, and the server-derived depth tier (analytics only, never used for `TIER_C`).

A hint superseded by another hint (some hint's `supersedes` names it) is excluded first and produces no result.

### Reason precedence (first match, per hint)

1. `MODEL_DELETED`: the model is deleted.
2. `SCHEMA_UNSUPPORTED`: the hint's schema version is not supported.
3. `TIER_C`: the hint's claimed depth tier is C.
4. `SERVER_FAILED`: the server measurement state is FAILED.
5. `PENDING`: the server state is PENDING. This is an outcome (`PENDING`), not a `NOT_COMPARABLE` reason code.
6. `MODE_MISMATCH`: server measurements exist but none in the hint's mode.
7. `NO_ASSOCIATION`: no server measurements exist, or no branch below produced an association.

### Association branches

For a hint with one or more servers in its mode, in order:

1. **SEEDED**: the server measurement whose `seedClientMeasurementId` is this hint's `clientMeasurementId` (same mode; lowest server id if several) is associated by construction, unless that server is already taken by another hint.
2. **GEOMETRIC** (IOU for OBJECT_BOX): for a hint that has a frame mapping (the scan has a transform to canonical and the hint's `sessionId` and `worldOriginEpoch` equal the scan's) and the geometry its mode needs (`obb`, `endpointsWorld` or `plane`). Thresholds: IoU >= 0.5, endpoint distance <= 0.10 m, normal angle <= 5 degrees, plane offset <= 0.05 m; a plane is sign-invariant (`n` with offset `o` and `-n` with `-o` are the same plane). Assignment is one-to-one, greedy by descending score (ties: lower server id, then lower hint id); a server taken by one hint is unavailable to the others.
3. **DIMENSION_ONLY**: every remaining hint, that is when the frame mapping or the hint's mode geometry is missing. It is decided per hint, independently of the other hints: the hint is associated when exactly one not-yet-taken server of its mode is not a `MAJOR_DIFF` against it (so the outcome is `AGREE` or `MINOR_DIFF`); with zero or several such servers the hint gets `NO_ASSOCIATION`. Two hints that qualify for the same single server are therefore both associated with it. OBJECT_BOX footprint dimensions (length, width) are sorted ascending on both sides before pairing, height stays separate, in every branch.

A hint that matches no branch gets `NO_ASSOCIATION`.

### Geometric thresholds (provisional)

IoU 0.5, endpoint distance 0.10 m, normal angle 5 degrees, plane offset 0.05 m. These are provisional like the tolerance profile and a change is a new vector release. Geometry comparisons use the same 1e-5 m rounding as the tolerance function (`com.atlas.measurement.tolerance.Tolerance.units(double)`). A non-finite coordinate or a zero-length plane normal is an error (an exception), never a silent match or mismatch.

### Shared server measurement

Only the one-to-one branches (SEEDED, GEOMETRIC) take a server: a server matched there is unavailable to other hints in those branches and in DIMENSION_ONLY, and the other hints that would match it get `NO_ASSOCIATION` or fall to the next branch. DIMENSION_ONLY itself never takes a server, so two dimension-only hints may share one.
