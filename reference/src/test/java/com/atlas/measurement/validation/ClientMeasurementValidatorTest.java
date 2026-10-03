package com.atlas.measurement.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ClientMeasurementValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ClientMeasurementValidator validator = new ClientMeasurementValidator();

    private static ObjectNode example(String name) throws IOException {
        try (InputStream in = ClientMeasurementValidatorTest.class.getResourceAsStream("/json-schema/v1/examples/" + name)) {
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    private ClientMeasurementValidator.Result mutatedBox(Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode n = example("live-measurement-object-box.json");
        mutation.accept(n);
        return validator.validateLiveMeasurement(n.toString());
    }

    @Test
    void validExamplesPass() throws IOException {
        assertThat(validator.validateLiveMeasurement(example("live-measurement-object-box.json").toString()).valid()).isTrue();
        assertThat(validator.validateLiveMeasurement(example("live-measurement-point-to-point.json").toString()).valid()).isTrue();
        assertThat(validator.validateClientCapture(example("client-capture.json").toString()).valid()).isTrue();
    }

    @Test
    void unknownTopLevelFieldIsRejected() throws IOException {
        var r = mutatedBox(n -> n.put("extra", 1));
        assertThat(r.code).isEqualTo("CLIENT_MEASUREMENT_INVALID");
    }

    @Test
    void unknownNestedFieldIsRejected() throws IOException {
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("extra", 1)).valid()).isFalse();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("device")).put("extra", "x")).valid()).isFalse();
    }

    @Test
    void unknownFieldInClientCaptureIsRejected() throws IOException {
        ObjectNode n = example("client-capture.json");
        n.put("extra", true);
        assertThat(validator.validateClientCapture(n.toString()).code).isEqualTo("CLIENT_MEASUREMENT_INVALID");
    }

    @Test
    void moreThan32KeypointsIsRejectedAndExactly32Passes() throws IOException {
        assertThat(mutatedBox(n -> fillKeypoints(n, 32)).valid()).isTrue();
        assertThat(mutatedBox(n -> fillKeypoints(n, 33)).valid()).isFalse();
    }

    private static void fillKeypoints(ObjectNode n, int count) {
        ArrayNode kps = ((ObjectNode) n.get("overlay")).putArray("keypoints");
        for (int i = 0; i < count; i++) {
            ObjectNode kp = kps.addObject();
            kp.put("name", "k" + i);
            kp.putArray("world").add(0).add(0).add(0);
            kp.putArray("screen").add(0).add(0);
            kp.put("sigma", 0.01);
        }
    }

    @Test
    void trustMustBeUnverifiedEstimate() throws IOException {
        assertThat(mutatedBox(n -> n.put("trust", "VERIFIED")).valid()).isFalse();
    }

    @Test
    void depthOfExactlyEightPassesAndNineIsRejectedBeforeSchemaValidation() {
        // Depth counts containers; the schema itself would also reject these as unknown fields, so
        // the message is what proves the depth limit fired first.
        assertThat(validator.validateLiveMeasurement(nested(8)).errors).noneMatch(e -> e.startsWith("JSON depth"));
        var r = validator.validateLiveMeasurement(nested(9));
        assertThat(r.code).isEqualTo("CLIENT_MEASUREMENT_INVALID");
        assertThat(r.errors).containsExactly("JSON depth above 8");
    }

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < depth; i++) {
            sb.append("{\"a\":");
        }
        sb.append("{}");
        for (int i = 1; i < depth; i++) {
            sb.append('}');
        }
        return sb.toString();
    }

    @Test
    void absurdlyDeepPayloadIsRejectedWithoutStackOverflow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            sb.append('[');
        }
        assertThat(validator.validateLiveMeasurement(sb.toString()).code).isEqualTo("CLIENT_MEASUREMENT_INVALID");
    }

    @Test
    void unknownSchemaVersionIsUnsupportedNotInvalid() throws IOException {
        assertThat(mutatedBox(n -> n.put("schemaVersion", "1.1")).code).isEqualTo("CLIENT_SCHEMA_UNSUPPORTED");
    }

    @Test
    void vioMetricCapsConfidenceAtPointSix() throws IOException {
        var r = validator.validateLiveMeasurement(example("live-measurement-point-to-point.json").put("confidence", 0.61).toString());
        assertThat(r.valid()).isFalse();
        var ok = validator.validateLiveMeasurement(example("live-measurement-point-to-point.json").put("confidence", 0.6).toString());
        assertThat(ok.valid()).isTrue();
    }

    @Test
    void dimensionsMustMatchMode() throws IOException {
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions")).remove("heightM")).valid()).isFalse();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions")).set("distanceM", n.get("dimensions").get("lengthM"))).valid()).isFalse();
    }

    @Test
    void enumsRangesAndFormatsAreEnforced() throws IOException {
        assertThat(mutatedBox(n -> n.put("mode", "VOLUME")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("unit", "FOOT")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("confidence", 1.01)).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("clientMeasurementId", "not-a-uuid")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("timestamp", "yesterday")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("algorithmVersion", "1.1")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("confidence", "high")).valid()).isFalse();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", -0.1)).valid()).isFalse();
    }

    @Test
    void batchAcceptsUpTo100ItemsAndRejectsMore() throws IOException {
        assertThat(validator.validateBatch(batchOf(100)).valid()).isTrue();
        assertThat(validator.validateBatch(batchOf(101)).valid()).isFalse();
        assertThat(validator.validateBatch("{\"items\":[]}").valid()).isFalse();
    }

    @Test
    void batchItemsAreValidatedAndUnknownEnvelopeFieldsRejected() throws IOException {
        ObjectNode bad = example("live-measurement-object-box.json");
        bad.put("extra", 1);
        assertThat(validator.validateBatch("{\"items\":[" + bad + "]}").valid()).isFalse();
        assertThat(validator.validateBatch(batchOf(1).replace("{\"items\"", "{\"x\":1,\"items\"")).valid()).isFalse();
    }

    @Test
    void batchItemDepthIsLimitedPerItem() throws IOException {
        // example item has depth 5; the envelope is allowed to add two levels, a 9-deep item is not.
        assertThat(validator.validateBatch("{\"items\":[" + nested(9) + "]}").errors).containsExactly("JSON depth above 8");
    }

    private static String batchOf(int n) throws IOException {
        String item = example("live-measurement-point-to-point.json").toString();
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append(item);
        }
        return sb.append("]}").toString();
    }
}
