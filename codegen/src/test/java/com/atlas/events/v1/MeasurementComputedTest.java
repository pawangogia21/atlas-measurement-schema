package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeasurementComputedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        MeasurementComputed event = MeasurementComputed.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .build();

        assertThat(event.getEventType()).isEqualTo("MeasurementComputed");
        assertThat(event.getTenantId()).isEqualTo("default");
        assertThat(event.getSchemaVersion()).isEqualTo("1.0");
        assertThat(event.getTraceId()).isNull();

        MeasurementComputed roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }
}
