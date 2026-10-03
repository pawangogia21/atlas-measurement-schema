package com.atlas.measurement.schema.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one way to read the generated types: unknown properties and unknown enum symbols fail (the JSON Schema has
 * {@code additionalProperties: false}) and JSON deeper than {@link #MAX_JSON_DEPTH} is refused while parsing. The
 * generated classes carry no validation of their own beyond this; ranges, patterns and the VIO_METRIC rule are
 * enforced by the JSON Schema validation of the intake (see json-schema/README.md).
 */
public final class StrictMapper {
    public static final int MAX_JSON_DEPTH = 8;

    private StrictMapper() {}

    public static ObjectMapper create() {
        return new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).build()).build())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
}
