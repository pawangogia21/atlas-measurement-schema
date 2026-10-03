package com.atlas.measurement.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Intake validation of client JSON against the 1.0 schemas (design 25.2). Normative text: json-schema/README.md.
 *
 * <p>Rules that JSON Schema cannot express, applied here before and after the schema:
 * <ul>
 *   <li>Document limits at parse time (S6): at most {@link #MAX_DOCUMENT_CHARS} characters ({@link #MAX_BATCH_CHARS}
 *       for a batch), strings of at most {@link #MAX_STRING_LENGTH}, numbers of at most {@link #MAX_NUMBER_LENGTH}
 *       characters, names of at most 128. They are checked while parsing, so an oversized body is never materialised.
 *   <li>JSON nesting depth at most {@link #MAX_JSON_DEPTH} (root object = depth 1) per document or batch item. A
 *       batch is parsed with the transport ceiling {@link #TRANSPORT_MAX_DEPTH} so that one too-deep item can be
 *       answered INVALID without failing the batch; a body deeper than the ceiling is refused as a whole.
 *   <li>Strict JSON (S7): duplicate object keys, trailing content after the document and non-numeric tokens are errors.
 *   <li>Finite numbers (F1/S8): a number that overflows a double (1e999) is rejected.
 *   <li>{@code geometry.plane.normalWorld} has unit length within {@link #UNIT_NORMAL_TOLERANCE}.
 * </ul>
 * Errors never echo a client value (S6): a fixed text, the schema keyword and the instance path, which the schema
 * itself bounds (the path holds only schema-defined names and array indices).
 */
public final class ClientMeasurementValidator {
    public static final int MAX_JSON_DEPTH = 8;
    public static final int TRANSPORT_MAX_DEPTH = 32;
    public static final int MAX_DOCUMENT_CHARS = 256 * 1024;
    public static final int MAX_BATCH_CHARS = 400 * 1024;
    public static final int MAX_STRING_LENGTH = 4096;
    public static final int MAX_NUMBER_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_BATCH_ITEMS = 100;
    public static final int MAX_COMPLETE_ITEMS = 50;
    public static final double UNIT_NORMAL_TOLERANCE = 1e-3;
    public static final String CODE_INVALID = "CLIENT_MEASUREMENT_INVALID";
    public static final String CODE_SCHEMA_UNSUPPORTED = "CLIENT_SCHEMA_UNSUPPORTED";
    public static final String WARNING_HINTS_DROPPED = "CLIENT_HINTS_DROPPED";

    private static final String SCHEMA_BASE = "https://schemas.atlas.example/measurement/v1/";
    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0");
    private static final int MAX_ERRORS = 20;
    private static final int MAX_PATH_CHARS = 128;

    private static final ObjectMapper MAPPER = mapper(MAX_JSON_DEPTH, MAX_DOCUMENT_CHARS);
    private static final ObjectMapper BATCH_MAPPER = mapper(TRANSPORT_MAX_DEPTH, MAX_BATCH_CHARS);
    private static final ObjectMapper HINTS_MAPPER = mapper(TRANSPORT_MAX_DEPTH, MAX_DOCUMENT_CHARS);

    private static ObjectMapper mapper(int depth, int documentChars) {
        return new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth).maxDocumentLength(documentChars)
                        .maxStringLength(MAX_STRING_LENGTH).maxNumberLength(MAX_NUMBER_LENGTH).maxNameLength(MAX_NAME_LENGTH).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    /** Whether a hint's {@code schemaVersion} is one the server accepts (the association reason SCHEMA_UNSUPPORTED). */
    public static boolean isSupportedSchemaVersion(String schemaVersion) {
        return SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion);
    }

    /** Outcome: {@code code == null} means valid. */
    public static final class Result {
        public final String code;
        public final List<String> errors;

        Result(String code, List<String> errors) {
            this.code = code;
            this.errors = errors;
        }

        static Result invalid(String error) {
            return new Result(CODE_INVALID, List.of(error));
        }

        public boolean valid() {
            return code == null;
        }
    }

    /**
     * A batch: {@code request} is invalid when the whole request is refused (envelope, size, count or a body deeper
     * than the transport ceiling), in which case {@code items} is empty; otherwise {@code request} is valid and
     * {@code items} holds one result per item (a bad item is INVALID and does not fail the batch).
     */
    public static final class BatchResult {
        public final Result request;
        public final List<Result> items;

        BatchResult(Result request, List<Result> items) {
            this.request = request;
            this.items = items;
        }

        public boolean accepted() {
            return request.valid();
        }
    }

    /**
     * The {@code clientMeasurements} of {@code uploads/complete}: the upload is never rejected for bad hints. A
     * body-level violation drops all hints, an item-level one only that item (with its code); either way the
     * response is 202 VALIDATING with {@link #WARNING_HINTS_DROPPED}.
     */
    public static final class HintsResult {
        public final Result request;
        public final List<Result> items;

        HintsResult(Result request, List<Result> items) {
            this.request = request;
            this.items = items;
        }

        public boolean allDropped() {
            return !request.valid();
        }

        public List<String> warnings() {
            boolean dropped = allDropped() || items.stream().anyMatch(r -> !r.valid());
            return dropped ? List.of(WARNING_HINTS_DROPPED) : List.of();
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
        try {
            return liveMeasurementNode(parse(MAPPER, json, MAX_DOCUMENT_CHARS, MAX_JSON_DEPTH));
        } catch (InvalidDocument e) {
            return e.result;
        }
    }

    public Result validateClientCapture(String json) {
        try {
            JsonNode node = parse(MAPPER, json, MAX_DOCUMENT_CHARS, MAX_JSON_DEPTH);
            Result structural = structural(node);
            return structural != null ? structural : check(clientCapture, node);
        } catch (InvalidDocument e) {
            return e.result;
        }
    }

    /**
     * All-or-nothing view of a batch: the request-level result when the request is refused, else the first invalid
     * item's result (so an unsupported item {@code schemaVersion} is {@link #CODE_SCHEMA_UNSUPPORTED} here too), else
     * valid. Use {@link #validateBatchItems(String)} for the per-item answer the API gives.
     */
    public Result validateBatch(String json) {
        BatchResult r = validateBatchItems(json);
        if (!r.accepted()) {
            return r.request;
        }
        return r.items.stream().filter(i -> !i.valid()).findFirst().orElse(r.request);
    }

    /**
     * Batch intake (design 25.2): the envelope must be {@code {"items": [1..100 objects]}} and each item is then
     * judged on its own; a bad item (including one nested deeper than {@link #MAX_JSON_DEPTH} or with an unsupported
     * {@code schemaVersion}) is INVALID and does not fail the batch. The whole request is refused only for the
     * transport reasons: not JSON, over {@link #MAX_BATCH_CHARS}, deeper than {@link #TRANSPORT_MAX_DEPTH}, a string
     * or number over its limit, a duplicate key, trailing content, or a bad envelope or item count.
     */
    public BatchResult validateBatchItems(String json) {
        JsonNode node;
        try {
            node = parse(BATCH_MAPPER, json, MAX_BATCH_CHARS, TRANSPORT_MAX_DEPTH);
        } catch (InvalidDocument e) {
            return new BatchResult(e.result, List.of());
        }
        JsonNode items = node.get("items");
        if (!node.isObject() || node.size() != 1 || items == null || !items.isArray() || items.size() < 1 || items.size() > MAX_BATCH_ITEMS) {
            return new BatchResult(Result.invalid("batch envelope must be {\"items\": [1.." + MAX_BATCH_ITEMS + " objects]}"), List.of());
        }
        return new BatchResult(new Result(null, List.of()), itemResults(items));
    }

    /** The {@code clientMeasurements} array of {@code uploads/complete}, given as its JSON text. */
    public HintsResult validateCompleteHints(String json) {
        JsonNode node;
        try {
            node = parse(HINTS_MAPPER, json, MAX_DOCUMENT_CHARS, TRANSPORT_MAX_DEPTH);
        } catch (InvalidDocument e) {
            return new HintsResult(e.result, List.of());
        }
        if (!node.isArray() || node.size() > MAX_COMPLETE_ITEMS) {
            return new HintsResult(Result.invalid("clientMeasurements must be an array of at most " + MAX_COMPLETE_ITEMS + " objects"), List.of());
        }
        return new HintsResult(new Result(null, List.of()), itemResults(node));
    }

    private List<Result> itemResults(JsonNode items) {
        List<Result> results = new ArrayList<>();
        for (JsonNode item : items) {
            results.add(!item.isObject() ? Result.invalid("batch item must be a JSON object")
                    : depth(item) > MAX_JSON_DEPTH ? Result.invalid("JSON depth above " + MAX_JSON_DEPTH) : liveMeasurementNode(item));
        }
        return results;
    }

    private Result liveMeasurementNode(JsonNode node) {
        Result structural = structural(node);
        if (structural != null) {
            return structural;
        }
        JsonNode version = node.get("schemaVersion");
        if (version != null && version.isTextual() && !SUPPORTED_SCHEMA_VERSIONS.contains(version.asText())) {
            return new Result(CODE_SCHEMA_UNSUPPORTED, List.of("schemaVersion is not supported"));
        }
        Result schema = check(liveMeasurement, node);
        if (!schema.valid()) {
            return schema;
        }
        JsonNode normal = node.path("geometry").path("plane").path("normalWorld");
        if (normal.isArray()) {
            double len = Math.sqrt(normal.get(0).asDouble() * normal.get(0).asDouble() + normal.get(1).asDouble() * normal.get(1).asDouble()
                    + normal.get(2).asDouble() * normal.get(2).asDouble());
            if (!(Math.abs(len - 1) <= UNIT_NORMAL_TOLERANCE)) {
                return Result.invalid("unitLength at $.geometry.plane.normalWorld");
            }
        }
        return schema;
    }

    /** Checks that apply to any document before schema work: a number outside the finite double range is invalid. */
    private static Result structural(JsonNode node) {
        return allFinite(node) ? null : Result.invalid("finiteNumber: a number is outside the finite double range");
    }

    private static boolean allFinite(JsonNode n) {
        if (n.isFloatingPointNumber()) {
            return !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue());
        }
        for (JsonNode child : n) {
            if (!allFinite(child)) {
                return false;
            }
        }
        return true;
    }

    /** Container nesting depth, the root counting as 1; only called on a tree already bounded by the transport ceiling. */
    private static int depth(JsonNode n) {
        int deepest = 0;
        for (JsonNode child : n) {
            deepest = Math.max(deepest, depth(child));
        }
        return n.isContainerNode() ? deepest + 1 : 0;
    }

    private static final class InvalidDocument extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Result result;

        InvalidDocument(String message) {
            super(message, null, false, false);
            this.result = Result.invalid(message);
        }
    }

    private static JsonNode parse(ObjectMapper mapper, String json, int maxChars, int maxDepth) throws InvalidDocument {
        if (json == null || json.length() > maxChars) {
            throw new InvalidDocument("document larger than " + maxChars + " characters");
        }
        JsonNode n;
        try (JsonParser p = mapper.getFactory().createParser(json)) {
            n = mapper.readTree(p);
            if (p.nextToken() != null) {
                throw new InvalidDocument("trailing content after the JSON document");
            }
        } catch (StreamConstraintsException e) {
            throw new InvalidDocument(constraintMessage(e.getMessage(), maxChars, maxDepth));
        } catch (JsonProcessingException e) {
            throw new InvalidDocument(e.getOriginalMessage() != null && e.getOriginalMessage().startsWith("Duplicate field")
                    ? "duplicate object key" : "not valid JSON");
        } catch (IOException e) {
            throw new InvalidDocument("not valid JSON");
        }
        if (n == null || n.isMissingNode()) {
            throw new InvalidDocument("not valid JSON: the document is empty");
        }
        if (mapper != HINTS_MAPPER && !n.isObject()) {
            throw new InvalidDocument("document must be a JSON object");
        }
        return n;
    }

    /** Fixed text per violated limit; the Jackson message quotes lengths, never client text, but is not forwarded. */
    private static String constraintMessage(String jackson, int maxChars, int maxDepth) {
        String m = jackson == null ? "" : jackson;
        if (m.contains("getMaxNestingDepth")) {
            return "JSON depth above " + maxDepth;
        }
        if (m.contains("getMaxStringLength")) {
            return "string value longer than " + MAX_STRING_LENGTH + " characters";
        }
        if (m.contains("getMaxNumberLength")) {
            return "number longer than " + MAX_NUMBER_LENGTH + " characters";
        }
        if (m.contains("getMaxNameLength")) {
            return "name longer than " + MAX_NAME_LENGTH + " characters";
        }
        return "document larger than " + maxChars + " characters";
    }

    private static Result check(JsonSchema schema, JsonNode node) {
        Set<ValidationMessage> messages = schema.validate(node);
        if (messages.isEmpty()) {
            return new Result(null, List.of());
        }
        List<String> errors = new ArrayList<>();
        for (ValidationMessage m : messages) {
            errors.add(describe(m));
        }
        errors.sort(null);
        return new Result(CODE_INVALID, errors.size() > MAX_ERRORS ? new ArrayList<>(errors.subList(0, MAX_ERRORS)) : errors);
    }

    /**
     * {@code <keyword> at <instance path>}, plus the schema-defined property name for a missing required property.
     * Never the library message, which quotes the offending value or, for an unknown field, the client's field name.
     */
    private static String describe(ValidationMessage m) {
        String path = String.valueOf(m.getInstanceLocation()).replaceAll("[^A-Za-z0-9_.$\\[\\]-]", "?");
        if (path.length() > MAX_PATH_CHARS) {
            path = path.substring(0, MAX_PATH_CHARS);
        }
        String text = m.getType() + " at " + path;
        return "required".equals(m.getType()) && m.getProperty() != null ? text + ": " + m.getProperty() : text;
    }
}
