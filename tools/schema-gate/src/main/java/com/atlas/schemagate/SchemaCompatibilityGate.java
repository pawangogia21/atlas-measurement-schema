package com.atlas.schemagate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;

/**
 * AT-2 task 3: the CI backward-compatibility and enum-change gate for {@code avro/*.avsc}.
 *
 * <p>Two independent checks, either of which fails the gate:
 * <ol>
 *   <li><b>Backward compatibility.</b> For every {@code .avsc} file present in both the baseline
 *       (the last released version, typically {@code origin/main}) and the current working tree,
 *       a reader using the current schema must be able to read data written with the baseline
 *       schema ({@link SchemaCompatibility#checkReaderWriterCompatibility}).</li>
 *   <li><b>Unapproved enum change.</b> Any enum whose symbol list differs between baseline and
 *       current is a compatibility event (design section 6.2) that fails the gate unless its fully
 *       qualified name appears in the approvals file (one name per line, {@code #}-prefixed
 *       comments and blank lines ignored; default {@code avro/.enum-approvals}).</li>
 * </ol>
 *
 * <p>Usage: {@code java -jar atlas-measurement-schema-gate.jar <baselineDir> <currentDir>
 * [approvedEnumChangesFile]}. Exit code 0 on pass, 1 on gate failure, 2 on usage/IO error.
 */
public final class SchemaCompatibilityGate {

    private SchemaCompatibilityGate() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: SchemaCompatibilityGate <baselineDir> <currentDir> [approvedEnumChangesFile]");
            System.exit(2);
        }
        try {
            Path baselineDir = Path.of(args[0]);
            Path currentDir = Path.of(args[1]);
            Path approvalsFile = args.length > 2 ? Path.of(args[2]) : null;

            GateResult result = run(baselineDir, currentDir, approvalsFile);
            result.violations().forEach(v -> System.err.println("FAIL: " + v));
            if (result.passed()) {
                System.out.println("Avro compatibility gate: PASS (" + result.checkedSchemaCount() + " schema(s) checked)");
                System.exit(0);
            } else {
                System.err.println("Avro compatibility gate: FAIL (" + result.violations().size() + " violation(s))");
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("Gate could not run: " + e.getMessage());
            System.exit(2);
        }
    }

    public static GateResult run(Path baselineDir, Path currentDir, Path approvalsFile) throws IOException {
        Map<String, Schema> baselineSchemas = parseDirectory(baselineDir);
        Map<String, Schema> currentSchemas = parseDirectory(currentDir);
        Set<String> approvedEnumChanges = readApprovals(approvalsFile);

        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, Schema> entry : currentSchemas.entrySet()) {
            String fileName = entry.getKey();
            Schema currentSchema = entry.getValue();
            Schema baselineSchema = baselineSchemas.get(fileName);
            if (baselineSchema == null) {
                continue; // new file, nothing to compare against
            }
            checked++;

            if (currentSchema.getType() == Schema.Type.RECORD && baselineSchema.getType() == Schema.Type.RECORD) {
                SchemaCompatibility.SchemaPairCompatibility compat =
                        SchemaCompatibility.checkReaderWriterCompatibility(currentSchema, baselineSchema);
                if (compat.getType() != SchemaCompatibilityType.COMPATIBLE) {
                    violations.add(fileName + ": backward-incompatible change ("
                            + compat.getResult().getIncompatibilities() + ")");
                }
            }

            violations.addAll(enumChangeViolations(fileName, baselineSchema, currentSchema, approvedEnumChanges));
        }

        for (String removedFile : baselineSchemas.keySet()) {
            if (!currentSchemas.containsKey(removedFile)) {
                violations.add(removedFile + ": schema file removed; a topic's schema must not disappear "
                        + "(retire via a new major topic version instead)");
            }
        }

        return new GateResult(violations, checked);
    }

    private static List<String> enumChangeViolations(String fileName, Schema baseline, Schema current,
            Set<String> approvedEnumChanges) {
        List<String> violations = new ArrayList<>();
        for (Schema currentEnum : namedEnumsIn(current)) {
            Schema baselineEnum = findByFullName(baseline, currentEnum.getFullName());
            if (baselineEnum == null || baselineEnum.getType() != Schema.Type.ENUM) {
                continue; // new enum, nothing to compare
            }
            if (!baselineEnum.getEnumSymbols().equals(currentEnum.getEnumSymbols())
                    && !approvedEnumChanges.contains(currentEnum.getFullName())) {
                violations.add(fileName + ": enum " + currentEnum.getFullName()
                        + " symbols changed (" + baselineEnum.getEnumSymbols() + " -> " + currentEnum.getEnumSymbols()
                        + ") without an approval entry for it in the enum-approvals file");
            }
        }
        return violations;
    }

    /** All named enum types reachable from this schema (the top-level schema itself, or nested). */
    private static List<Schema> namedEnumsIn(Schema schema) {
        List<Schema> found = new ArrayList<>();
        collectNamedEnums(schema, found, new LinkedHashSet<>());
        return found;
    }

    private static void collectNamedEnums(Schema schema, List<Schema> found, Set<String> visited) {
        if (schema == null || !visited.add(schema.getFullName() + "@" + schema.getType())) {
            return;
        }
        switch (schema.getType()) {
            case ENUM -> found.add(schema);
            case RECORD -> schema.getFields().forEach(f -> collectNamedEnums(f.schema(), found, visited));
            case UNION -> schema.getTypes().forEach(t -> collectNamedEnums(t, found, visited));
            case ARRAY -> collectNamedEnums(schema.getElementType(), found, visited);
            case MAP -> collectNamedEnums(schema.getValueType(), found, visited);
            default -> { /* no nested named types */ }
        }
    }

    private static Schema findByFullName(Schema schema, String fullName) {
        for (Schema candidate : namedEnumsIn(schema)) {
            if (candidate.getFullName().equals(fullName)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Parses every {@code *.avsc} file in a directory into one shared namespace, sorted
     * alphabetically so a type defined in one file (e.g. {@code CaptureSource.avsc}) is available
     * by name to files parsed afterwards that reference it (mirrors codegen/pom.xml's plugin
     * configuration, which imports it explicitly for the same reason).
     */
    private static Map<String, Schema> parseDirectory(Path dir) throws IOException {
        Map<String, Schema> schemasByFileName = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return schemasByFileName;
        }
        Schema.Parser parser = new Schema.Parser();
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".avsc"));
        if (files == null) {
            return schemasByFileName;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            schemasByFileName.put(file.getName(), parser.parse(file));
        }
        return schemasByFileName;
    }

    private static Set<String> readApprovals(Path approvalsFile) throws IOException {
        if (approvalsFile == null || !Files.isRegularFile(approvalsFile)) {
            return Set.of();
        }
        Set<String> approvals = new LinkedHashSet<>();
        for (String line : Files.readAllLines(approvalsFile)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                approvals.add(trimmed);
            }
        }
        return approvals;
    }

    public record GateResult(List<String> violations, int checkedSchemaCount) {
        public boolean passed() {
            return violations.isEmpty();
        }
    }
}
