# Negative Vectors

Payloads that the intake service must reject with HTTP 422 `CLIENT_MEASUREMENT_INVALID`, plus per-item batch error reporting (kind `batchItems`: a valid envelope answered per item). Each of the 105 vectors is a mutation of a valid example from `json-schema/v1/examples/`.

## Layout

`<version>/` is immutable once released (a change is a new version, never an edit; design 31.4).

- `manifest.json`: the vector set metadata with `vectorSet`, `version`, a description, `vectors[]` with per-vector details, and `files[]` with `path`, `sha256` and `bytes` for every JSON file.
- 105 JSON files: payloads organized by kind:
  - `liveMeasurement` (single item): missing/malformed/invalid required fields (timestamp, capture, mode, dimensions, etc.), depth limits (9 and 100001 levels), invalid enums and field values
  - `clientCapture` (the capture object): missing/invalid required fields and enums
  - `batch` (whole request refused): invalid or unknown envelope field, too many items (101), empty batch, body over 400 KiB (409600 characters), body deeper than the transport ceiling of 32 (`batch-body-depth-33`)
  - `batchItems` (per-item validation, `expectedItems` lists the code of each item): `batch-per-item-rejection`, `batch-item-depth-9`, `batch-item-schema-version-unsupported`

## Error tokens and depth enforcement

Every vector except kind `batchItems` expects `expectedStatus` 422, `expectedCode` `CLIENT_MEASUREMENT_INVALID` and an `expectedErrorContains` token that must appear in one of the returned error strings. Error strings are `<keyword> at <instance path>` (plus `: <property>` for a missing required property) or a fixed text, never client text. Tokens in use: a schema keyword (`additionalProperties`, `maximum`, `unitLength`, `finiteNumber`), a property name or path part (`schemaVersion`, `depthTier`, `items`) or a fixed text (`JSON object`, `duplicate object key`, `trailing content`, `not valid JSON`, `JSON depth above 8`, `JSON depth above 32`, `document larger than`, `string value longer than`, `number longer than`, `name longer than`, `batch envelope`). The `batchItems` vectors carry `expectedItems` instead (see below).

- **JSON depth limit 8** (depth counts containers; root is depth 1): enforced while parsing before schema validation via Jackson's `maxNestingDepth`, so a deeply nested body is never materialised. A single document (`depth-9.json`, `depth-100001.json`, which a consumer must refuse without exhausting its stack) is refused as a whole with `JSON depth above 8`.
- **Batch exception:** a batch body is parsed with a transport ceiling of 32. An item nested deeper than 8 but within 32 is INVALID on its own: `batch-item-depth-9.json` is a `batchItems` vector with `expectedItems` `["VALID", "CLIENT_MEASUREMENT_INVALID", "VALID"]`, and the other items are processed. Only a body deeper than 32 (`batch-body-depth-33.json`, kind `batch`, `JSON depth above 32`), a body over 400 KiB, or a bad envelope or item count rejects the whole request with HTTP 422.

## Per-item batch validation (`ClientMeasurementValidator.validateBatchItems`)

A batch whose envelope is `{"items": [1..100 objects]}` is processed item by item: each item is `VALID`, `CLIENT_MEASUREMENT_INVALID` or `CLIENT_SCHEMA_UNSUPPORTED` (an unsupported `schemaVersion`; the Kit retries after an update), and a bad item does not fail the batch. The reference API returns a `BatchResult` with `request` and per-item `Result`s (`code`, `errors`); the vectors state only the expected codes, in order, in `expectedItems`. Test cases: `batch-per-item-rejection.json` (items 2 and 4 invalid), `batch-item-depth-9.json`, `batch-item-schema-version-unsupported.json`. Whole-request refusals (kind `batch`): `batch-empty.json`, `batch-101-items.json`, `batch-unknown-envelope-field.json`, `batch-over-400k-characters.json`, `batch-body-depth-33.json`.

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.NegativeVectorGenerator vectors/negative/<version>
```

`ReleasedVectorsTest` regenerates both boundary and negative vectors into temp directories and compares every file with the committed release, so the committed files cannot drift from the generators or from the reference pipeline.
