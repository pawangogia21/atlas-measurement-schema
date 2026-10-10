package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelUploadedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        ModelUploaded event = ModelUploaded.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setStorageKey("t/default/o/owner-1/m/model-1/raw/model.usdz")
                .setStorageVersionId("v1")
                .setSizeBytes(1024L)
                .setSha256("a".repeat(64))
                .setDetectedFormat("USDZ")
                .build();

        assertThat(event.getTenantId()).isEqualTo("default");
        assertThat(event.getClaimedCaptureSource()).isEqualTo(CaptureSource.UNKNOWN);
        assertThat(event.getClientHintCount()).isZero();
        assertThat(event.getEventType()).isEqualTo("ModelUploaded");

        ModelUploaded roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }

    @Test
    void clientCaptureWorldOriginEpochDefaultsToNullAndRoundTrips() throws Exception {
        ModelUploaded.Builder builder = ModelUploaded.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setStorageKey("t/default/o/owner-1/m/model-1/raw/model.usdz")
                .setStorageVersionId("v1")
                .setSizeBytes(1024L)
                .setSha256("a".repeat(64))
                .setDetectedFormat("USDZ");

        assertThat(builder.build().getClientCaptureWorldOriginEpoch()).isNull();

        ModelUploaded withEpoch = builder.setClientCaptureWorldOriginEpoch(2147483647).build();
        assertThat(AvroRoundTrip.roundTrip(withEpoch).getClientCaptureWorldOriginEpoch()).isEqualTo(2147483647);
    }
}
