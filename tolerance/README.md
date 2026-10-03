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
