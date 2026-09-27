package com.atlas.events.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.support.AvroRoundTrip;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelFailedTest {

    @Test
    void minimalValidRecordSerializesAndDeserializes() throws Exception {
        ModelFailed event = ModelFailed.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setStage(FailureStage.PROCESSING)
                .setReasonCode(ReasonCode.CONVERTER_TIMEOUT)
                .setRetryable(true)
                .setMessage("The conversion job timed out.")
                .build();

        assertThat(event.getEventType()).isEqualTo("ModelFailed");

        ModelFailed roundTripped = AvroRoundTrip.roundTrip(event);
        assertThat(roundTripped).isEqualTo(event);
    }
}
