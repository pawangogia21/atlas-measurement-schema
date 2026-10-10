# Client-capture Vectors

Vectors for the optional `clientCapture.worldOriginEpoch` (schema package 1.1.0, design 36.8): an integer 0..2147483647, the `worldOriginEpoch` of the AR world frame in which the uploaded scan geometry is expressed. A new set rather than an edit of `negative/1.0.0`, because released sets are immutable.

## Layout

`<version>/` is immutable once released (design 31.4).

- `manifest.json`: `vectorSet`, `version`, `vectors[]` and `files[]` with `path`, `sha256` and `bytes`.
- One file per vector, each a complete `clientCapture` object (kind `clientCapture`). `expectedValid` is true when the intake accepts it; otherwise it answers 422 `CLIENT_MEASUREMENT_INVALID` with `expectedErrorContains` in the errors.

| Vector | Expected |
|---|---|
| `epoch-absent` | valid (the field is optional) |
| `epoch-0`, `epoch-2147483647` | valid (both bounds) |
| `epoch-negative` (-1) | invalid, `minimum` |
| `epoch-2147483648` | invalid, `maximum` |
| `epoch-fractional` (1.5), `epoch-string` ("7") | invalid, `type` |

Only the Java reference replays this set today (`ReleasedVectorsTest`); ports validate through the generated types.

## Regenerate (reproducible, byte-identical)

```
./mvnw -f reference/pom.xml -B compile
java -cp "reference/target/classes:<jackson jars>" com.atlas.measurement.vectorgen.ClientCaptureEpochVectorGenerator vectors/client-capture/<version>
```
