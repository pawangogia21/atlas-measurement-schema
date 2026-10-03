package com.atlas.schemagate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AT-16 sub-task L: the CI backward-compatibility gate for {@code json-schema/v<major>/*.schema.json}. Within a
 * schema major only additive, optional changes are allowed (design 21.4): a client record valid under the baseline
 * must stay valid, and an old reader must keep working. Same shape as the Avro gate: baseline directory, current
 * directory and an approvals file ({@code json-schema/.enum-approvals}).
 *
 * <p>Fails on: a removed schema file or property or {@code $defs} entry, a new required property, a changed
 * {@code type}, {@code $ref}, {@code format} or {@code pattern}, a tightened bound (higher minimum, lower maximum,
 * {@code minItems}, {@code maxItems}, {@code minLength}, {@code maxLength}, new {@code uniqueItems}), a changed
 * {@code additionalProperties}, a changed {@code items}, and any keyword the gate does not understand that is added,
 * changed or removed (for example {@code multipleOf}, {@code dependentRequired}, {@code dependentSchemas},
 * {@code propertyNames}, {@code patternProperties}, {@code contains}/{@code minContains}/{@code maxContains},
 * {@code unevaluatedProperties}/{@code unevaluatedItems}, {@code $dynamicRef}): they can tighten a schema and cannot be
 * compared, so they are never silently accepted. Only the annotations description, title, default, examples,
 * {@code $comment}, {@code $id}, {@code $schema} and deprecated are free.
 * <p>Compatibility events that are failures unless approved by key: any change to an {@code enum} or {@code const}
 * (symbols are a compatibility event, as in the Avro gate) and any change to the conditional keywords
 * ({@code allOf, anyOf, oneOf, if, then, else, not}), which cannot be compared structurally. A key is
 * {@code <file>#<json-pointer-of-the-keyword>}, e.g. {@code v1/live-measurement.schema.json#/properties/state/enum} (the file path is relative to the schema root).
 * Descriptions, titles, defaults and examples may change freely.
 *
 * <p>Usage: {@code java -cp gate.jar com.atlas.schemagate.JsonSchemaCompatibilityGate <baselineDir> <currentDir>
 * [approvalsFile]}; directories are {@code json-schema/} trees (each major in its own {@code v<n>} folder, a baseline
 * major missing from current is a failure). Exit 0 pass, 1 gate failure, 2 usage or IO error.
 */
public final class JsonSchemaCompatibilityGate {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> LOWER_BOUNDS = List.of("minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties");
    private static final List<String> UPPER_BOUNDS = List.of("maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties");
    private static final List<String> EXACT = List.of("type", "$ref", "format", "pattern", "additionalProperties", "prefixItems");
    private static final List<String> APPROVABLE_EXACT = List.of("enum", "const", "allOf", "anyOf", "oneOf", "if", "then", "else", "not");
    /** Keywords handled above or by the traversal ({@code items}, {@code uniqueItems}, {@code required}, members). */
    private static final Set<String> TRAVERSED = Set.of("items", "uniqueItems", "required", "properties", "$defs");
    /** Annotations that never change what a document must satisfy: free to change. */
    private static final Set<String> FREE = Set.of("description", "title", "default", "examples", "$comment", "$id", "$schema", "deprecated");

    /** Result: the violations (empty = pass) and how many schema files were compared. */
    public static final class Result {
        private final List<String> violations;
        private final int checked;

        Result(List<String> violations, int checked) {
            this.violations = violations;
            this.checked = checked;
        }

        public boolean passed() {
            return violations.isEmpty();
        }

        public List<String> violations() {
            return violations;
        }

        public int checked() {
            return checked;
        }
    }

    private final Set<String> approvals;
    private final List<String> violations = new ArrayList<>();

    private JsonSchemaCompatibilityGate(Set<String> approvals) {
        this.approvals = approvals;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: JsonSchemaCompatibilityGate <baselineDir> <currentDir> [approvalsFile]");
            System.exit(2);
        }
        try {
            Result r = run(Path.of(args[0]), Path.of(args[1]), args.length > 2 ? Path.of(args[2]) : null);
            r.violations().forEach(v -> System.err.println("FAIL: " + v));
            if (r.passed()) {
                System.out.println("JSON Schema compatibility gate: PASS (" + r.checked() + " schema file(s) checked)");
                System.exit(0);
            }
            System.err.println("JSON Schema compatibility gate: FAIL (" + r.violations().size() + " violation(s))");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("JSON Schema compatibility gate: error: " + e.getMessage());
            System.exit(2);
        }
    }

    public static Result run(Path baselineDir, Path currentDir, Path approvalsFile) throws IOException {
        Set<String> approved = new LinkedHashSet<>();
        if (approvalsFile != null && Files.exists(approvalsFile)) {
            for (String line : Files.readAllLines(approvalsFile)) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    approved.add(t);
                }
            }
        }
        JsonSchemaCompatibilityGate gate = new JsonSchemaCompatibilityGate(approved);
        int checked = 0;
        for (Path file : schemaFiles(baselineDir)) {
            String rel = baselineDir.relativize(file).toString().replace('\\', '/');
            Path now = currentDir.resolve(rel);
            if (!Files.exists(now)) {
                gate.violations.add(rel + ": schema file removed within its major");
                continue;
            }
            checked++;
            gate.compare(rel, "", MAPPER.readTree(file.toFile()), MAPPER.readTree(now.toFile()));
        }
        return new Result(gate.violations, checked);
    }

    private static List<Path> schemaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".schema.json")).sorted().collect(Collectors.toList());
        }
    }

    private void fail(String file, String ptr, String message) {
        violations.add(file + "#" + ptr + ": " + message);
    }

    /** A compatibility event: fails unless {@code <file>#<ptr>} is approved. */
    private void event(String file, String ptr, String message) {
        if (!approvals.contains(file + "#" + ptr)) {
            fail(file, ptr, message + " (compatibility event: approve the key " + file + "#" + ptr + " in the approvals file)");
        }
    }

    private void compare(String file, String ptr, JsonNode base, JsonNode cur) {
        if (!base.isObject() || !cur.isObject()) {
            if (!base.equals(cur)) {
                fail(file, ptr, "schema changed from " + base + " to " + cur);
            }
            return;
        }
        for (String k : EXACT) {
            if (!same(base.get(k), cur.get(k))) {
                fail(file, ptr + "/" + k, "changed from " + base.get(k) + " to " + cur.get(k));
            }
        }
        if (base.has("items") && cur.has("items")) {
            compare(file, ptr + "/items", base.get("items"), cur.get("items"));
        } else if (!same(base.get("items"), cur.get("items"))) {
            fail(file, ptr + "/items", "items added or removed");
        }
        for (String k : APPROVABLE_EXACT) {
            if (!same(base.get(k), cur.get(k))) {
                event(file, ptr + "/" + k, "changed from " + base.get(k) + " to " + cur.get(k));
            }
        }
        for (String k : LOWER_BOUNDS) {
            if (cur.has(k) && (!base.has(k) || cur.get(k).asDouble() > base.get(k).asDouble())) {
                fail(file, ptr + "/" + k, "tightened from " + base.get(k) + " to " + cur.get(k));
            }
        }
        for (String k : UPPER_BOUNDS) {
            if (cur.has(k) && (!base.has(k) || cur.get(k).asDouble() < base.get(k).asDouble())) {
                fail(file, ptr + "/" + k, "tightened from " + base.get(k) + " to " + cur.get(k));
            }
        }
        if (cur.path("uniqueItems").asBoolean(false) && !base.path("uniqueItems").asBoolean(false)) {
            fail(file, ptr + "/uniqueItems", "uniqueItems newly required");
        }
        Set<String> baseRequired = names(base.get("required"));
        for (String r : names(cur.get("required"))) {
            if (!baseRequired.contains(r)) {
                fail(file, ptr + "/required", "new required property '" + r + "' (add optional fields only)");
            }
        }
        Set<String> keywords = new LinkedHashSet<>();
        base.fieldNames().forEachRemaining(keywords::add);
        cur.fieldNames().forEachRemaining(keywords::add);
        for (String k : keywords) {
            if (!isKnown(k) && !same(base.get(k), cur.get(k))) {
                fail(file, ptr + "/" + k, "keyword '" + k + "' is not understood by the gate and was added, changed or removed (it can tighten the schema: "
                        + "add optional properties only, or bump the major)");
            }
        }
        compareMembers(file, ptr + "/properties", base.get("properties"), cur.get("properties"), "property");
        compareMembers(file, ptr + "/$defs", base.get("$defs"), cur.get("$defs"), "definition");
    }

    private static boolean isKnown(String keyword) {
        return EXACT.contains(keyword) || APPROVABLE_EXACT.contains(keyword) || LOWER_BOUNDS.contains(keyword) || UPPER_BOUNDS.contains(keyword)
                || TRAVERSED.contains(keyword) || FREE.contains(keyword);
    }

    private void compareMembers(String file, String ptr, JsonNode base, JsonNode cur, String what) {
        if (base == null) {
            return;
        }
        for (Iterator<String> it = base.fieldNames(); it.hasNext();) {
            String name = it.next();
            if (cur == null || !cur.has(name)) {
                fail(file, ptr + "/" + name, what + " '" + name + "' removed");
            } else {
                compare(file, ptr + "/" + name, base.get(name), cur.get(name));
            }
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Set<String> names(JsonNode array) {
        Set<String> s = new LinkedHashSet<>();
        if (array != null) {
            array.forEach(n -> s.add(n.asText()));
        }
        return s;
    }
}
