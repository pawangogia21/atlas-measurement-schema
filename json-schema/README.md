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

## Rules that are intake rules, not schema rules

JSON Schema cannot express them; the intake service (AT-17) applies them with the same helper.

- **JSON depth at most 8.** Depth counts containers; the root object is depth 1. It is enforced while parsing, before schema validation, so a deeply nested body is never materialised (`com.atlas.measurement.validation.ClientMeasurementValidator`, Jackson `maxNestingDepth`). It applies per `LiveMeasurement` item, not to the batch envelope.
- An unsupported `schemaVersion` (anything but `1.0`) is `CLIENT_SCHEMA_UNSUPPORTED`; every other violation is `CLIENT_MEASUREMENT_INVALID`.
- Size caps (4 KB per item, 256 KB per `complete` body, 400 KB per batch, 50 items in `complete`), timestamp windows and `sessionId` equality with the create request.

## Compatibility

Within a schema major only optional fields may be added (design 21.4). `$id` values use a placeholder host; resolution is by relative `$ref` and the validator maps the prefix to this folder.
