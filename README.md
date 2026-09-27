# Atlas Measurement Schema

Shared JSON Schema, Apache Avro schemas, conformance vectors and tolerance profiles for the Atlas 3D model platform.

This repository defines the schema contracts that are used across Atlas services for model metadata, event streaming, conformance assessment and measurement specifications.

## Layout

- **`json-schema/`** — JSON Schema definitions for model metadata and measurement specifications
- **`avro/`** — Apache Avro schemas for event streaming and Kafka topics
- **`vectors/conformance/`** — Cross-platform numerical-parity vectors for tier A algorithms and shared primitives
- **`vectors/boundary/`** — Boundary vectors for tolerance threshold testing
- **`tolerance/`** — Tolerance profile definitions and the unified tolerance function
- **`docs/`** — Algorithm specifications and design documentation
- **`codegen/`** — Maven module generating Java types from `avro/*.avsc` (AT-2); Kotlin and Swift
  generation are not yet wired (AT-16/later)
- **`tools/schema-gate/`** — CI-only tool enforcing the Avro backward-compatibility and
  enum-change gate (AT-2); never published, never a dependency of `codegen/` or the services

## Versioning

Schemas follow semantic versioning with topic-specific version markers:

- **Naming rule** — Kafka topics (and their Avro schemas) are named `<system>.<aggregate>.<event>.v<major>`,
  e.g. `atlas.model.uploaded.v1`.
- **Topic versioning** — A breaking schema change creates a new major topic version (`v2`), never an
  in-place edit of `v1`. Additive, backward-compatible changes (a new optional field with a default)
  stay on `v1`.
- **Backward compatibility** — Within a topic version, schemas must maintain backward compatibility
  (new fields are optional with defaults, deprecated fields are supported). Enforced in CI, see "The
  Avro compatibility gate" below.
- **Scope** — Version bumps apply per topic, not globally across all schemas.
- **Adding a field to an Avro event** — add it to the `.avsc` file in `avro/` with a `default`
  value, run `./mvnw -f codegen/pom.xml verify` to confirm the generated types still build and the
  unit tests round-trip, then open a PR; CI's compatibility gate checks it automatically.

## Avro event schemas (AT-2)

`avro/*.avsc` (namespace `com.atlas.events.v1`) defines the final v1 Kafka event contract:
`ModelUploaded`, `ModelProcessed`, `ModelFailed`, `ModelDeleted`, `ModelPurged`, plus the shared
`CaptureSource` enum. See `avro/README.md` for the full field list, the envelope convention, and
schema registration.

### Generated Java types (`codegen/`)

`codegen/pom.xml` wires the `avro-maven-plugin` to generate Java types from `avro/*.avsc` into a
plain jar with no domain logic (design section 3.3) — `atlas-ingestion-service` and
`atlas-processing-service` depend on it, they never hand-write these classes.

```bash
./mvnw -f codegen/pom.xml clean verify
```

### The Avro compatibility gate (CI, AT-2 task 3)

`.github/workflows/ci.yml` runs `tools/schema-gate` on every push and pull request: it fails the
build on a backward-incompatible `.avsc` change, and separately fails any enum symbol change
(`CaptureSource` or any other enum in `avro/`) unless the enum is listed in `avro/.enum-approvals`
for that PR. See `avro/README.md`'s "The compatibility gate" section for how to run it locally and
how to approve an intentional enum change.

### Consuming the generated types

- **Local development (works today, no secret needed):**
  ```bash
  ./mvnw -f codegen/pom.xml install
  ```
  then, in `atlas-ingestion-service` or `atlas-processing-service`'s `pom.xml`:
  ```xml
  <dependency>
    <groupId>com.atlas</groupId>
    <artifactId>atlas-measurement-schema-codegen</artifactId>
    <version>0.0.1-SNAPSHOT</version>
  </dependency>
  ```
- **GitHub Packages (once the `GH_PACKAGES_TOKEN` repository secret is configured):** CI publishes
  the same artifact with `mvn deploy` after a successful build on `main` (see
  `codegen/pom.xml`'s `<distributionManagement>`); the publish step is skipped, not failed, while
  the secret is absent. Once published, add this repository's GitHub Packages Maven registry to the
  consuming project's `settings.xml`/`pom.xml` `<repositories>` and use the same dependency
  coordinates as above with the released version.

### Spike verdict (design section 26.2 gate (a), AT-2 task 5)

**PASS.** The standard Confluent Avro serializer/deserializer (`io.confluent:kafka-avro-serializer`)
round-trips every one of the five v1 events through a real Kafka topic and Apicurio's
Confluent-compatible schema registry API (`/apis/ccompat/v7`), with **no code change beyond the
`schema.registry.url`** — the same client and configuration a consumer would use against a real
Confluent Schema Registry. Verified in
`codegen/src/test/java/com/atlas/events/v1/spike/ApicurioConfluentCompatSpikeIT.java`
(Testcontainers: `apache/kafka-native` + `apicurio/apicurio-registry:3.3.3` with in-memory H2
storage — a self-contained stand-in for `atlas-platform`'s compose `core` profile, which backs the
same image with Postgres instead; both expose the identical `/apis/ccompat` API). No fallback to
Confluent Schema Registry is needed. See `AT-2.md`'s "Spike verdict" note for the ticket-level
record.

## Building

### Prerequisites

- Java 21+
- Docker (only for `codegen/`'s Testcontainers-based spike/round-trip test)

### Build

`codegen/` and `tools/schema-gate/` are separate Maven modules (own `pom.xml`, not aggregated
under the root), built with `./mvnw`'s `-f` flag:

```bash
./mvnw clean package                              # root: JSON Schema, vectors, tolerance
./mvnw -f codegen/pom.xml clean verify             # Avro-generated Java types + tests
./mvnw -f tools/schema-gate/pom.xml clean verify   # the CI compatibility gate tool
```

### Run Tests

```bash
./mvnw test
./mvnw -f codegen/pom.xml test              # unit tests only, no Docker needed
./mvnw -f codegen/pom.xml verify            # + the Testcontainers spike/round-trip IT
```

### Verify All

```bash
./mvnw clean verify
```

## Next Steps (AT-16)

- JSON Schema content, units/frame conventions, `algorithm-spec.md`
- Conformance and boundary vectors
- Kotlin and Swift generated-type publishing (Java is done, AT-2)

## Atlas Architecture

This repository holds the schema contracts for the Atlas 3D model platform. It is referenced by `atlas-ingestion-service`, `atlas-processing-service`, `atlas-measure-kit-ios`, `atlas-measure-kit-android` and the Measurement service for validation, code generation and conformance testing.

For the complete architecture and service integration points, see project-atlas.
