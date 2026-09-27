package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelPurgedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        ModelPurged event = ModelPurged.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setConfirmingService(ConfirmingService.PROCESSING)
                .build();

        assertThat(event.getEventType()).isEqualTo("ModelPurged");

        ModelPurged roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }
}
