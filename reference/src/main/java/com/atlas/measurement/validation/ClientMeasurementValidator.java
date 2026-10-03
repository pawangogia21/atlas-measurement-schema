package com.atlas.measurement.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Intake validation of one client JSON document against the 1.0 schemas (design 25.2).
 *
 * <p>The JSON nesting depth limit (8) cannot be expressed in JSON Schema. It is enforced here, while parsing
 * and before any schema work (so a deeply nested payload is rejected without being materialised), through
 * Jackson's {@code maxNestingDepth}. Depth counts containers: the root object is depth 1, so
 * {@code {"a":{"b":1}}} has depth 2. The limit applies to each {@code LiveMeasurement} (and to the
 * {@code clientCapture}) document, not to the surrounding batch envelope.
 */
public final class ClientMeasurementValidator {
    public static final int MAX_JSON_DEPTH = 8;
    public static final String CODE_INVALID = "CLIENT_MEASUREMENT_INVALID";
    public static final String CODE_SCHEMA_UNSUPPORTED = "CLIENT_SCHEMA_UNSUPPORTED";

    private static final String SCHEMA_BASE = "https://schemas.atlas.example/measurement/v1/";
    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0");

    private static final ObjectMapper MAPPER = mapperWithDepth(MAX_JSON_DEPTH);
    // The batch envelope adds two container levels ({"items":[ item ]}), so items stay within MAX_JSON_DEPTH.
    private static final ObjectMapper BATCH_MAPPER = mapperWithDepth(MAX_JSON_DEPTH + 2);

    private static ObjectMapper mapperWithDepth(int depth) {
        return new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth).build()).build());
    }

    /** Outcome: {@code code == null} means valid. */
    public static final class Result {
        public final String code;
        public final List<String> errors;

        Result(String code, List<String> errors) {
            this.code = code;
            this.errors = errors;
        }

        public boolean valid() {
            return code == null;
        }
    }

    private final JsonSchema liveMeasurement = load("live-measurement.schema.json");
    private final JsonSchema clientCapture = load("client-capture.schema.json");
    private final JsonSchema batch = load("client-measurements-batch.schema.json");

    private static JsonSchema load(String file) {
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                b -> b.schemaMappers(m -> m.mapPrefix(SCHEMA_BASE, "classpath:json-schema/v1/")));
        return factory.getSchema(SchemaLocation.of(SCHEMA_BASE + file), config);
    }

    /** One {@code LiveMeasurement} (an item of {@code clientMeasurements} or of a batch). */
    public Result validateLiveMeasurement(String json) {
        JsonNode node;
        try {
            node = parseObject(MAPPER, json);
        } catch (InvalidDocument e) {
            return e.result;
        }
        JsonNode version = node.get("schemaVersion");
        if (version != null && version.isTextual() && !SUPPORTED_SCHEMA_VERSIONS.contains(version.asText())) {
            return new Result(CODE_SCHEMA_UNSUPPORTED, List.of("schemaVersion " + version.asText() + " is not supported"));
        }
        return check(liveMeasurement, node);
    }

    public Result validateClientCapture(String json) {
        try {
            return check(clientCapture, parseObject(MAPPER, json));
        } catch (InvalidDocument e) {
            return e.result;
        }
    }

    /** The batch envelope; the depth limit applies to each item (the envelope itself adds two levels). */
    public Result validateBatch(String json) {
        try {
            return check(batch, parseObject(BATCH_MAPPER, json));
        } catch (InvalidDocument e) {
            return e.result;
        }
    }

    /**
     * Batch intake (design 25.2): the envelope must be valid ({@code items} only, 1 to 100) and each item is then
     * judged on its own; a bad item is INVALID and does not fail the batch. A body nested deeper than the limit
     * is refused as a whole before it is materialised, so a too-deep item fails the entire request.
     *
     * @return one result for the whole request when the envelope or the depth is invalid, otherwise one per item
     */
    public List<Result> validateBatchItems(String json) {
        JsonNode node;
        try {
            node = parseObject(BATCH_MAPPER, json);
        } catch (InvalidDocument e) {
            return List.of(e.result);
        }
        JsonNode items = node.get("items");
        if (node.size() != 1 || items == null || !items.isArray() || items.size() < 1 || items.size() > 100) {
            return List.of(new Result(CODE_INVALID, List.of("batch envelope must be {\"items\": [1..100 objects]}")));
        }
        List<Result> results = new ArrayList<>();
        for (JsonNode item : items) {
            results.add(item.isObject() ? validateLiveMeasurement(item.toString())
                    : new Result(CODE_INVALID, List.of("batch item must be a JSON object")));
        }
        return results;
    }

    private static final class InvalidDocument extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Result result;

        InvalidDocument(String message) {
            super(message, null, false, false);
            this.result = new Result(CODE_INVALID, List.of(message));
        }
    }

    private static JsonNode parseObject(ObjectMapper mapper, String json) throws InvalidDocument {
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (StreamConstraintsException e) {
            throw new InvalidDocument("JSON depth above " + MAX_JSON_DEPTH);
        } catch (JsonProcessingException e) {
            throw new InvalidDocument("not valid JSON: " + e.getOriginalMessage());
        }
        if (n == null || !n.isObject()) {
            throw new InvalidDocument("document must be a JSON object");
        }
        return n;
    }

    private static Result check(JsonSchema schema, JsonNode node) {
        Set<ValidationMessage> messages = schema.validate(node);
        if (messages.isEmpty()) {
            return new Result(null, List.of());
        }
        List<String> errors = new ArrayList<>();
        for (ValidationMessage m : messages) {
            errors.add(m.getMessage());
        }
        errors.sort(null);
        return new Result(CODE_INVALID, errors);
    }
}
