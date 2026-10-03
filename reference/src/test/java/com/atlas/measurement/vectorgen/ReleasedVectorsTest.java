package com.atlas.measurement.vectorgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.measurement.association.Association;
import com.atlas.measurement.tolerance.ToleranceProfile;
import com.atlas.measurement.validation.ClientMeasurementValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The committed boundary and negative vectors are byte-identical to what their generators produce, their manifest
 * sha256 entries match the files, and they replay against the reference implementation (the Swift and Kotlin
 * tolerance tests replay boundary-cases.json themselves).
 */
class ReleasedVectorsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path BOUNDARY = Paths.get("..", "vectors", "boundary", BoundaryVectorGenerator.VERSION);
    private static final Path NEGATIVE = Paths.get("..", "vectors", "negative", NegativeVectorGenerator.VERSION);

    @Test
    void boundaryRegenerationIsByteIdentical(@TempDir Path tmp) throws IOException {
        BoundaryVectorGenerator.generate(tmp);
        assertIdentical(tmp, BOUNDARY);
    }

    @Test
    void negativeRegenerationIsByteIdentical(@TempDir Path tmp) throws IOException {
        NegativeVectorGenerator.generate(tmp);
        assertIdentical(tmp, NEGATIVE);
    }

    @Test
    void manifestHashesMatchTheFilesOnDisk() throws IOException {
        for (Path dir : List.of(BOUNDARY, NEGATIVE)) {
            JsonNode manifest = MAPPER.readTree(dir.resolve("manifest.json").toFile());
            List<String> listed = new java.util.ArrayList<>();
            for (JsonNode f : manifest.get("files")) {
                byte[] data = Files.readAllBytes(dir.resolve(f.get("path").asText()));
                assertThat(ConformanceVectorGenerator.sha256(data)).as(f.get("path").asText()).isEqualTo(f.get("sha256").asText());
                assertThat(data.length).isEqualTo(f.get("bytes").asInt());
                listed.add(f.get("path").asText());
            }
            // every file in the release is listed (manifest.json cannot list itself)
            try (Stream<Path> s = Files.list(dir)) {
                assertThat(s.map(p -> p.getFileName().toString()).filter(n -> !n.equals("manifest.json")).collect(Collectors.toList()))
                        .containsExactlyInAnyOrderElementsOf(listed);
            }
        }
    }

    @Test
    void associationVectorsReplay() throws IOException {
        JsonNode root = MAPPER.readTree(BOUNDARY.resolve("association-cases.json").toFile());
        ToleranceProfile profile = ToleranceProfile.loadBundled();
        int branches = 0;
        for (JsonNode c : root.get("cases")) {
            assertThat(Association.decide(c.get("context"), c.get("hints"), c.get("servers"), profile, root.get("algorithmMajor").asInt()))
                    .as(c.get("id").asText()).isEqualTo(c.get("expected"));
            branches++;
        }
        assertThat(branches).isGreaterThanOrEqualTo(70);
    }

    @Test
    void everyReasonCodeAndAssociationBranchHasAVector() throws IOException {
        String all = Files.readString(BOUNDARY.resolve("association-cases.json"));
        for (String s : new String[] {"NO_ASSOCIATION", "TIER_C", "MODE_MISMATCH", "SERVER_FAILED", "SCHEMA_UNSUPPORTED", "MODEL_DELETED",
                "\"SEEDED\"", "\"IOU\"", "\"GEOMETRIC\"", "\"DIMENSION_ONLY\"", "PENDING"}) {
            assertThat(all).as(s).contains(s);
        }
    }

    @Test
    void negativeVectorsAreRejectedForTheirStatedReason() throws IOException {
        ClientMeasurementValidator validator = new ClientMeasurementValidator();
        JsonNode manifest = MAPPER.readTree(NEGATIVE.resolve("manifest.json").toFile());
        int count = 0;
        for (JsonNode v : manifest.get("vectors")) {
            String id = v.get("id").asText();
            String payload = Files.readString(NEGATIVE.resolve(v.get("path").asText()));
            String kind = v.get("kind").asText();
            count++;
            if (kind.equals("batchItems")) {
                ClientMeasurementValidator.BatchResult r = validator.validateBatchItems(payload);
                assertThat(r.accepted()).as(id).isTrue();
                assertThat(r.items).as(id).hasSize(v.get("expectedItems").size());
                for (int i = 0; i < r.items.size(); i++) {
                    assertThat(r.items.get(i).valid() ? "VALID" : r.items.get(i).code).as(id + "[" + i + "]").isEqualTo(v.get("expectedItems").get(i).asText());
                }
                continue;
            }
            ClientMeasurementValidator.Result r = kind.equals("clientCapture") ? validator.validateClientCapture(payload)
                    : kind.equals("batch") ? validator.validateBatch(payload) : validator.validateLiveMeasurement(payload);
            assertThat(v.get("expectedStatus").asInt()).isEqualTo(422);
            assertThat(r.code).as(id).isEqualTo(v.get("expectedCode").asText()).isEqualTo("CLIENT_MEASUREMENT_INVALID");
            assertThat(String.join("\n", r.errors)).as(id).contains(v.get("expectedErrorContains").asText());
        }
        assertThat(count).isGreaterThanOrEqualTo(100);
    }

    @Test
    void negativeVectorsCoverTheRequiredFamilies() throws IOException {
        String ids = Files.readString(NEGATIVE.resolve("manifest.json"));
        for (String id : new String[] {"depth-9", "depth-100001", "keypoints-33", "wrong-trust", "unknown-field-top-level", "unknown-enum-mode",
                "unknown-enum-scale-source", "missing-qualityFlags", "missing-dimensions", "batch-per-item-rejection", "batch-101-items",
                "value-1e999-overflows-a-double", "value-just-over-maximum", "algorithm-version-major-over-int", "duplicate-key-top-level",
                "trailing-content-second-document", "body-over-256k-characters", "string-over-4096-characters", "plane-normal-zero-length",
                "batch-item-depth-9", "batch-body-depth-33", "batch-item-schema-version-unsupported"}) {
            assertThat(ids).as(id).contains("\"" + id + "\"");
        }
    }

    private static void assertIdentical(Path generated, Path released) throws IOException {
        try (Stream<Path> s = Files.walk(generated)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path committed = released.resolve(generated.relativize(p).toString());
                assertThat(committed).as(generated.relativize(p).toString()).exists();
                assertThat(Files.readAllBytes(committed)).as(generated.relativize(p).toString()).isEqualTo(Files.readAllBytes(p));
            }
        }
        try (Stream<Path> g = Files.walk(generated); Stream<Path> r = Files.walk(released)) {
            assertThat(r.filter(Files::isRegularFile).count()).isEqualTo(g.filter(Files::isRegularFile).count());
        }
    }
}
