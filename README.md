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
- **`codegen/`** — Tools and configuration for code generation (Java, Kotlin, Swift)

## Versioning

Schemas follow semantic versioning with topic-specific version markers:

- **Topic versioning** — Kafka topics are versioned via topic name suffix (e.g., `atlas.model.uploaded.v1`). Schema changes that break compatibility result in a new topic version.
- **Backward compatibility** — Within a topic version, schemas must maintain backward compatibility (new fields are optional with defaults, deprecated fields are supported).
- **Scope** — Version bumps apply per topic, not globally across all schemas.

## Building

### Prerequisites

- Java 21+

### Build

```bash
./mvnw clean package
```

### Run Tests

```bash
./mvnw test
```

### Verify All

```bash
./mvnw clean verify
```

## Next Steps (AT-2, AT-16)

- Schema content: JSON Schema and Avro definitions
- Schema validation and linting
- Schema compatibility checking
- Code generation and artifact publishing
- Conformance and tolerance profile specifications

## Atlas Architecture

This repository holds the schema contracts for the Atlas 3D model platform. It is referenced by `atlas-ingestion-service`, `atlas-processing-service`, `atlas-measure-kit-ios`, `atlas-measure-kit-android` and the Measurement service for validation, code generation and conformance testing.

For the complete architecture and service integration points, see project-atlas.
