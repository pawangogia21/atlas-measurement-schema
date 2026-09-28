package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelDeletedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        ModelDeleted event = ModelDeleted.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .build();

        assertThat(event.getEventType()).isEqualTo("ModelDeleted");
        assertThat(event.getTenantId()).isEqualTo("default");

        ModelDeleted roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }
}
