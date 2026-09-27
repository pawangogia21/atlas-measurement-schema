# Code Generation

Generates Java types from `../avro/*.avsc` (AT-2) using the `avro-maven-plugin`, wired in this
folder's own `pom.xml`. The resulting jar (`atlas-measurement-schema-codegen`) has no domain logic
(design section 3.3) — it is schema-derived types only, consumed by `atlas-ingestion-service` and
`atlas-processing-service`.

Kotlin and Swift generation are not yet wired (AT-16/later).

See the root `README.md`'s "Avro event schemas" section for how to build, test and consume this
module, and for the AT-2 spike verdict.
