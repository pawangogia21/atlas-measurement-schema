# atlas-measurement-schema-gate

CI-only tool, never published and never a dependency of `codegen/`, `codegen-json/` or of the services
that consume the generated types. One shaded jar holds two backward-compatibility gates:

- **Avro gate** (`SchemaCompatibilityGate`, AT-2 task 3): fails a change to `../../avro/*.avsc` that is
  backward-incompatible with the previous released version (Avro's own reader/writer compatibility check), or
  that changes an enum's symbols without an approval entry in `../../avro/.enum-approvals`.
- **JSON Schema gate** (`JsonSchemaCompatibilityGate`, AT-16 sub-task L, design 21.4): within a schema major only
  additive, optional changes are allowed, so a client record valid under the baseline stays valid. It fails on a
  removed schema file, property or `$defs` entry, a new required property, a changed `type`, `$ref`, `format` or
  `pattern`, a tightened bound, a changed `additionalProperties` or `items`, and on any keyword it does not
  understand that is added, changed or removed (it cannot compare such a keyword, so it never silently accepts
  it). Only `description`, `title`, `default`, `examples`, `$comment`, `$id`, `$schema` and `deprecated` change
  freely. A change to an `enum` or `const`, or to `allOf`, `anyOf`, `oneOf`, `if`, `then`, `else` or `not`, fails
  unless its key `<file>#<json-pointer>` (for example `v1/live-measurement.schema.json#/properties/state/enum`)
  is listed in `../../json-schema/.enum-approvals` in the same change.

Build the jar once:

```bash
./mvnw -f pom.xml clean package
```

Run the gates directly (exit 0 pass, 1 gate failure, 2 usage or IO error):

```bash
java -jar target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar <baselineDir> <currentDir> [approvalsFile]
java -cp target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar com.atlas.schemagate.JsonSchemaCompatibilityGate <baselineDir> <currentDir> [approvalsFile]
```

Or via the CI wrapper scripts, which resolve the baseline commit for you (the merge-base with the PR target, or the
previous release tag or `origin/main` for a tag build; see `resolve-baseline.sh`):

```bash
../../.github/scripts/avro-compat-gate.sh origin/main
../../.github/scripts/json-schema-compat-gate.sh origin/main
```

In CI both run through `run-trusted-gate.sh`. See the root `README.md`'s "The Avro compatibility gate" section
and `avro/README.md` for the Avro approval-marker convention, and `json-schema/README.md` for the JSON Schema rules.
