# atlas-measurement-schema-gate

CI-only tool (AT-2 task 3): fails a change to `../../avro/*.avsc` that is backward-incompatible
with the previous released version, or that changes an enum's symbols without an approval entry in
`../../avro/.enum-approvals`. Never published; never a dependency of `codegen/` or of the services
that consume the generated types.

Run it directly:

```bash
./mvnw -f pom.xml clean package
java -jar target/atlas-measurement-schema-gate-0.0.1-SNAPSHOT.jar <baselineDir> <currentDir> [approvalsFile]
```

Or via the CI wrapper script, which resolves the right baseline commit for you:

```bash
../../.github/scripts/avro-compat-gate.sh origin/main
```

See the root `README.md`'s "The Avro compatibility gate" section and `avro/README.md` for the
approval-marker convention.
