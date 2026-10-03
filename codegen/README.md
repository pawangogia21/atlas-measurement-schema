# Code Generation

Generates Java types from `../avro/*.avsc` (AT-2) using the `avro-maven-plugin`, wired in this
folder's own `pom.xml`. The resulting jar (`atlas-measurement-schema-codegen`) has no domain logic
(design section 3.3) — it is schema-derived types only, consumed by `atlas-ingestion-service` and
`atlas-processing-service`.

This module is the Avro (event) side only. The JSON Schema (client measurement) types are generated elsewhere (AT-16):
Java in `../codegen-json/` (jsonschema2pojo, consumed by the Kotlin artifact), Swift in `../swift/` (quicktype, run by
`swift/generate-types.sh`), and Kotlin uses the Java types jar.

See the root `README.md`'s "Avro event schemas" section for how to build, test and consume this
module, and for the AT-2 spike verdict.
