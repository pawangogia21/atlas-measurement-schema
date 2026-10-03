# JSON Schema: client measurement contract

The client contract of the Measure Kits (design 17.3, 25.2, 25.3). Draft 2020-12, one folder per schema major (`v1/`, schema version `1.0`). Metres, 1-sigma, Y-up right-handed (`docs/conventions.md`).

| File | Validates |
|---|---|
| `v1/live-measurement.schema.json` | One `LiveMeasurement` (an item of `clientMeasurements` or of a batch) |
| `v1/client-capture.schema.json` | The `clientCapture` object of `POST /api/v1/models/uploads` |
| `v1/client-measurements-batch.schema.json` | The body of `POST /api/v1/client-measurements:batch` (1 to 100 items) |
| `v1/examples/` | Valid examples; the base for the negative vectors |

## Rules the schema enforces

- Unknown fields are rejected everywhere (`additionalProperties: false`).
- `overlay.keypoints` has at most 32 items; `qualityFlags` at most 8, unique.
- `trust`, when present, must be `UNVERIFIED_ESTIMATE` (the server stamps it on every client record).
- `dimensions` must match `mode`: `OBJECT_BOX` has `lengthM`, `widthM`, `heightM`; the other modes have `distanceM`.
- `confidence` is at most 0.6 when `scaleSource` is `VIO_METRIC`.
- Optional `geometry` object carries `endpointsWorld` (for `POINT_TO_POINT`) and `plane` (for `PLANE_DISTANCE`); normals must be unit-length (1.0 ± 1e-5 m).
- `value`, `sigmaM` and `ci95M` are bounded (within approximately ±10000 m); `algorithmVersion` major/minor/patch fit INT range.
- Numeric values must be finite (not NaN or Infinity).

## Rules that are intake rules, not schema rules

JSON Schema cannot express them; the intake service (AT-17) applies them with the same helper.

- **JSON depth at most 8.** Depth counts containers; the root object is depth 1. It is enforced while parsing, before schema validation, so a deeply nested body is never materialised (`com.atlas.measurement.validation.ClientMeasurementValidator`, Jackson `maxNestingDepth`). **Batch exception:** the depth limit applies per `LiveMeasurement` item in a batch (so one item at depth 9 causes the whole request to return HTTP 422 for that item), not to the batch envelope itself. A body deeper than 32, over size, or with too many items rejects the whole request.
- An unsupported `schemaVersion` (anything but `1.0`) is `CLIENT_SCHEMA_UNSUPPORTED`; every other violation is `CLIENT_MEASUREMENT_INVALID`.
- Size caps (4 KB per item, 256 KiB per `complete` body, 400 KiB per batch body, 50 items in `complete`). Whole-request limits: batch size 1-100 items, request body 400 KiB. String fields capped at 4096 chars, `algorithmVersion` and `kitVersion` at 32 chars, field names at 128 chars.
- Duplicate object keys and trailing JSON content are rejected; all numeric values must be finite.
- Timestamp windows: hint timestamp at most 5 minutes in the future and no older than 24 hours before the `complete` request. Batch timestamps at most 5 minutes in the future and at most 30 days old (offline sync).
- `sessionId` equality with the create request (for `complete`); idempotency by `(tenantId, ownerId, clientMeasurementId)` for the batch endpoint.
- On the `complete` endpoint, invalid hints are dropped and the upload succeeds with status 202 and `warnings: ["CLIENT_HINTS_DROPPED"]`; the upload itself is **never rejected for bad hints**.

## Generated Java types (`codegen-json/`, AT-16)

`codegen-json/pom.xml` wires `jsonschema2pojo` (1.2.1) to generate Java types from `v1/live-measurement.schema.json` and `v1/client-capture.schema.json` into a library jar (`com.atlas:atlas-measurement-schema-types`, JVM 11) with no domain logic — the intake service and the `kotlin/` artifact depend on it.

### Strictness with `StrictMapper.create()`

The generated types carry no validation of their own beyond the mapper's strictness settings. Always deserialize with `StrictMapper.create()`, which enforces:
- Unknown properties fail (matching the schema's `additionalProperties: false`)
- Unknown enum values fail (rather than being silently set to `null`)
- JSON nesting depth ≤ 8 (enforced during parsing via Jackson's `maxNestingDepth`)

Ranges, patterns, field conditionals (the `dimensions` → `mode` rule), and the VIO_METRIC confidence cap are all enforced by JSON Schema validation, not by the generated code. The intake service applies them with `ClientMeasurementValidator` (the reference implementation, in `reference/`).

### Per-item batch validation

For a batch request, call `ClientMeasurementValidator.validateBatchItems(jsonString)` to return per-item validation results with codes and errors:

```java
public List<Result> validateBatchItems(String json) { ... }
// Result: code (VALID, CLIENT_MEASUREMENT_INVALID, etc.), errors (list of violation strings)
```

The envelope and per-item parsing are handled in one call; the HTTP 200 response body and per-item result codes are implementation-specific (reference implementation shape defined in `reference/`). See `vectors/negative/README.md` for negative test vectors.

### Swift types

Swift types are generated separately with quicktype (design 31.10) via `swift/generate-types.sh` and output to `swift/Sources/AtlasMeasurementSchema/Generated.swift` (git-ignored in this repo; published in the separate `atlas-measurement-schema-swift` repository). Note: Swift's `Codable` silently ignores unknown fields, so if the server adds a field to the schema and an older Kit tries to decode it, the new field is discarded. The Kits are responsible for testing both forward and backward compatibility in their own CI.

## Compatibility

Within a schema major only optional fields may be added (design 21.4). `$id` values use a placeholder host; resolution is by relative `$ref` and the validator maps the prefix to this folder.

The JSON Schema backward-compatibility gate (AT-16 sub-task L, `.github/scripts/json-schema-compat-gate.sh`) runs in CI on every push and pull request. It fails the build on:
- A change to an enum or const field (unless the key is listed in `json-schema/.enum-approvals`)
- A change to `allOf`, `anyOf`, `oneOf`, `if`/`then`/`else`, or `not` keywords

Adding an optional property (with no `required` entry) needs no approval. Removing a property, adding a required property, changing a type, or tightening bounds cannot be approved here — those are major-version changes (v1 → v2).
