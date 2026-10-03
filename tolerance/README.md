# Tolerance profile and tolerance function

`tolerance-profile.json` holds versioned thresholds (`accuracyGate`, `accuracyGateP95`, `agree`, `minor`, with per-`algorithmMajor` overrides) for the one tolerance function behind the Kit accuracy gates and the AGREE / MINOR_DIFF / MAJOR_DIFF classification (design 21.2). `NOT_COMPARABLE` comes from the association rule, not from these thresholds. `tolerance-profile.schema.json` validates the file (the Java test suite does it).

A released profile version is immutable: a retune publishes `1.1.0`, never edits `1.0.0`. Every comparison stores the `tolerance_profile_version` it used.

## Function (normative)

```
tol(ref, sigmaServer, sigmaClient, row) =
    max(floorM, relFrac * |ref|, min(sigmaK * combinedSigma, sigmaCapM))
combinedSigma = sqrt(sigmaServer^2 + sigmaClient^2)
```

- `sigmaK = null`: the sigma term is omitted (the sigma inputs are ignored). `sigmaCapM = null` with a non-null `sigmaK`: the sigma term is uncapped.
- Sigma inputs must be finite and >= 0, `ref` finite; otherwise the call is invalid (an error, never a silent result).
- Comparison (`withinTolerance(diff, tol)`): both values are converted to integer units of 1e-5 m with `floor(x * 1e5 + 0.5)` evaluated in IEEE-754 double, then compared with `<=`. All three languages use this exact expression.
- Classification of an associated pair (`classify`): per dimension, `diff = |client - server|`, `ref = server value`, `sigmaServer`/`sigmaClient` of that dimension. `AGREE` if every dimension is within `agree`; else `MINOR_DIFF` if every dimension is within `minor`; else `MAJOR_DIFF`.

## Overrides (normative)

`overrides` maps an `algorithmMajor` (decimal string, the major of the client hint's `algorithmVersion` for comparisons, of the Kit's own version for the accuracy gates) to a map of row name to a partial row. Resolution of the effective row for `(rowName, algorithmMajor)`: start with the base row; if `overrides[algorithmMajor][rowName]` exists, each field present in it replaces the base field (including `null` for `sigmaK` / `sigmaCapM`); absent fields and absent majors inherit the base. There is no fallback to a nearer major. `1.0.0` ships with `"overrides": {}`.

## Implementations

One implementation per language, each reading `tolerance-profile.json` (no constants in code) and run against the same vectors (design 21.2, D2b in `AT-16`):

- Java reference: `reference/` (`com.atlas.measurement.tolerance`)
- Swift: `swift/`
- Kotlin (JVM 11): `kotlin/`

`smoke-cases.json` holds a few hand-computed cases that all three test suites replay. The release boundary vectors (exactly, just under, just over every threshold) are delivered separately under `vectors/boundary/`.

## Association Rule (AT-16, design 21.2)

Server-side measurement association and NOT_COMPARABLE reason codes are defined by the reference implementation (`com.atlas.measurement.association.Association` in `reference/`). The rule is normative; Swift and Kotlin do not replay association vectors (the association is server-side only).

### Decision inputs

**Per hint:** the hint's own `sessionId` and `worldOriginEpoch` (for frame mapping), `schemaVersion` (for support check), claimed `depthTier`, `mode`, dimensions, and geometric data (box / endpoints / plane).

**Per server measurement:** its `id`, `mode`, dimensions, geometric data, and optionally a `seedClientMeasurementId` (for seeded matching, which names the hint that seeded this server measurement).

**Per model:** whether the model is deleted, the server measurement state (READY, PENDING, FAILED), and the server-derived `depthTier` (used only for `tierMismatch` analytics, never for the `TIER_C` reason code).

### Reason precedence (first match)

1. `MODEL_DELETED` — the model is deleted.
2. `SCHEMA_UNSUPPORTED` — the hint's schema version is not supported.
3. `TIER_C` — the hint's claimed depth tier is C (lowest; future tiers may be defined).
4. `SERVER_FAILED` — the server measurement state is FAILED.
5. `PENDING` — the server state is PENDING (outcome, not a reason; the hint can retry).
6. Per-hint `MODE_MISMATCH` — no server measurements exist in the hint's mode.
7. `NO_ASSOCIATION` — no suitable server measurement found (seeded, geometric and dimension-only branches exhausted).

### Association branches

With one or more servers in the hint's mode, the rule tries (in order):

1. **SEEDED**: a server measurement with a `seedClientMeasurementId` that matches this hint's `clientMeasurementId` — associated by construction.
2. **GEOMETRIC** (if frame mapping is available): match by geometric similarity (IoU ≥ 0.5, endpoint distance ≤ 0.10 m, normal angle ≤ 5 degrees, plane offset ≤ 0.05 m). Assignment is one-to-one greedy by descending score.
3. **DIMENSION_ONLY** (if frame mapping is not available): match by per-dimension tolerance (footprint dimensions sorted ascending with height separate). Qualifies only if exactly one server measurement matches within the `minor` tolerance. If zero or more than one candidates qualify, the hint gets `NO_ASSOCIATION`. Both hints that are dimension-only paired to the same single server get `NO_ASSOCIATION`.

### Geometric thresholds (provisional)

IoU 0.5, endpoint distance 0.10 m, normal angle 5 degrees, plane offset 0.05 m. These are provisional like the tolerance profile and a change is a new vector release. Geometry comparisons use the same 1e-5 m rounding as the tolerance function (`com.atlas.measurement.tolerance.Tolerance.units(double)`).

### Shared server measurement (geometric branch only)

In the **GEOMETRIC branch** (frame mapping available), if a server measurement is matched to one hint, it is taken and unavailable for other hints (one-to-one greedy assignment). The result for other hints that would match it is `NO_ASSOCIATION`. **In the DIMENSION_ONLY branch**, each hint is tested independently, and two hints that both qualify for the same single server both get `NO_ASSOCIATION` (to prevent a mis-association from creating a false `MAJOR_DIFF`).
