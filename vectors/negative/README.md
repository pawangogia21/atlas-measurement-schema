# Negative Vectors

Payloads that the intake service must reject with HTTP 422 `CLIENT_MEASUREMENT_INVALID`, plus per-item batch error reporting. Each of the 62 vectors is a mutation of a valid example from `json-schema/v1/examples/`.

## Layout

`<version>/` is immutable once released (a change is a new version, never an edit; design 31.4).

- `manifest.json`: the vector set metadata with `vectorSet`, `version`, a description, `vectors[]` with per-vector details, and `files[]` with `path`, `sha256` and `bytes` for every JSON file.
- 62 JSON files: payloads organized by kind:
  - `liveMeasurement` (single item): missing/malformed/invalid required fields (timestamp, capture, mode, dimensions, etc.), depth limits (9 and 100001 levels), invalid enums and field values
  - `clientCapture` (the capture object): missing/invalid required fields and enums
  - `batch` (whole request): invalid envelope, non-object items, batch-size violations, too many items (101), empty batch
  - `batchItems` (per-item validation): rejection reporting with per-item results

## Error tokens and depth enforcement

Every negative vector expects HTTP 422 with a `code` field; the `message` or top-level `errors` field contains the recorded error token (e.g. `"batch envelope must be {\"items\": [1..100 objects]}"`, or specific schema validation errors like `"required field timestamp is absent"`).

- **JSON depth limit 8** (depth counts containers; root is depth 1): enforced while parsing before schema validation via Jackson's `maxNestingDepth`, so a deeply nested body is never materialized. The limit applies per `LiveMeasurement` item in a batch, not to the batch envelope itself. Test cases: `depth-9.json` (single item just over limit) and `batch-item-depth-9.json` (batch with one item at depth 9).
- **Over-limit nesting**: `batch-item-depth-100001.json` tests stack-safe rejection of an item recursively nested 100001 levels deep — a consumer must refuse it without exhausting its stack.

## Per-item batch validation (`ClientMeasurementValidator.validateBatchItems`)

A batch request body with 1–100 valid `LiveMeasurement` items in an `items` array returns HTTP 200 with per-item results:

```json
[
  { "code": "VALID" },
  { "code": "CLIENT_MEASUREMENT_INVALID", "errors": [
    "violation 1",
    "violation 2"
  ]},
  { "code": "VALID" }
]
```

The batch envelope itself must be a JSON object with an `items` key and array; violations in the envelope (non-object items, batch size out of range, missing `items` key) return HTTP 422 with a single error in the response. Test cases: `batch-per-item-rejection.json` (valid envelope, mixed valid/invalid items), `batch-empty.json` (zero items), `batch-101-items.json` (101 items).

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.NegativeVectorGenerator vectors/negative/<version>
```

`ReleasedVectorsTest` regenerates both boundary and negative vectors into temp directories and compares every file with the committed release, so the committed files cannot drift from the generators or from the reference pipeline.
