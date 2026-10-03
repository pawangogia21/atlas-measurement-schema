package com.atlas.measurement.association;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.measurement.tolerance.ToleranceProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Inputs the vectors cannot carry as JSON numbers (non-finite) and the error contract of the geometric score. */
class AssociationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode plane(String normal, double offset) {
        return json("{\"id\":\"x\",\"mode\":\"PLANE_DISTANCE\",\"geometry\":{\"plane\":{\"normalWorld\":" + normal + ",\"offsetM\":" + offset + "}}}");
    }

    @Test
    void aZeroLengthPlaneNormalIsAnErrorNotAMatch() {
        // F1: acos(NaN) made units(NaN) = 0 <= units(5), so a zero normal "qualified" on the angle
        assertThatThrownBy(() -> Association.geometricScore(plane("[0,0,0]", 0.5), plane("[0,1,0]", 0.5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Association.geometricScore(plane("[0,1,0]", 0.5), plane("[0,0,0]", 0.5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteGeometryIsAnError() {
        JsonNode p2p = json("{\"id\":\"x\",\"mode\":\"POINT_TO_POINT\",\"geometry\":{\"endpointsWorld\":[[0,0,0],[1,0,0]]}}");
        com.fasterxml.jackson.databind.node.ObjectNode bad = (com.fasterxml.jackson.databind.node.ObjectNode) p2p.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) bad.get("geometry").get("endpointsWorld").get(1)).set(0, MAPPER.getNodeFactory().numberNode(Double.POSITIVE_INFINITY));
        assertThatThrownBy(() -> Association.geometricScore(p2p, bad)).isInstanceOf(IllegalArgumentException.class);
        com.fasterxml.jackson.databind.node.ObjectNode nan = (com.fasterxml.jackson.databind.node.ObjectNode) p2p.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) nan.get("geometry").get("endpointsWorld").get(0)).set(2, MAPPER.getNodeFactory().numberNode(Double.NaN));
        assertThatThrownBy(() -> Association.geometricScore(nan, p2p)).isInstanceOf(IllegalArgumentException.class);
        com.fasterxml.jackson.databind.node.ObjectNode nanOffset = (com.fasterxml.jackson.databind.node.ObjectNode) plane("[0,1,0]", 0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) nanOffset.get("geometry").get("plane")).put("offsetM", Double.NaN);
        assertThatThrownBy(() -> Association.geometricScore(nanOffset, plane("[0,1,0]", 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSeenFromTheOtherSidePlaneScoresLikeTheSamePlane() {
        assertThat(Association.geometricScore(plane("[0,-1,0]", -0.5), plane("[0,1,0]", 0.5))).isEqualTo(Association.geometricScore(plane("[0,1,0]", 0.5), plane("[0,1,0]", 0.5)));
        assertThat(Association.geometricScore(plane("[0,-1,0]", 0.5), plane("[0,1,0]", 0.5))).isEqualTo(-1);
    }

    @Test
    void degenerateBoxesDoNotOverlap() throws IOException {
        JsonNode flat = json("{\"id\":\"x\",\"mode\":\"OBJECT_BOX\",\"obb\":{\"centerWorld\":[0,0,0],\"halfExtentsM\":[0,0,0],\"yawRad\":0}}");
        assertThat(Association.geometricScore(flat, flat)).isEqualTo(-1);
    }

    @Test
    void decideRejectsANonFiniteDimension() throws IOException {
        ToleranceProfile profile = ToleranceProfile.loadBundled();
        com.fasterxml.jackson.databind.node.ObjectNode h = (com.fasterxml.jackson.databind.node.ObjectNode) json(
                "{\"id\":\"h1\",\"mode\":\"POINT_TO_POINT\",\"schemaVersion\":\"1.0\",\"depthTier\":\"A\",\"sessionId\":\"s\",\"worldOriginEpoch\":1,\"dims\":[{\"v\":1.0,\"s\":0}]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) h.get("dims").get(0)).put("v", Double.NaN);
        JsonNode ctx = json("{\"scan\":{\"sessionId\":\"s\",\"worldOriginEpoch\":1,\"transformToCanonical\":false}}");
        JsonNode servers = json("[{\"id\":\"s1\",\"mode\":\"POINT_TO_POINT\",\"dims\":[{\"v\":1.0,\"s\":0}]}]");
        assertThatThrownBy(() -> Association.decide(ctx, MAPPER.createArrayNode().add(h), servers, profile, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
