package com.atlas.schemagate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the gate fails (red) and passes (green) on the repository's real schema: the baseline is a copy of
 * json-schema/v1/live-measurement.schema.json and each test mutates a copy of it.
 */
class JsonSchemaCompatibilityGateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REAL = Paths.get("..", "..", "json-schema", "v1", "live-measurement.schema.json");
    private static final String FILE = "v1/live-measurement.schema.json";

    @TempDir
    Path tmp;
    private Path baseline;
    private Path current;

    @BeforeEach
    void setUp() throws IOException {
        baseline = tmp.resolve("baseline");
        current = tmp.resolve("current");
        Files.createDirectories(baseline.resolve("v1"));
        Files.createDirectories(current.resolve("v1"));
        Files.copy(REAL, baseline.resolve(FILE));
    }

    private JsonSchemaCompatibilityGate.Result changed(Consumer<ObjectNode> mutation, String... approvals) throws IOException {
        ObjectNode n = (ObjectNode) MAPPER.readTree(REAL.toFile());
        mutation.accept(n);
        MAPPER.writeValue(current.resolve(FILE).toFile(), n);
        Path file = null;
        if (approvals.length > 0) {
            file = tmp.resolve("approvals");
            Files.write(file, List.of(approvals));
        }
        return JsonSchemaCompatibilityGate.run(baseline, current, file);
    }

    private static ObjectNode props(ObjectNode n) {
        return (ObjectNode) n.get("properties");
    }

    @Test
    void unchangedSchemaPasses() throws IOException {
        JsonSchemaCompatibilityGate.Result r = changed(n -> { });
        assertThat(r.violations()).isEmpty();
        assertThat(r.checked()).isEqualTo(1);
    }

    @Test
    void addingAnOptionalPropertyAndEditingDescriptionsPasses() throws IOException {
        assertThat(changed(n -> {
            props(n).putObject("extraHint").put("type", "string").put("description", "new optional field");
            n.put("description", "reworded");
        }).violations()).isEmpty();
    }

    @Test
    void looseningABoundPasses() throws IOException {
        assertThat(changed(n -> ((ObjectNode) props(n).get("qualityFlags")).put("maxItems", 16)).violations()).isEmpty();
    }

    @Test
    void removingAPropertyFails() throws IOException {
        assertThat(changed(n -> props(n).remove("obb")).violations()).anyMatch(v -> v.contains("property 'obb' removed"));
    }

    @Test
    void aNewRequiredPropertyFails() throws IOException {
        assertThat(changed(n -> {
            props(n).putObject("extraHint").put("type", "string");
            ((ArrayNode) n.get("required")).add("extraHint");
        }).violations()).anyMatch(v -> v.contains("new required property 'extraHint'"));
    }

    @Test
    void makingAnExistingOptionalPropertyRequiredFails() throws IOException {
        assertThat(changed(n -> ((ArrayNode) n.get("required")).add("trust")).violations())
                .anyMatch(v -> v.contains("new required property 'trust'"));
    }

    @Test
    void aTypeChangeFails() throws IOException {
        assertThat(changed(n -> ((ObjectNode) props(n).get("confidence")).put("type", "string")).violations())
                .anyMatch(v -> v.contains("/properties/confidence/type"));
    }

    @Test
    void tighteningABoundFails() throws IOException {
        assertThat(changed(n -> ((ObjectNode) props(n).get("overlay").get("properties").get("keypoints")).put("maxItems", 16)).violations())
                .anyMatch(v -> v.contains("maxItems"));
        assertThat(changed(n -> ((ObjectNode) props(n).get("confidence")).put("minimum", 0.1)).violations())
                .anyMatch(v -> v.contains("/confidence/minimum"));
    }

    @Test
    void allowingUnknownFieldsFails() throws IOException {
        assertThat(changed(n -> n.remove("additionalProperties")).violations()).anyMatch(v -> v.contains("additionalProperties"));
    }

    @Test
    void anUnapprovedEnumChangeFailsAndAnApprovedOnePasses() throws IOException {
        Consumer<ObjectNode> addSymbol = n -> ((ArrayNode) props(n).get("state").get("enum")).add("PAUSED");
        assertThat(changed(addSymbol).violations()).anyMatch(v -> v.contains("/properties/state/enum") && v.contains("approve the key"));
        assertThat(changed(addSymbol, "# comment", FILE + "#/properties/state/enum").violations()).isEmpty();
    }

    @Test
    void aConditionalRuleChangeFailsUnlessApproved() throws IOException {
        Consumer<ObjectNode> drop = n -> ((ArrayNode) n.get("allOf")).remove(1);
        assertThat(changed(drop).violations()).anyMatch(v -> v.contains("/allOf"));
        assertThat(changed(drop, FILE + "#/allOf").violations()).isEmpty();
    }

    @Test
    void tighteningKeywordsTheGateDoesNotKnowAreRejected() throws IOException {
        // F10: each of these can tighten an existing property and used to pass silently
        String[][] cases = {
            {"multipleOf", "0.001"}, {"dependentRequired", "{\"sigmaM\":[\"ci95M\"]}"}, {"dependentSchemas", "{\"sigmaM\":{\"required\":[\"ci95M\"]}}"},
            {"propertyNames", "{\"maxLength\":3}"}, {"patternProperties", "{\"^x\":{\"type\":\"number\"}}"}, {"contains", "{\"type\":\"number\"}"},
            {"minContains", "2"}, {"maxContains", "1"}, {"unevaluatedProperties", "false"}, {"unevaluatedItems", "false"}, {"$dynamicRef", "\"#x\""},
            {"$anchor", "\"x\""}, {"contentEncoding", "\"base64\""}};
        for (String[] c : cases) {
            JsonSchemaCompatibilityGate.Result r = changed(n -> {
                try {
                    ((ObjectNode) ((ObjectNode) n.get("$defs")).get("measured")).set(c[0], MAPPER.readTree(c[1]));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(r.violations()).as(c[0]).anyMatch(v -> v.contains("keyword '" + c[0] + "' is not understood"));
        }
        // also on a property, and an unknown keyword removed or changed (cannot be compared either)
        assertThat(changed(n -> ((ObjectNode) props(n).get("confidence")).put("multipleOf", 0.01)).violations()).isNotEmpty();
    }

    @Test
    void anUnknownKeywordAlreadyInTheBaselineMayStayButNotChange() throws IOException {
        ObjectNode b = (ObjectNode) MAPPER.readTree(REAL.toFile());
        ((ObjectNode) ((ObjectNode) b.get("$defs")).get("measured")).put("multipleOf", 0.001);
        MAPPER.writeValue(baseline.resolve(FILE).toFile(), b);
        assertThat(changed(n -> ((ObjectNode) ((ObjectNode) n.get("$defs")).get("measured")).put("multipleOf", 0.001)).violations()).isEmpty();
        assertThat(changed(n -> ((ObjectNode) ((ObjectNode) n.get("$defs")).get("measured")).put("multipleOf", 0.01)).violations()).isNotEmpty();
        assertThat(changed(n -> { }).violations()).anyMatch(v -> v.contains("'multipleOf'"));
    }

    @Test
    void freeAnnotationsMayChange() throws IOException {
        assertThat(changed(n -> {
            n.put("title", "Renamed");
            n.put("$comment", "c");
            n.putArray("examples").add("x");
            ((ObjectNode) props(n).get("confidence")).put("default", 0.5).put("description", "reworded").put("deprecated", false);
        }).violations()).isEmpty();
    }

    @Test
    void aRemovedDefinitionFails() throws IOException {
        assertThat(changed(n -> ((ObjectNode) n.get("$defs")).remove("vec2")).violations()).anyMatch(v -> v.contains("definition 'vec2' removed"));
    }

    @Test
    void aRemovedSchemaFileFails() throws IOException {
        assertThat(JsonSchemaCompatibilityGate.run(baseline, current, null).violations()).anyMatch(v -> v.contains("schema file removed"));
    }

    @Test
    void aNewSchemaFileAndANewMajorPass() throws IOException {
        Files.copy(REAL, current.resolve(FILE));
        Files.createDirectories(current.resolve("v2"));
        Files.writeString(current.resolve("v1/added.schema.json"), "{\"type\":\"object\"}");
        Files.writeString(current.resolve("v2/live-measurement.schema.json"), "{\"type\":\"array\"}");
        assertThat(JsonSchemaCompatibilityGate.run(baseline, current, null).violations()).isEmpty();
    }
}
