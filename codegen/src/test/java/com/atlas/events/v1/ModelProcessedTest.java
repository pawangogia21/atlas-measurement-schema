package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelProcessedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        UUID modelId = UUID.randomUUID();
        ModelProcessed event = ModelProcessed.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(modelId)
                .setOwnerId("owner-1")
                .setCanonicalStorageKey("t/default/o/owner-1/m/" + modelId + "/canonical/model.glb")
                .setGeometryDocumentId(modelId + ":1")
                .setSpatialArtifactKey("t/default/o/owner-1/m/" + modelId + "/derived/model.bvh")
                .setScaleToMetres(1.0)
                .setScaleSource("DECLARED_METERS_PER_UNIT")
                .setScaleConfidence(0.95)
                .setNeedsConfirmation(false)
                .setAabbSizeXMetres(1.2)
                .setAabbSizeYMetres(0.8)
                .setAabbSizeZMetres(2.1)
                .setPipelineVersion("1")
                .setDurationMs(4200L)
                .setEvidence(List.of("USD_PRODUCER_ROOMPLAN"))
                .build();

        assertThat(event.getDerivedCaptureSource()).isEqualTo(CaptureSource.UNKNOWN);
        assertThat(event.getTenantId()).isEqualTo("default");

        ModelProcessed roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }
}
