package com.atlas.measurement.tolerance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToleranceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode resource(String path) throws IOException {
        try (InputStream in = ToleranceTest.class.getResourceAsStream(path)) {
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode smoke() throws IOException {
        return MAPPER.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../tolerance/smoke-cases.json")));
    }

    private static JsonNode boundary() throws IOException {
        return MAPPER.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../vectors/boundary/1.0.0/boundary-cases.json")));
    }

    @Test
    void profileMatchesItsSchemaAndHasVersion100() throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(ToleranceTest.class.getResourceAsStream("/tolerance/tolerance-profile.schema.json"));
        assertThat(schema.validate(resource("/tolerance/tolerance-profile.json"))).isEmpty();
        assertThat(ToleranceProfile.loadBundled().version()).isEqualTo("1.0.0");
    }

    @Test
    void profileSchemaRejectsBadProfiles() throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(ToleranceTest.class.getResourceAsStream("/tolerance/tolerance-profile.schema.json"));
        com.fasterxml.jackson.databind.node.ObjectNode p = (com.fasterxml.jackson.databind.node.ObjectNode) resource("/tolerance/tolerance-profile.json");
        p.put("extra", 1);
        assertThat(schema.validate(p)).isNotEmpty();
        p = (com.fasterxml.jackson.databind.node.ObjectNode) resource("/tolerance/tolerance-profile.json");
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.get("rows")).remove(3);
        assertThat(schema.validate(p)).isNotEmpty();
        p = (com.fasterxml.jackson.databind.node.ObjectNode) resource("/tolerance/tolerance-profile.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.get("overrides")).putObject("x1");
        assertThat(schema.validate(p)).isNotEmpty();
    }

    @Test
    void bundledProfileHoldsTheFourRowsOfDesign212() throws IOException {
        ToleranceProfile p = ToleranceProfile.loadBundled();
        assertRow(p.row("accuracyGate", 1), 0.01, 0.02, null, null);
        assertRow(p.row("accuracyGateP95", 1), 0.025, 0.04, null, null);
        assertRow(p.row("agree", 1), 0.02, 0.02, 2.0, 0.10);
        assertRow(p.row("minor", 1), 0.05, 0.05, 3.0, 0.15);
    }

    private static void assertRow(ToleranceRow r, double floorM, double relFrac, Double sigmaK, Double cap) {
        assertThat(r.floorM).isEqualTo(floorM);
        assertThat(r.relFrac).isEqualTo(relFrac);
        assertThat(r.sigmaK).isEqualTo(sigmaK);
        assertThat(r.sigmaCapM).isEqualTo(cap);
    }

    @Test
    void smokeTolCases() throws IOException {
        JsonNode s = smoke();
        ToleranceProfile base = ToleranceProfile.loadBundled();
        ToleranceProfile over = ToleranceProfile.parse(s.get("overrideProfile"));
        for (JsonNode c : s.get("tol")) {
            ToleranceProfile p = c.get("profile").asText().equals("base") ? base : over;
            ToleranceRow row = p.row(c.get("row").asText(), c.get("major").asInt());
            double t = Tolerance.tol(c.get("ref").asDouble(), c.get("sigmaServer").asDouble(), c.get("sigmaClient").asDouble(), row);
            assertThat(t).as(c.get("id").asText()).isCloseTo(c.get("tol").asDouble(), org.assertj.core.data.Offset.offset(s.get("tolEpsilon").asDouble()));
        }
    }

    @Test
    void smokeWithinCases() throws IOException {
        for (JsonNode c : smoke().get("within")) {
            assertThat(Tolerance.withinTolerance(c.get("diff").asDouble(), c.get("tol").asDouble()))
                    .as(c.get("id").asText()).isEqualTo(c.get("within").asBoolean());
        }
    }

    @Test
    void smokeClassifyCases() throws IOException {
        ToleranceProfile base = ToleranceProfile.loadBundled();
        for (JsonNode c : smoke().get("classify")) {
            List<Tolerance.DimensionPair> dims = new ArrayList<>();
            for (JsonNode d : c.get("dims")) {
                dims.add(new Tolerance.DimensionPair(d.get("server").asDouble(), d.get("sigmaServer").asDouble(),
                        d.get("client").asDouble(), d.get("sigmaClient").asDouble()));
            }
            assertThat(Tolerance.classify(base, c.get("major").asInt(), dims).name())
                    .as(c.get("id").asText()).isEqualTo(c.get("outcome").asText());
        }
    }

    @Test
    void invalidInputsAreErrorsNotResults() throws IOException {
        ToleranceRow agree = ToleranceProfile.loadBundled().row("agree", 1);
        assertThatThrownBy(() -> Tolerance.tol(1, -0.1, 0, agree)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tolerance.tol(Double.NaN, 0, 0, agree)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tolerance.tol(1, 0, Double.POSITIVE_INFINITY, agree)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToleranceProfile.loadBundled().row("nope", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitsRoundHalfUpAtOneEMinusFive() {
        assertThat(Tolerance.units(0.020004)).isEqualTo(2000);
        assertThat(Tolerance.units(0.020006)).isEqualTo(2001);
        assertThat(Tolerance.units(0.0)).isZero();
    }

    @Test
    void boundaryTolCases() throws IOException {
        JsonNode b = boundary();
        ToleranceProfile base = ToleranceProfile.loadBundled();
        for (JsonNode c : b.get("threshold")) {
            ToleranceRow row = base.row(c.get("row").asText(), c.get("major").asInt());
            double t = Tolerance.tol(c.get("ref").asDouble(), c.get("sigmaServer").asDouble(), c.get("sigmaClient").asDouble(), row);
            assertThat(t).as(c.get("id").asText()).isCloseTo(c.get("tol").asDouble(), org.assertj.core.data.Offset.offset(b.get("tolEpsilon").asDouble()));
            assertThat(Tolerance.withinTolerance(c.get("diff").asDouble(), t)).as(c.get("id").asText()).isEqualTo(c.get("within").asBoolean());
        }
    }

    @Test
    void boundaryWithinCases() throws IOException {
        for (JsonNode c : boundary().get("within")) {
            assertThat(Tolerance.withinTolerance(c.get("diff").asDouble(), c.get("tol").asDouble()))
                    .as(c.get("id").asText()).isEqualTo(c.get("within").asBoolean());
        }
    }

    @Test
    void boundaryClassifyCases() throws IOException {
        ToleranceProfile base = ToleranceProfile.loadBundled();
        for (JsonNode c : boundary().get("classify")) {
            List<Tolerance.DimensionPair> dims = new ArrayList<>();
            for (JsonNode d : c.get("dims")) {
                dims.add(new Tolerance.DimensionPair(d.get("server").asDouble(), d.get("sigmaServer").asDouble(),
                        d.get("client").asDouble(), d.get("sigmaClient").asDouble()));
            }
            assertThat(Tolerance.classify(base, c.get("major").asInt(), dims).name())
                    .as(c.get("id").asText()).isEqualTo(c.get("outcome").asText());
        }
    }
}
