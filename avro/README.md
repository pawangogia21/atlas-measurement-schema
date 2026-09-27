# Avro Schemas

Apache Avro schemas for the Atlas Kafka event contract (design section 6.1/6.2), namespace
`com.atlas.events.v1`.

## Files

- `CaptureSource.avsc` — shared enum, referenced by both `ModelUploaded` (as `claimedCaptureSource`)
  and `ModelProcessed` (as `derivedCaptureSource`). `UNKNOWN` is the first symbol and the default so
  new symbols stay backward compatible.
- `ModelUploaded.avsc` — `atlas.model.uploaded.v1` / `atlas.model.uploaded.v1-heavy`.
- `ModelProcessed.avsc` — `atlas.model.processed.v1`.
- `ModelFailed.avsc` — `atlas.model.failed.v1`. Defines its own `FailureStage` and `ReasonCode`
  enums (the reason codes list from design section 10.4).
- `ModelDeleted.avsc` — `atlas.model.deleted.v1` (delete saga start).
- `ModelPurged.avsc` — `atlas.model.purged.v1` (delete saga per-service confirmation). Defines its
  own `ConfirmingService` enum.

Every schema carries the common envelope fields on top of its own payload fields: `eventId`,
`eventType`, `occurredAt`, `modelId`, `tenantId`, `ownerId`, `traceId`, `schemaVersion` (design
section 6.2). The W3C `traceparent` travels separately in Kafka message headers (section 6.3); the
payload's `traceId` is for log/event correlation and survives independently of header handling.

`atlas.model.scale-updated.v1` and `atlas.measurement.computed.v1` are reserved for later services
(AT-2 out of scope); no schema is defined for them here.

## Naming and versioning

- Kafka topic naming: `<system>.<aggregate>.<event>.v<major>` (e.g. `atlas.model.uploaded.v1`).
- A **breaking** schema change gets a new major topic version (`v2`), never an in-place edit of
  `v1`. Additive, backward-compatible changes (a new optional field with a default) stay on `v1`.
- Adding a field: add it with a `default` value so old readers and old data both keep working; run
  `./mvnw -f codegen/pom.xml verify` locally to confirm it still compiles and round-trips, then open
  a PR — CI runs the compatibility gate automatically (see below).
- Changing an enum's symbols (adding, removing or renaming a symbol, on `CaptureSource` or any
  other enum here) is treated as a compatibility event, not a plain addition (design section 6.2):
  it fails CI unless the enum's fully qualified name is listed in `avro/.enum-approvals` (one name
  per line, `#` comments allowed) in the same PR. Reviewers see that file in the diff as the
  explicit approval marker.

## The compatibility gate (CI, AT-2 task 3)

`.github/workflows/ci.yml` runs `tools/schema-gate` (a small, unpublished CLI, see its own
`pom.xml`) against every push and pull request:

1. **Backward compatibility** — every `avro/*.avsc` is compared against the same file at the
   baseline commit (the PR's target branch, or the previous commit on a direct push to `main`)
   using Avro's own `SchemaCompatibility` reader/writer check. A change that an old reader could not
   safely apply to old data fails the build.
2. **Enum changes** — any enum whose symbol list differs from the baseline fails the build unless
   its name is listed in `avro/.enum-approvals`.

Run it locally before opening a PR:

```bash
./mvnw -f tools/schema-gate/pom.xml -B package
./.github/scripts/avro-compat-gate.sh origin/main
```

## Registration (CI/script only — never the services)

The schema registry (Apicurio, Confluent-compatible API) is write-restricted to CI (design section
6.1): services only *read* schemas by id/subject, they never auto-register. Locally, against
`atlas-platform`'s `docker compose --profile core up -d` (which starts Kafka, Postgres and the
`schema-registry` service, Apicurio on `${ATLAS_REGISTRY_PORT:-18085}`), register a schema with:

```bash
curl -X POST "http://localhost:${ATLAS_REGISTRY_PORT:-18085}/apis/ccompat/v7/subjects/atlas.model.uploaded.v1-value/versions" \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data-binary @- <<EOF
{"schema": $(python3 -c 'import json,sys; print(json.dumps(open("avro/ModelUploaded.avsc").read()))')}
EOF
```

The `KafkaAvroSerializer`/`KafkaAvroDeserializer` used by producers and consumers auto-register
schemas by default (`auto.register.schemas=true`) — services must explicitly set
`auto.register.schemas=false` so only CI/this script can write to the registry.

## Consuming the generated types

See the root `README.md`'s "Consuming the generated types" section.
