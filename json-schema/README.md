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
- Optional `geometry` object: `endpointsWorld` (two points, `POINT_TO_POINT` only) and `plane` (`normalWorld`, `offsetM`; `PLANE_DISTANCE` only). `OBJECT_BOX` derives its box from `obb` and carries no geometry. Without geometry the server falls back to the dimension-only association.
- Every metric has a maximum: `value`, `sigmaM`, `ci95M`, `halfExtentsM` and keypoint `sigma` are in 0..9999.99999 (the `NUMERIC(9,5)` columns); world coordinates and offsets are within +-100000. `algorithmVersion` is `MAJOR.MINOR.PATCH` with each component at most 9 digits (no leading zeros) and at most 16 characters; `kitVersion` at most 16.

## Rules that are intake rules, not schema rules

JSON Schema cannot express them; the intake service (AT-17) applies them with the same helper.

- **JSON depth at most 8.** Depth counts containers; the root object is depth 1. It is enforced while parsing, before schema validation, so a deeply nested body is never materialised (`com.atlas.measurement.validation.ClientMeasurementValidator`, Jackson `maxNestingDepth`). It applies per `LiveMeasurement` document. In a batch the body is parsed with a transport ceiling of 32: an item nested deeper than 8 but within 32 is `CLIENT_MEASUREMENT_INVALID` on its own (the other items are processed; vector `batch-item-depth-9`), and only a body deeper than 32 (`batch-body-depth-33`) rejects the whole request.
- An unsupported `schemaVersion` (anything but `1.0`) is `CLIENT_SCHEMA_UNSUPPORTED`; every other violation is `CLIENT_MEASUREMENT_INVALID`.
- Size limits, checked while parsing (characters, not bytes): a document at most 256 * 1024 (the `complete` hints body), a batch body at most 400 * 1024, strings at most 4096, numbers at most 32 characters, field names at most 128. At most 100 items in a batch (at least 1) and at most 50 in `complete`.
- Duplicate object keys, trailing content after the document and numbers outside the finite double range (1e999) are rejected.
- `geometry.plane.normalWorld` must have unit length within 1e-3 (`unitLength at $.geometry.plane.normalWorld`).
- **Whole-request rejection (HTTP 422) of a batch** happens only for transport reasons: not JSON, a body over the size limit, deeper than 32, a string or number over its limit, a duplicate key, trailing content, or a bad envelope (`{"items": [1..100 objects]}`, nothing else) or item count. Anything wrong inside an item is that item's result.
- Timestamp windows, `sessionId` equality with the create request and idempotency on `clientMeasurementId` are service rules of the intake (AT-17), not applied by the reference validator; no window length is specified here.
- On `uploads/complete`, `validateCompleteHints` never rejects the upload for bad hints: a body-level violation drops all hints, an item-level one only that item, and the response is 202 `VALIDATING` with `warnings: ["CLIENT_HINTS_DROPPED"]`.
- Error strings never echo client text: `<keyword> at <instance path>` (e.g. `additionalProperties at $`, `maximum at $.dimensions.lengthM.value`, `unitLength at ...`), plus `: <property>` for a missing required property, or a fixed text (`duplicate object key`, `trailing content after the JSON document`, `finiteNumber: ...`, `JSON depth above 8`, `document larger than ... characters`).

## Generated Java types (`codegen-json/`, AT-16)

`codegen-json/pom.xml` wires `jsonschema2pojo` (1.2.1) to generate Java types from `v1/live-measurement.schema.json` and `v1/client-capture.schema.json` into a library jar (`com.atlas:atlas-measurement-schema-types`, JVM 11) with no domain logic — the intake service and the `kotlin/` artifact depend on it.

### Strictness with `StrictMapper.create()`

The generated types carry no validation of their own beyond the mapper's strictness settings. Always deserialize with `StrictMapper.create()`, which enforces:
- Unknown properties fail (matching the schema's `additionalProperties: false`)
- Unknown enum values fail (rather than being silently set to `null`)
- JSON nesting depth ≤ 8, duplicate keys, trailing content, non-finite numbers and the intake size limits (enforced during parsing)

Ranges, patterns, field conditionals (the `dimensions` → `mode` rule), and the VIO_METRIC confidence cap are all enforced by JSON Schema validation, not by the generated code. The intake service applies them with `ClientMeasurementValidator` (the reference implementation, in `reference/`).

### Per-item batch validation

For a batch request, call `ClientMeasurementValidator.validateBatchItems(jsonString)`. It returns a `BatchResult`: `request` (invalid when the whole request is refused, then `items` is empty) and, otherwise, `items` with one `Result` per item (`code`, null when valid, `CLIENT_MEASUREMENT_INVALID` or `CLIENT_SCHEMA_UNSUPPORTED`, and `errors`). `validateBatch` gives the all-or-nothing view, and `validateCompleteHints` the `complete` variant (`HintsResult`, with `warnings()`). See `vectors/negative/README.md` for the vectors.

### Swift types

Swift types are generated separately with quicktype (design 31.10) via `swift/generate-types.sh` and output to `swift/Sources/AtlasMeasurementSchema/Generated.swift` (git-ignored in this repo; published in the separate `atlas-measurement-schema-swift` repository). Note: Swift's `Codable` silently ignores unknown fields, so if the server adds a field to the schema and an older Kit tries to decode it, the new field is discarded. The Kits are responsible for testing both forward and backward compatibility in their own CI.

## Compatibility

Within a schema major only optional fields may be added (design 21.4). `$id` values use a placeholder host; resolution is by relative `$ref` and the validator maps the prefix to this folder.

The JSON Schema backward-compatibility gate (AT-16 sub-task L, `.github/scripts/json-schema-compat-gate.sh`) runs in CI on every push and pull request. It fails the build on:
- A change to an enum or const field (unless the key is listed in `json-schema/.enum-approvals`)
- A change to `allOf`, `anyOf`, `oneOf`, `if`/`then`/`else`, or `not` keywords

Adding an optional property (with no `required` entry) needs no approval. Removing a property, adding a required property, changing a type, or tightening bounds cannot be approved here — those are major-version changes (v1 → v2).
