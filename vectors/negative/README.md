# Negative Vectors

Payloads that the intake service must reject with HTTP 422 `CLIENT_MEASUREMENT_INVALID`, plus per-item batch error reporting (kind `batchItems`: a valid envelope answered per item) and the `uploads/complete` hint-dropping cases (kind `hintsComplete`: the upload is never rejected). Each of the 118 vectors is a mutation of a valid example from `json-schema/v1/examples/`.

## Layout

`<version>/` is immutable once released (a change is a new version, never an edit; design 31.4).

- `manifest.json`: the vector set metadata with `vectorSet`, `version`, a description, `vectors[]` with per-vector details, and `files[]` with `path`, `sha256` and `bytes` for every JSON file.
- 118 JSON files: payloads organized by kind:
  - `liveMeasurement` (single item): missing/malformed/invalid required fields (timestamp, capture, mode, dimensions, etc.), depth limits (9 and 100001 levels), invalid enums and field values
  - `clientCapture` (the capture object): missing/invalid required fields and enums
  - `batch` (whole request refused): invalid or unknown envelope field, too many items (101), empty batch, body over 400 KiB (409600 characters), body deeper than the transport ceiling of 32 (`batch-body-depth-33`)
  - `batchItems` (per-item validation, `expectedItems` lists the code of each item): `batch-per-item-rejection`, `batch-item-depth-9`, `batch-item-schema-version-unsupported`
  - `hintsComplete` (the `clientMeasurements` array of `uploads/complete`, see `validateCompleteHints`): `hints-complete-*`, 12 vectors, see below

## Error tokens and depth enforcement

Every vector except kind `batchItems` expects `expectedStatus` 422, `expectedCode` `CLIENT_MEASUREMENT_INVALID` and an `expectedErrorContains` token that must appear in one of the returned error strings. Error strings are `<keyword> at <instance path>` (plus `: <property>` for a missing required property) or a fixed text, never client text. Tokens in use: a schema keyword (`additionalProperties`, `maximum`, `unitLength`, `finiteNumber`), a property name or path part (`schemaVersion`, `depthTier`, `items`) or a fixed text (`JSON object`, `duplicate object key`, `trailing content`, `not valid JSON`, `JSON depth above 8`, `JSON depth above 32`, `document larger than`, `string value longer than`, `number longer than`, `name longer than`, `batch envelope`). The `batchItems` and `hintsComplete` vectors carry `expectedItems` and `expectedItemErrorContains` instead (see below).

- **JSON depth limit 8** (depth counts containers; root is depth 1): enforced while parsing before schema validation via Jackson's `maxNestingDepth`, so a deeply nested body is never materialised. A single document (`depth-9.json`, `depth-100001.json`, which a consumer must refuse without exhausting its stack) is refused as a whole with `JSON depth above 8`.
- **Batch exception:** a batch body is parsed with a transport ceiling of 32. An item nested deeper than 8 but within 32 is INVALID on its own: `batch-item-depth-9.json` is a `batchItems` vector with `expectedItems` `["VALID", "CLIENT_MEASUREMENT_INVALID", "VALID"]`, and the other items are processed. Only a body deeper than 32 (`batch-body-depth-33.json`, kind `batch`, `JSON depth above 32`), a body over 400 KiB, or a bad envelope or item count rejects the whole request with HTTP 422.

## Per-item batch validation (`ClientMeasurementValidator.validateBatchItems`)

A batch whose envelope is `{"items": [1..100 objects]}` is processed item by item: each item is `VALID`, `CLIENT_MEASUREMENT_INVALID` or `CLIENT_SCHEMA_UNSUPPORTED` (an unsupported `schemaVersion`; the Kit retries after an update), and a bad item does not fail the batch. The reference API returns a `BatchResult` with `request` and per-item `Result`s (`code`, `errors`); the vectors state the expected codes, in order, in `expectedItems` and, in `expectedItemErrorContains`, the text that each invalid item's errors must contain (`""` for a valid item). The token is what keeps an item rejected for the stated reason: a schema-valid item has at most depth 5, so an item nested deeper than 8 is also invalid through `additionalProperties` and the code alone cannot tell the two apart; `batch-item-depth-9.json` expects `["", "JSON depth above 8", ""]`, so a consumer without the per-item depth rule fails it. Test cases: `batch-per-item-rejection.json` (items 2 and 4 invalid), `batch-item-depth-9.json`, `batch-item-schema-version-unsupported.json`. Whole-request refusals (kind `batch`): `batch-empty.json`, `batch-101-items.json`, `batch-unknown-envelope-field.json`, `batch-over-400k-characters.json`, `batch-body-depth-33.json`.

## `uploads/complete` hints (`ClientMeasurementValidator.validateCompleteHints`)

`clientMeasurements` of `uploads/complete` (an array of at most 50 objects) never rejects the upload: a body-level violation drops all hints, an item-level one drops only that item, and either way the response is `202 VALIDATING` with the warning `CLIENT_HINTS_DROPPED` (nothing dropped: no warning). A `hintsComplete` vector file holds the array text; the manifest entry states `expectedUploadRejected` (always `false`), `expectedAllHintsDropped`, `expectedRequestErrorContains` (the body-level error text, only when all hints are dropped), `expectedItems` (empty when all hints are dropped), `expectedItemErrorContains` and `expectedWarnings`. Vectors: `hints-complete-all-valid`, `-empty-array`, `-item-dropped`, `-item-schema-unsupported`, `-item-depth-9`, `-item-not-an-object`, `-50-items` (the limit, accepted), `-51-items`, `-not-an-array`, `-body-depth-33`, `-duplicate-key`, `-not-json`.

## Boundary of the unit-length plane normal

`geometry.plane.normalWorld` must have unit length within 1e-3 (checked at intake): `plane-normal-length-0.9989.json` is rejected (`unitLength`), `json-schema/v1/examples/live-measurement-plane-normal-0.9991.json` (length 0.9991) is valid.

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.NegativeVectorGenerator vectors/negative/<version>
```

`ReleasedVectorsTest` regenerates both boundary and negative vectors into temp directories and compares every file with the committed release, so the committed files cannot drift from the generators or from the reference pipeline.
