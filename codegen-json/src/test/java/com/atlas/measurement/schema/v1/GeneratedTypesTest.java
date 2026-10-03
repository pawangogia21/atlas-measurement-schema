package com.atlas.measurement.schema.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class GeneratedTypesTest {
    private static final ObjectMapper MAPPER = StrictMapper.create();
    private static final Path EXAMPLES = Paths.get("..", "json-schema", "v1", "examples");
    private static final Path NEGATIVE = Paths.get("..", "vectors", "negative", "1.0.0");

    private static final java.util.Comparator<JsonNode> NUMERIC = (a, b) ->
            a.isNumber() && b.isNumber() ? Double.compare(a.doubleValue(), b.doubleValue()) : a.equals(b) ? 0 : 1;

    private static String example(String name) throws IOException {
        return Files.readString(EXAMPLES.resolve(name));
    }

    @Test
    void validExamplesDeserializeAndRoundTrip() throws IOException {
        for (String name : new String[] {"live-measurement-object-box.json", "live-measurement-point-to-point.json", "live-measurement-plane-distance.json"}) {
            LiveMeasurement m = MAPPER.readValue(example(name), LiveMeasurement.class);
            // 0 and 0.0 are the same JSON number: compare numerically
            assertThat(MAPPER.readTree(MAPPER.writeValueAsString(m)).equals(NUMERIC, MAPPER.readTree(example(name)))).as(name).isTrue();
        }
        ClientCapture c = MAPPER.readValue(example("client-capture.json"), ClientCapture.class);
        assertThat(c.getDepthTier()).isEqualTo(ClientCapture.DepthTier.A);
    }

    @Test
    void generatedFieldsFollowTheContract() throws IOException {
        LiveMeasurement m = MAPPER.readValue(example("live-measurement-object-box.json"), LiveMeasurement.class);
        assertThat(m.getMode()).isEqualTo(LiveMeasurement.Mode.OBJECT_BOX);
        assertThat(m.getTrust()).isEqualTo("UNVERIFIED_ESTIMATE");
        assertThat(m.getDimensions().getLengthM().getValue()).isEqualTo(0.602);
        assertThat(m.getFrameOfReference().getWorldOriginEpoch()).isEqualTo(2);
        assertThat(m.getQualityFlags()).containsExactly(QualityFlag.PARTIAL_VIEW);
    }

    @Test
    void unknownFieldsAndUnknownEnumSymbolsAreRejected() throws IOException {
        for (String id : new String[] {"unknown-field-top-level", "unknown-field-nested-dimensions", "unknown-field-nested-device",
                "unknown-field-nested-obb", "unknown-field-nested-keypoint", "unknown-enum-mode", "unknown-enum-state",
                "unknown-enum-depth-tier", "unknown-enum-depth-source", "unknown-enum-scale-source", "unknown-enum-quality-flag"}) {
            String payload = Files.readString(NEGATIVE.resolve(id + ".json"));
            assertThatThrownBy(() -> MAPPER.readValue(payload, LiveMeasurement.class)).as(id).isInstanceOf(JsonProcessingException.class);
        }
        String capture = Files.readString(NEGATIVE.resolve("capture-unknown-field.json"));
        assertThatThrownBy(() -> MAPPER.readValue(capture, ClientCapture.class)).isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void jsonDeeperThanEightIsRefusedWhileParsing() throws IOException {
        for (String id : new String[] {"depth-9", "depth-100001"}) {
            String payload = Files.readString(NEGATIVE.resolve(id + ".json"));
            assertThatThrownBy(() -> MAPPER.readTree(payload)).as(id).isInstanceOf(JsonProcessingException.class);
            assertThatThrownBy(() -> MAPPER.readValue(payload, LiveMeasurement.class)).as(id).isInstanceOf(JsonProcessingException.class);
        }
    }

    @Test
    void batchRequestHoldsLiveMeasurements() throws IOException {
        JsonNode item = MAPPER.readTree(example("live-measurement-point-to-point.json"));
        ClientMeasurementsBatchRequest b = MAPPER.readValue("{\"items\":[" + item + "]}", ClientMeasurementsBatchRequest.class);
        assertThat(b.getItems()).hasSize(1);
        assertThat(b.getItems().get(0).getMode()).isEqualTo(LiveMeasurement.Mode.POINT_TO_POINT);
    }

    @Test
    void geometryTypesFollowTheContract() throws IOException {
        LiveMeasurement p2p = MAPPER.readValue(example("live-measurement-point-to-point.json"), LiveMeasurement.class);
        assertThat(p2p.getGeometry().getEndpointsWorld()).hasSize(2);
        assertThat(p2p.getGeometry().getPlane()).isNull();
        LiveMeasurement plane = MAPPER.readValue(example("live-measurement-plane-distance.json"), LiveMeasurement.class);
        assertThat(plane.getGeometry().getPlane().getNormalWorld()).containsExactly(0.0, 1.0, 0.0);
        assertThat(plane.getGeometry().getPlane().getOffsetM()).isEqualTo(0.0);
    }

    @Test
    void parserStrictnessMatchesTheIntakeValidator() throws IOException {
        for (String id : new String[] {"duplicate-key-top-level", "duplicate-key-nested", "trailing-content-second-document", "trailing-content-garbage",
                "value-1e999-overflows-a-double", "value-negative-1e999", "yaw-1e999", "nan-literal", "infinity-literal", "empty-body",
                "string-over-4096-characters", "number-over-32-characters", "name-over-128-characters"}) {
            String payload = Files.readString(NEGATIVE.resolve(id + ".json"));
            assertThatThrownBy(() -> MAPPER.readValue(payload, LiveMeasurement.class)).as(id).isInstanceOf(java.io.IOException.class);
        }
        // the mapper serves single documents and batches: its document limit is the batch limit, 400 KiB
        String oversizeBatch = Files.readString(NEGATIVE.resolve("batch-over-400k-characters.json"));
        assertThatThrownBy(() -> MAPPER.readValue(oversizeBatch, ClientMeasurementsBatchRequest.class)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void aNumberGivenAsAStringIsNotCoerced() throws IOException {
        String valid = example("live-measurement-object-box.json");
        assertThatThrownBy(() -> MAPPER.readValue(valid.replace("\"confidence\": 0.87", "\"confidence\": \"NaN\""), LiveMeasurement.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> MAPPER.readValue(valid.replace("\"worldOriginEpoch\": 2", "\"worldOriginEpoch\": \"2\""), LiveMeasurement.class))
                .isInstanceOf(JsonProcessingException.class);
    }
}
