package com.atlas.measurement.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
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
        assertThat(validator.validateLiveMeasurement(example("live-measurement-plane-distance.json").toString()).valid()).isTrue();
        assertThat(validator.validateLiveMeasurement(example("live-measurement-plane-normal-0.9991.json").toString()).valid()).isTrue();
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
    void aTooDeepBatchItemIsInvalidOnItsOwnAndDoesNotFailTheBatch() throws IOException {
        String item = example("live-measurement-point-to-point.json").toString();
        var r = validator.validateBatchItems("{\"items\":[" + item + "," + nested(9) + "," + item + "]}");
        assertThat(r.accepted()).isTrue();
        assertThat(r.items).hasSize(3);
        assertThat(r.items.get(0).valid()).isTrue();
        assertThat(r.items.get(1).code).isEqualTo("CLIENT_MEASUREMENT_INVALID");
        assertThat(r.items.get(1).errors).containsExactly("JSON depth above 8");
        assertThat(r.items.get(2).valid()).isTrue();
        // an item of exactly depth 8 is judged by the schema, not by the depth rule
        assertThat(validator.validateBatchItems("{\"items\":[" + nested(8) + "]}").items.get(0).errors).noneMatch(e -> e.startsWith("JSON depth"));
    }

    @Test
    void aBodyBeyondTheTransportCeilingFailsTheWholeBatch() {
        // the envelope adds two levels: an item of depth 30 is at the ceiling of 32, depth 31 is beyond it
        assertThat(validator.validateBatchItems("{\"items\":[" + nested(30) + "]}").accepted()).isTrue();
        var r = validator.validateBatchItems("{\"items\":[" + nested(31) + "]}");
        assertThat(r.accepted()).isFalse();
        assertThat(r.items).isEmpty();
        assertThat(r.request.errors).containsExactly("JSON depth above 32");
    }

    @Test
    void anOversizeBatchOrTooManyItemsFailsTheWholeBatch() throws IOException {
        String big = "{\"items\":[]}" + " ".repeat(ClientMeasurementValidator.MAX_BATCH_CHARS);
        assertThat(validator.validateBatchItems(big).accepted()).isFalse();
        assertThat(validator.validateBatchItems(batchOf(101)).accepted()).isFalse();
        assertThat(validator.validateBatchItems(batchOf(100)).items).hasSize(100);
    }

    @Test
    void anUnsupportedSchemaVersionIsInvalidWithItsOwnCodeInBothBatchMethods() throws IOException {
        ObjectNode future = example("live-measurement-point-to-point.json");
        future.put("schemaVersion", "2.0");
        String body = "{\"items\":[" + example("live-measurement-point-to-point.json") + "," + future + "]}";
        assertThat(validator.validateBatchItems(body).items.get(1).code).isEqualTo("CLIENT_SCHEMA_UNSUPPORTED");
        assertThat(validator.validateBatch(body).code).isEqualTo("CLIENT_SCHEMA_UNSUPPORTED");
        assertThat(validator.validateLiveMeasurement(future.toString()).code).isEqualTo("CLIENT_SCHEMA_UNSUPPORTED");
    }

    @Test
    void uploadsCompleteNeverRejectsTheUploadForBadHints() throws IOException {
        String item = example("live-measurement-point-to-point.json").toString();
        var ok = validator.validateCompleteHints("[" + item + "]");
        assertThat(ok.allDropped()).isFalse();
        assertThat(ok.warnings()).isEmpty();
        // item level: only that item is dropped
        var some = validator.validateCompleteHints("[" + item + "," + nested(9) + "]");
        assertThat(some.allDropped()).isFalse();
        assertThat(some.items.get(0).valid()).isTrue();
        assertThat(some.items.get(1).valid()).isFalse();
        assertThat(some.warnings()).containsExactly("CLIENT_HINTS_DROPPED");
        // body level: all hints dropped, the upload continues
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append(item);
        }
        for (String body : new String[] {many.append("]").toString(), "[" + nested(33) + "]", "{}", "[1,", "[" + item + "]" + " ".repeat(ClientMeasurementValidator.MAX_DOCUMENT_CHARS)}) {
            var all = validator.validateCompleteHints(body);
            assertThat(all.allDropped()).as(body.substring(0, Math.min(20, body.length()))).isTrue();
            assertThat(all.warnings()).containsExactly("CLIENT_HINTS_DROPPED");
        }
    }

    @Test
    void nonFiniteAndHugeNumbersAreRejected() throws IOException {
        for (String bad : new String[] {"1e999", "-1e999", "1E400"}) {
            var r = validator.validateLiveMeasurement(example("live-measurement-object-box.json").toString().replace("\"value\":0.602", "\"value\":" + bad));
            assertThat(r.code).as(bad).isEqualTo("CLIENT_MEASUREMENT_INVALID");
            assertThat(r.errors).as(bad).anyMatch(e -> e.startsWith("finiteNumber"));
        }
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", 1e308)).valid()).isFalse();
        assertThat(validator.validateClientCapture(example("client-capture.json").toString().replace("\"algorithmVersion\":\"1.1.2\"", "\"algorithmVersion\":\"1.1.2\"")).valid()).isTrue();
    }

    @Test
    void metricsStopAtWhatTheColumnsHold() throws IOException {
        // NUMERIC(9,5): 9999.99999 is the largest value
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", 9999.99999)).valid()).isTrue();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", 10000)).valid()).isFalse();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("sigmaM", 10000)).valid()).isFalse();
        assertThat(mutatedBox(n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("ci95M", 10000)).valid()).isFalse();
        // INT columns: components below 10^9
        assertThat(mutatedBox(n -> n.put("algorithmVersion", "999999999.0.0")).valid()).isTrue();
        assertThat(mutatedBox(n -> n.put("algorithmVersion", "2147483648.0.0")).valid()).isFalse();
        assertThat(mutatedBox(n -> n.put("algorithmVersion", "1.1000000000.0")).valid()).isFalse();
    }

    @Test
    void geometryFollowsTheModeAndAPlaneNormalHasUnitLength() throws IOException {
        ObjectNode plane = example("live-measurement-plane-distance.json");
        assertThat(validator.validateLiveMeasurement(plane.toString()).valid()).isTrue();
        ((ObjectNode) plane.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0.9991).add(0);
        assertThat(validator.validateLiveMeasurement(plane.toString()).valid()).isTrue();
        ((ObjectNode) plane.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0.9989).add(0);
        assertThat(validator.validateLiveMeasurement(plane.toString()).errors).containsExactly("unitLength at $.geometry.plane.normalWorld");
        ((ObjectNode) plane.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0).add(0);
        assertThat(validator.validateLiveMeasurement(plane.toString()).valid()).isFalse();
        // each mode carries only its own geometry; a hint without geometry is valid
        ObjectNode p2p = example("live-measurement-point-to-point.json");
        ((ObjectNode) p2p.get("geometry")).putObject("plane").put("offsetM", 0).putArray("normalWorld").add(0).add(1).add(0);
        assertThat(validator.validateLiveMeasurement(p2p.toString()).valid()).isFalse();
        p2p.remove("geometry");
        assertThat(validator.validateLiveMeasurement(p2p.toString()).valid()).isTrue();
        assertThat(mutatedBox(n -> n.putObject("geometry").putArray("endpointsWorld").addArray().add(0).add(0).add(0).addNull()).valid()).isFalse();
    }

    @Test
    void duplicateKeysAndTrailingContentAreRejected() throws IOException {
        String valid = example("live-measurement-object-box.json").toString();
        assertThat(validator.validateLiveMeasurement(valid.substring(0, valid.length() - 1) + ",\"confidence\":0.5}").errors).containsExactly("duplicate object key");
        assertThat(validator.validateLiveMeasurement(valid + " {\"evil\":1}").errors).containsExactly("trailing content after the JSON document");
        assertThat(validator.validateLiveMeasurement(valid + " xx").valid()).isFalse();
        assertThat(validator.validateLiveMeasurement(valid + "  \n").valid()).isTrue();
        assertThat(validator.validateClientCapture(example("client-capture.json") + "{}").valid()).isFalse();
        assertThat(validator.validateBatchItems("{\"items\":[]}{}").accepted()).isFalse();
    }

    @Test
    void parserLimitsRefuseOversizedInputBeforeSchemaWork() throws IOException {
        String valid = example("live-measurement-object-box.json").toString();
        assertThat(validator.validateLiveMeasurement(valid + " ".repeat(ClientMeasurementValidator.MAX_DOCUMENT_CHARS)).errors)
                .containsExactly("document larger than 262144 characters");
        assertThat(validator.validateLiveMeasurement(valid.replace("iPhone15,3", "a".repeat(4097))).errors)
                .containsExactly("string value longer than 4096 characters");
        assertThat(validator.validateLiveMeasurement(valid.replace("0.87", "0." + "1".repeat(40))).errors)
                .containsExactly("number longer than 32 characters");
        assertThat(validator.validateLiveMeasurement(valid.substring(0, valid.length() - 1) + ",\"" + "k".repeat(129) + "\":1}").errors)
                .containsExactly("name longer than 128 characters");
        assertThat(validator.validateLiveMeasurement(null).valid()).isFalse();
    }

    @Test
    void errorsNeverEchoClientValues() throws IOException {
        String secret = "SECRET\\u0007\\nINJECT";
        String valid = example("live-measurement-object-box.json").toString();
        for (String payload : new String[] {
                valid.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"" + secret + "\""),
                valid.replace("\"mode\":\"OBJECT_BOX\"", "\"mode\":\"" + secret + "\""),
                valid.substring(0, valid.length() - 1) + ",\"" + secret + "\":\"" + secret + "\"}",
                valid.replace("\"state\":\"STABLE\"", "\"state\":\"" + secret + "\"")}) {
            var r = validator.validateLiveMeasurement(payload);
            assertThat(r.valid()).isFalse();
            assertThat(String.join("\n", r.errors)).doesNotContain("SECRET").doesNotContain("INJECT").doesNotContain("\u0007");
        }
        // a schemaVersion of a million characters is refused by the string limit and costs a short error
        var r = validator.validateLiveMeasurement(valid.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"" + "9".repeat(1_000_000) + "\""));
        assertThat(String.join("", r.errors).length()).isLessThan(100);
        // many errors are capped
        var many = mutatedBox(n -> {
            ArrayNode flags = n.putArray("qualityFlags");
            for (int i = 0; i < 8; i++) {
                flags.add("X" + i);
            }
            n.put("unit", "X").put("state", "X").put("mode", "X").put("depthTier", "X").put("depthSource", "X").put("scaleSource", "X");
        });
        assertThat(many.errors.size()).isLessThanOrEqualTo(20);
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
