package com.atlas.measurement.schema.v1;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

/**
 * The one way to read the generated types: unknown properties and unknown enum symbols fail (the JSON Schema has
 * {@code additionalProperties: false}), JSON deeper than {@link #MAX_JSON_DEPTH} is refused while parsing, duplicate
 * object keys and trailing content after the document fail, a number that overflows a double (1e999) or a number
 * given as a string fails, and a document, string or number beyond the intake limits is refused while parsing. The
 * generated classes carry no validation of their own beyond this; ranges, patterns and the VIO_METRIC rule are
 * enforced by the JSON Schema validation of the intake (see json-schema/README.md).
 */
public final class StrictMapper {
    public static final int MAX_JSON_DEPTH = 8;
    /** Same limits as ClientMeasurementValidator (reference/): one document, strings, numbers, names. */
    public static final int MAX_DOCUMENT_CHARS = 400 * 1024;
    public static final int MAX_STRING_LENGTH = 4096;
    public static final int MAX_NUMBER_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 128;

    private StrictMapper() {}

    public static ObjectMapper create() {
        SimpleModule finiteDoubles = new SimpleModule("atlas-finite-doubles");
        finiteDoubles.addDeserializer(Double.class, new FiniteDouble());
        finiteDoubles.addDeserializer(double.class, new FiniteDouble());
        return new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).maxDocumentLength(MAX_DOCUMENT_CHARS)
                        .maxStringLength(MAX_STRING_LENGTH).maxNumberLength(MAX_NUMBER_LENGTH).maxNameLength(MAX_NAME_LENGTH).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build())
                .registerModule(new JavaTimeModule())
                .registerModule(finiteDoubles)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, false)
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /** A JSON number that fits a finite double; a string, NaN or an overflowing literal is an error (never echoed). */
    private static final class FiniteDouble extends StdDeserializer<Double> {
        private static final long serialVersionUID = 1L;

        FiniteDouble() {
            super(Double.class);
        }

        @Override
        public Double deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (!p.currentToken().isNumeric()) {
                throw JsonMappingException.from(p, "a JSON number is required");
            }
            double d = p.getDoubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw JsonMappingException.from(p, "number outside the finite double range");
            }
            return d;
        }
    }
}
