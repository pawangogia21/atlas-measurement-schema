package com.atlas.measurement.vectorgen;

import com.atlas.measurement.validation.ClientMeasurementValidator;
import com.atlas.measurement.validation.ClientMeasurementValidator.Result;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Generates the negative vectors (design 24 item 12): payloads the intake must reject with 422
 * {@code CLIENT_MEASUREMENT_INVALID} (the upload itself still succeeds with {@code CLIENT_HINTS_DROPPED}, that
 * part is AT-17). Each vector is a mutation of a valid example. The generator fails if the reference validator
 * does not reject a vector or its error does not contain the recorded token, so every vector is rejected for
 * the reason it states. Kinds: {@code liveMeasurement}, {@code clientCapture}, {@code batch} (the whole request
 * is 422) and {@code batchItems} (a valid envelope: per-item results, the expected code of each item or
 * {@code VALID} in {@code expectedItems}, and the text each invalid item's errors must contain in
 * {@code expectedItemErrorContains}) and {@code hintsComplete} (the {@code clientMeasurements} array of
 * {@code uploads/complete}: never rejects the upload, see {@code validateCompleteHints}). Error tokens are the fixed texts of the validator (keyword and instance
 * path, never a client value). Oversized-body vectors are built from whitespace or one long string so they stay
 * small; the 21-million-character string of the security review is the same rule at a larger size.
 * Usage: {@code NegativeVectorGenerator <outDir>} (release layout vectors/negative/<version>/).
 */
public final class NegativeVectorGenerator {
    public static final String VERSION = "1.0.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final int OVER_LIMIT_DEPTH = 100_001;

    private final ClientMeasurementValidator validator = new ClientMeasurementValidator();
    private final Map<String, byte[]> files = new TreeMap<>();
    private final List<Map<String, Object>> vectors = new ArrayList<>();

    public static void generate(Path outDir) throws IOException {
        new NegativeVectorGenerator().run(outDir);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: NegativeVectorGenerator <outDir>");
            System.exit(2);
        }
        generate(Path.of(args[0]));
    }

    private static ObjectNode example(String name) throws IOException {
        try (InputStream in = NegativeVectorGenerator.class.getResourceAsStream("/json-schema/v1/examples/" + name)) {
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    private static JsonNode schema(String name) throws IOException {
        try (InputStream in = NegativeVectorGenerator.class.getResourceAsStream("/json-schema/v1/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    private void run(Path out) throws IOException {
        liveMeasurementVectors();
        captureVectors();
        batchVectors();

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("vectorSet", "negative");
        manifest.put("version", VERSION);
        manifest.put("description", "Payloads the server must reject. Every vector expects HTTP 422 with code CLIENT_MEASUREMENT_INVALID, except kind batchItems (a valid envelope: per-item results in expectedItems, and the text each invalid item's errors must contain in expectedItemErrorContains) and kind hintsComplete (the clientMeasurements array of uploads/complete: the upload is never rejected, a body-level violation drops all hints and an item-level one only that item, with the warning CLIENT_HINTS_DROPPED in expectedWarnings). A single document nested deeper than 8 is refused before it is materialised, including the over-100k-deep body, which a consumer must reject without exhausting its stack. In a batch, a body deeper than the transport ceiling of 32 (batch-body-depth-33) is refused as a whole, while an item deeper than 8 but within the ceiling is INVALID on its own (batch-item-depth-9, kind batchItems). An unsupported schemaVersion is INVALID with code CLIENT_SCHEMA_UNSUPPORTED (batch-item-schema-version-unsupported).");
        manifest.put("vectors", vectors);
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", e.getKey());
            f.put("sha256", ConformanceVectorGenerator.sha256(e.getValue()));
            f.put("bytes", e.getValue().length);
            list.add(f);
        }
        manifest.put("files", list);
        files.put("manifest.json", json(manifest));
        Files.createDirectories(out);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Files.write(out.resolve(e.getKey()), e.getValue());
        }
    }

    // ---- LiveMeasurement ----------------------------------------------------------------------------------

    private void liveMeasurementVectors() throws IOException {
        // missing required fields: one vector per required field of the schema
        for (JsonNode f : schema("live-measurement.schema.json").get("required")) {
            String field = f.asText();
            live("missing-" + field, "required field " + field + " is absent", field, n -> n.remove(field));
        }
        live("unknown-field-top-level", "unknown top-level field (additionalProperties false)", "additionalProperties", n -> n.put("extra", 1));
        live("unknown-field-nested-dimensions", "unknown field inside a dimension value", "additionalProperties",
                n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("extra", 1));
        live("unknown-field-nested-device", "unknown field inside device", "additionalProperties", n -> ((ObjectNode) n.get("device")).put("extra", "x"));
        live("unknown-field-nested-frame-of-reference", "unknown field inside frameOfReference", "additionalProperties",
                n -> ((ObjectNode) n.get("frameOfReference")).put("extra", "x"));
        live("unknown-field-nested-obb", "unknown field inside obb", "additionalProperties", n -> ((ObjectNode) n.get("obb")).put("extra", 1));
        live("unknown-field-nested-keypoint", "unknown field inside a keypoint", "additionalProperties",
                n -> ((ObjectNode) n.get("overlay").get("keypoints").get(0)).put("extra", 1));

        // unknown enum symbols
        live("unknown-enum-mode", "mode is not an OBJECT_BOX / POINT_TO_POINT / PLANE_DISTANCE symbol", "mode", n -> n.put("mode", "VOLUME"));
        live("unknown-enum-state", "state is not a known symbol", "state", n -> n.put("state", "FROZEN"));
        live("unknown-enum-depth-tier", "depthTier is not A, B, C or D", "depthTier", n -> n.put("depthTier", "E"));
        live("unknown-enum-depth-source", "depthSource is not a known symbol", "depthSource", n -> n.put("depthSource", "RADAR"));
        live("unknown-enum-scale-source", "scaleSource is not a known symbol", "scaleSource", n -> n.put("scaleSource", "GUESS"));
        live("unknown-enum-quality-flag", "qualityFlags contains an unknown symbol", "qualityFlags",
                n -> ((ArrayNode) n.get("qualityFlags")).add("NEW_FLAG"));
        live("unknown-enum-unit", "unit must be METRE", "unit", n -> n.put("unit", "FOOT"));
        live("unknown-enum-frame-convention", "frameOfReference.convention must be Y_UP_RIGHT_HANDED", "convention",
                n -> ((ObjectNode) n.get("frameOfReference")).put("convention", "Z_UP"));

        // trust, ranges, types, formats
        live("wrong-trust", "trust must be UNVERIFIED_ESTIMATE on client records", "trust", n -> n.put("trust", "VERIFIED_ESTIMATE"));
        live("confidence-above-1", "confidence must be within 0..1", "confidence", n -> n.put("confidence", 1.01));
        live("confidence-negative", "confidence must be within 0..1", "confidence", n -> n.put("confidence", -0.01));
        live("confidence-wrong-type", "confidence must be a number", "confidence", n -> n.put("confidence", "high"));
        live("vio-metric-confidence-above-cap", "VIO_METRIC caps confidence at 0.6", "confidence",
                n -> n.put("scaleSource", "VIO_METRIC").put("confidence", 0.95));
        live("negative-dimension-value", "dimension values are metres >= 0", "value",
                n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", -0.1));
        live("timestamp-not-date-time", "timestamp must be an RFC 3339 date-time", "timestamp", n -> n.put("timestamp", "yesterday"));
        live("client-measurement-id-not-uuid", "clientMeasurementId must be a UUID", "clientMeasurementId", n -> n.put("clientMeasurementId", "not-a-uuid"));
        live("algorithm-version-not-semver", "algorithmVersion must be MAJOR.MINOR.PATCH", "algorithmVersion", n -> n.put("algorithmVersion", "1.1"));
        live("object-box-without-height", "OBJECT_BOX requires lengthM, widthM and heightM", "dimensions",
                n -> ((ObjectNode) n.get("dimensions")).remove("heightM"));
        live("object-box-with-distance", "OBJECT_BOX must not carry distanceM", "dimensions",
                n -> ((ObjectNode) n.get("dimensions")).set("distanceM", n.get("dimensions").get("lengthM")));
        live("point-to-point-with-box-dimensions", "POINT_TO_POINT carries distanceM only", "dimensions", n -> n.put("mode", "POINT_TO_POINT"));

        // keypoints: 33 is one over the limit of 32
        live("keypoints-33", "overlay.keypoints holds at most 32 items", "keypoints", n -> {
            ArrayNode kp = (ArrayNode) n.get("overlay").get("keypoints");
            ObjectNode first = (ObjectNode) kp.get(0);
            while (kp.size() < 33) {
                kp.add(first.deepCopy());
            }
        });

        // depth: 9 levels is one over the limit of 8; the over-100k-deep body must not exhaust the parser
        live("depth-9", "JSON depth 9, the limit is 8", "JSON depth above 8", n -> n.set("x", nested(8)));
        raw("depth-100001", "liveMeasurement", "JSON depth above 8",
                "nested " + OVER_LIMIT_DEPTH + " levels deep (a parser must refuse it without exhausting its stack)",
                "{\"x\":" + "[".repeat(OVER_LIMIT_DEPTH) + "]".repeat(OVER_LIMIT_DEPTH) + "}");

        numericLimitVectors();
        geometryVectors();
        parserVectors();

        // not an object
        raw("not-json", "liveMeasurement", "not valid JSON", "the body is not JSON", "{\"schemaVersion\": ");
        raw("root-is-array", "liveMeasurement", "JSON object", "the document must be a JSON object", "[]");
    }

    /** An object nested {@code levels} deep: levels 8 below the root object gives total depth 9. */
    private static ObjectNode nested(int levels) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode cur = root;
        for (int i = 1; i < levels; i++) {
            cur = cur.putObject("a");
        }
        cur.put("a", 1);
        return root;
    }

    private void live(String id, String reason, String token, Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode n = example("live-measurement-object-box.json");
        mutation.accept(n);
        raw(id, "liveMeasurement", token, reason, n.toString());
    }

    /** A mutation of the compact JSON text of the example (for a number a tree cannot hold, such as 1e999). */
    private void liveText(String id, String reason, String token, java.util.function.UnaryOperator<String> mutation) throws IOException {
        String text = example("live-measurement-object-box.json").toString();
        String changed = mutation.apply(text);
        if (changed.equals(text)) {
            throw new IllegalStateException(id + ": the mutation did not change the example");
        }
        raw(id, "liveMeasurement", token, reason, changed);
    }

    // ---- clientCapture ------------------------------------------------------------------------------------

    private void captureVectors() throws IOException {
        for (JsonNode f : schema("client-capture.schema.json").get("required")) {
            String field = f.asText();
            ObjectNode n = example("client-capture.json");
            n.remove(field);
            raw("capture-missing-" + field, "clientCapture", field, "required field " + field + " is absent", n.toString());
        }
        ObjectNode unknown = example("client-capture.json");
        unknown.put("extra", true);
        raw("capture-unknown-field", "clientCapture", "additionalProperties", "unknown field (additionalProperties false)", unknown.toString());
        ObjectNode tier = example("client-capture.json");
        tier.put("depthTier", "Z");
        raw("capture-unknown-enum-depth-tier", "clientCapture", "depthTier", "depthTier is not A, B, C or D", tier.toString());
    }

    // ---- batch --------------------------------------------------------------------------------------------

    private void batchVectors() throws IOException {
        ObjectNode good = example("live-measurement-point-to-point.json");
        StringBuilder over = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 101; i++) {
            over.append(i == 0 ? "" : ",").append(good);
        }
        raw("batch-101-items", "batch", "items", "the batch holds at most 100 items", over.append("]}").toString());
        raw("batch-empty", "batch", "items", "the batch holds at least one item", "{\"items\":[]}");
        raw("batch-unknown-envelope-field", "batch", "batch envelope", "unknown field next to items", "{\"x\":1,\"items\":[" + good + "]}");
        raw("batch-body-depth-33", "batch", "JSON depth above 32",
                "a body nested deeper than the transport ceiling of 32 is refused as a whole before it is materialised",
                "{\"items\":[" + good + ",{\"x\":" + "[".repeat(40) + "]".repeat(40) + "}]}");
        raw("batch-over-400k-characters", "batch", "document larger than",
                "a batch body over 409600 characters is refused as a whole", "{\"items\":[]}" + " ".repeat(ClientMeasurementValidator.MAX_BATCH_CHARS));
        batchItems("batch-item-depth-9", "an item nested 9 levels deep (within the transport ceiling) is INVALID on its own; the other items are processed",
                "{\"items\":[" + good + "," + example("live-measurement-point-to-point.json").set("x", nested(8)) + "," + good + "]}",
                items("VALID", "CLIENT_MEASUREMENT_INVALID", "VALID"), items("", "JSON depth above 8", ""));
        ObjectNode future = good.deepCopy();
        future.put("schemaVersion", "2.0");
        batchItems("batch-item-schema-version-unsupported", "an item with an unsupported schemaVersion is INVALID with code CLIENT_SCHEMA_UNSUPPORTED, not persisted (the Kit retries after an update)",
                "{\"items\":[" + good + "," + future + "]}", items("VALID", "CLIENT_SCHEMA_UNSUPPORTED"), items("", "schemaVersion is not supported"));

        // a valid envelope: each item is judged on its own and a bad one does not fail the batch
        ObjectNode unknownField = good.deepCopy();
        unknownField.put("extra", 1);
        ObjectNode badEnum = good.deepCopy();
        badEnum.put("depthTier", "E");
        ObjectNode other = good.deepCopy();
        other.put("clientMeasurementId", "9a0b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d");
        String body = "{\"items\":[" + good + "," + unknownField + "," + other + "," + badEnum + "]}";
        batchItems("batch-per-item-rejection", "valid envelope, items 2 and 4 are invalid: the batch is answered per item (INVALID) and the valid items are processed",
                body, items("VALID", "CLIENT_MEASUREMENT_INVALID", "VALID", "CLIENT_MEASUREMENT_INVALID"),
                items("", "additionalProperties at $", "", "enum at $.depthTier"));
        hintsCompleteVectors(good);
    }

    private static String[] items(String... values) {
        return values;
    }

    /**
     * A batchItems vector: {@code expected} is the code of each item, or VALID; {@code tokens} the text each invalid item's
     * errors must contain ("" for a valid item), so an item is rejected for the stated reason and not for another one that
     * happens to hold too. The reference must agree with both.
     */
    private void batchItems(String id, String reason, String body, String[] expected, String[] tokens) throws IOException {
        ClientMeasurementValidator.BatchResult results = validator.validateBatchItems(body);
        if (!results.accepted() || results.items.size() != expected.length || tokens.length != expected.length) {
            throw new IllegalStateException(id + ": request refused or wrong item count: " + results.request.errors);
        }
        checkItems(id, results.items, expected, tokens);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("kind", "batchItems");
        v.put("path", id + ".json");
        v.put("reason", reason);
        v.put("expectedItems", List.of(expected));
        v.put("expectedItemErrorContains", List.of(tokens));
        vectors.add(v);
        files.put(id + ".json", json(MAPPER.readTree(body)));
    }

    private static void checkItems(String id, List<Result> actualItems, String[] expected, String[] tokens) {
        for (int i = 0; i < expected.length; i++) {
            Result r = actualItems.get(i);
            String actual = r.valid() ? "VALID" : r.code;
            if (!actual.equals(expected[i])) {
                throw new IllegalStateException(id + ": item " + i + " is " + actual + ", intended " + expected[i] + " " + r.errors);
            }
            if (!String.join("\n", r.errors).contains(tokens[i])) {
                throw new IllegalStateException(id + ": item " + i + " has no error containing '" + tokens[i] + "': " + r.errors);
            }
        }
    }

    // ---- uploads/complete: clientMeasurements (validateCompleteHints) ---------------------------------------------

    /**
     * The upload itself is never rejected for bad hints: a body-level violation drops all hints, an item-level one only that
     * item, and either way the response is 202 VALIDATING with CLIENT_HINTS_DROPPED.
     */
    private void hintsCompleteVectors(ObjectNode good) throws IOException {
        ObjectNode badEnum = good.deepCopy();
        badEnum.put("depthTier", "E");
        ObjectNode future = good.deepCopy();
        future.put("schemaVersion", "2.0");
        ObjectNode deep = good.deepCopy();
        deep.set("x", nested(8));
        String fifty = "[" + "{},".repeat(49) + "{}]";
        String[] fiftyCodes = new String[50];
        String[] fiftyTokens = new String[50];
        java.util.Arrays.fill(fiftyCodes, "CLIENT_MEASUREMENT_INVALID");
        java.util.Arrays.fill(fiftyTokens, "required at $");
        hintsComplete("hints-complete-all-valid", "valid hints: nothing is dropped and there is no warning",
                "[" + good + "]", null, items("VALID"), items(""));
        hintsComplete("hints-complete-empty-array", "an empty clientMeasurements array is fine: nothing to drop, no warning", "[]", null, items(), items());
        hintsComplete("hints-complete-item-dropped", "one invalid item is dropped on its own, the others are kept, the upload is accepted with CLIENT_HINTS_DROPPED",
                "[" + good + "," + badEnum + "," + good + "]", null, items("VALID", "CLIENT_MEASUREMENT_INVALID", "VALID"), items("", "enum at $.depthTier", ""));
        hintsComplete("hints-complete-item-schema-unsupported", "an item with an unsupported schemaVersion is dropped with CLIENT_SCHEMA_UNSUPPORTED",
                "[" + future + "]", null, items("CLIENT_SCHEMA_UNSUPPORTED"), items("schemaVersion is not supported"));
        hintsComplete("hints-complete-item-depth-9", "an item nested 9 levels deep is dropped on its own (the body is within the transport ceiling of 32)",
                "[" + good + "," + deep + "]", null, items("VALID", "CLIENT_MEASUREMENT_INVALID"), items("", "JSON depth above 8"));
        hintsComplete("hints-complete-item-not-an-object", "an array element that is not an object is dropped", "[1]", null,
                items("CLIENT_MEASUREMENT_INVALID"), items("batch item must be a JSON object"));
        hintsComplete("hints-complete-50-items", "50 items is the limit: the body is accepted and each item is judged on its own", fifty, null, fiftyCodes, fiftyTokens);
        hintsComplete("hints-complete-51-items", "51 items: a body-level violation, all hints are dropped, the upload is still accepted",
                "[" + "{},".repeat(50) + "{}]", "at most 50", items(), items());
        hintsComplete("hints-complete-not-an-array", "clientMeasurements that is not an array: all hints are dropped", "{\"a\":1}", "must be an array", items(), items());
        hintsComplete("hints-complete-body-depth-33", "a body nested deeper than the transport ceiling of 32: all hints are dropped",
                "[" + good + ",{\"x\":" + "[".repeat(40) + "]".repeat(40) + "}]", "JSON depth above 32", items(), items());
        hintsComplete("hints-complete-duplicate-key", "a duplicate object key anywhere: all hints are dropped",
                "[" + good.toString().replaceFirst("\\{", "{\"mode\":\"x\",") + "]", "duplicate object key", items(), items());
        hintsComplete("hints-complete-not-json", "not JSON: all hints are dropped, the upload is still accepted", "[", "not valid JSON", items(), items());
    }

    /**
     * A hintsComplete vector: the payload is the clientMeasurements array. {@code requestToken} is the text of the body-level error
     * when all hints are dropped (null: the body is accepted and each item is judged on its own).
     */
    private void hintsComplete(String id, String reason, String body, String requestToken, String[] expected, String[] tokens) throws IOException {
        ClientMeasurementValidator.HintsResult r = validator.validateCompleteHints(body);
        boolean allDropped = requestToken != null;
        if (r.allDropped() != allDropped || (allDropped && !String.join("\n", r.request.errors).contains(requestToken))) {
            throw new IllegalStateException(id + ": body-level result differs from the intended one: " + r.request.errors);
        }
        if (r.items.size() != expected.length || tokens.length != expected.length) {
            throw new IllegalStateException(id + ": wrong item count " + r.items.size());
        }
        checkItems(id, r.items, expected, tokens);
        boolean dropped = allDropped || java.util.Arrays.stream(expected).anyMatch(e -> !e.equals("VALID"));
        if (!r.warnings().equals(dropped ? List.of(ClientMeasurementValidator.WARNING_HINTS_DROPPED) : List.of())) {
            throw new IllegalStateException(id + ": warnings " + r.warnings());
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("kind", "hintsComplete");
        v.put("path", id + ".json");
        v.put("reason", reason);
        v.put("expectedUploadRejected", false);
        v.put("expectedAllHintsDropped", allDropped);
        if (allDropped) {
            v.put("expectedRequestErrorContains", requestToken);
        }
        v.put("expectedItems", List.of(expected));
        v.put("expectedItemErrorContains", List.of(tokens));
        v.put("expectedWarnings", r.warnings());
        vectors.add(v);
        files.put(id + ".json", (body + "\n").getBytes(StandardCharsets.UTF_8));
    }

    // ---- numeric limits (F1, F6, S8), geometry (F3), parser strictness and limits (S6, S7) -----------------------

    private void numericLimitVectors() throws IOException {
        // values the 25.3 NUMERIC(9,5) columns cannot hold, just over the maximum 9999.99999
        live("value-over-maximum", "dimension value above 9999.99999 m does not fit NUMERIC(9,5)", "maximum",
                n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", 10000));
        liveText("value-just-over-maximum", "9999.99999 is the largest value, 10000.00001 is rejected", "maximum",
                t -> t.replace("\"value\":0.602", "\"value\":10000.00001"));
        live("sigma-over-maximum", "sigmaM above 9999.99999", "maximum", n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("sigmaM", 10000));
        live("ci95-over-maximum", "ci95M above 9999.99999", "maximum", n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("ci95M", 10000));
        live("value-1e308", "a huge finite value", "maximum", n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("value", 1e308));
        liveText("value-1e999-overflows-a-double", "1e999 overflows a double to Infinity", "finiteNumber",
                t -> t.replace("\"value\":0.602", "\"value\":1e999"));
        liveText("value-negative-1e999", "-1e999 overflows a double to -Infinity", "finiteNumber",
                t -> t.replace("\"value\":0.602", "\"value\":-1e999"));
        liveText("yaw-1e999", "an overflowing number anywhere is rejected, not only in dimensions", "finiteNumber",
                t -> t.replace("\"yawRad\":0.35", "\"yawRad\":1e999"));
        live("half-extent-over-maximum", "obb.halfExtentsM above 9999.99999", "maximum",
                n -> ((ArrayNode) n.get("obb").get("halfExtentsM")).set(0, MAPPER.getNodeFactory().numberNode(10000)));
        live("world-coordinate-over-maximum", "world coordinates are within +-100000 m", "maximum",
                n -> ((ObjectNode) n.get("obb")).putArray("centerWorld").add(0).add(0).add(100001));
        live("algorithm-version-major-over-int", "algorithm_major is an INT column: 2147483648 does not fit", "algorithmVersion",
                n -> n.put("algorithmVersion", "2147483648.0.0"));
        live("algorithm-version-ten-digit-component", "version components stay below 10^9", "algorithmVersion", n -> n.put("algorithmVersion", "1.1000000000.0"));
        live("algorithm-version-over-16-characters", "algorithmVersion is at most 16 characters", "algorithmVersion", n -> n.put("algorithmVersion", "999999999.999999999.9"));
        ObjectNode capture = example("client-capture.json");
        capture.put("algorithmVersion", "1.2.2147483648");
        raw("capture-algorithm-version-patch-over-int", "clientCapture", "algorithmVersion", "algorithm_patch is an INT column", capture.toString());
        live("epoch-over-int", "worldOriginEpoch is an INT column", "worldOriginEpoch",
                n -> ((ObjectNode) n.get("frameOfReference")).put("worldOriginEpoch", 2147483648L));
    }

    private void geometryVectors() throws IOException {
        pointToPoint("geometry-endpoints-on-object-box", "OBJECT_BOX derives its box from obb and carries no endpoints", "geometry",
                n -> n.put("mode", "OBJECT_BOX"), true);
        pointToPoint("geometry-plane-on-point-to-point", "POINT_TO_POINT does not carry a plane", "geometry",
                n -> ((ObjectNode) n.get("geometry")).putObject("plane").put("offsetM", 0).putArray("normalWorld").add(0).add(1).add(0), false);
        pointToPoint("geometry-three-endpoints", "endpointsWorld holds exactly two points", "endpointsWorld",
                n -> ((ArrayNode) n.get("geometry").get("endpointsWorld")).addArray().add(0).add(0).add(0), false);
        pointToPoint("geometry-unknown-field", "unknown field inside geometry", "additionalProperties",
                n -> ((ObjectNode) n.get("geometry")).put("extra", 1), false);
        pointToPoint("geometry-endpoint-coordinate-over-maximum", "endpoint coordinates are within +-100000 m", "maximum",
                n -> ((ArrayNode) n.get("geometry").get("endpointsWorld").get(0)).set(0, MAPPER.getNodeFactory().numberNode(100001)), false);
        plane("plane-normal-not-unit", "the plane normal must have unit length within 1e-3", "unitLength",
                n -> ((ObjectNode) n.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0.9).add(0));
        plane("plane-normal-length-0.9989", "the plane normal length may differ from 1 by at most 1e-3: 0.9989 is just outside (0.9991 is accepted, see the examples)", "unitLength",
                n -> ((ObjectNode) n.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0.9989).add(0));
        plane("plane-normal-zero-length", "a zero-length plane normal is not a plane", "unitLength",
                n -> ((ObjectNode) n.get("geometry").get("plane")).putArray("normalWorld").add(0).add(0).add(0));
        plane("plane-normal-component-over-1", "a unit vector has components within -1..1", "maximum",
                n -> ((ObjectNode) n.get("geometry").get("plane")).putArray("normalWorld").add(0).add(1.5).add(0));
        plane("plane-missing-offset", "a plane has normalWorld and offsetM", "offsetM", n -> ((ObjectNode) n.get("geometry").get("plane")).remove("offsetM"));
        plane("plane-on-object-box-mode", "OBJECT_BOX does not carry a plane", "geometry",
                n -> n.put("mode", "OBJECT_BOX").set("dimensions", example0("live-measurement-object-box.json").get("dimensions")));
    }

    private static ObjectNode example0(String name) {
        try {
            return example(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pointToPoint(String id, String reason, String token, Consumer<ObjectNode> mutation, boolean boxDimensions) throws IOException {
        ObjectNode n = example("live-measurement-point-to-point.json");
        mutation.accept(n);
        if (boxDimensions) {
            n.set("dimensions", example("live-measurement-object-box.json").get("dimensions"));
        }
        raw(id, "liveMeasurement", token, reason, n.toString());
    }

    private void plane(String id, String reason, String token, Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode n = example("live-measurement-plane-distance.json");
        mutation.accept(n);
        raw(id, "liveMeasurement", token, reason, n.toString());
    }

    private void parserVectors() throws IOException {
        String valid = example("live-measurement-object-box.json").toString();
        String base = valid.substring(0, valid.length() - 1);
        raw("duplicate-key-top-level", "liveMeasurement", "duplicate object key",
                "the key confidence appears twice (first 0.87, last 0.5): parsers that keep the first and the last would disagree, so it is rejected",
                base + ",\"confidence\":0.5}");
        raw("duplicate-key-nested", "liveMeasurement", "duplicate object key", "a key repeated inside dimensions.lengthM",
                valid.replace("\"lengthM\":{\"value\":0.602", "\"lengthM\":{\"value\":0.602,\"value\":9"));
        raw("duplicate-key-hides-a-bad-value", "liveMeasurement", "duplicate object key",
                "the first confidence (5) is invalid and the last is valid: a last-wins parser would accept it",
                valid.replace("\"confidence\":0.87", "\"confidence\":5,\"confidence\":0.5"));
        raw("trailing-content-second-document", "liveMeasurement", "trailing content", "a second JSON document follows the valid one", valid + " {\"evil\":1}");
        raw("trailing-content-second-document-no-space", "liveMeasurement", "trailing content", "a second JSON document directly after the valid one", valid + "{}");
        raw("trailing-content-garbage", "liveMeasurement", "not valid JSON", "garbage follows the valid document", valid + " xx");
        raw("trailing-content-comma", "liveMeasurement", "not valid JSON", "a trailing comma after the document", valid + ",");
        raw("empty-body", "liveMeasurement", "not valid JSON", "an empty body", "");
        raw("whitespace-only-body", "liveMeasurement", "not valid JSON", "a body of whitespace only", "  \n ");
        raw("nan-literal", "liveMeasurement", "not valid JSON", "NaN is not a JSON number", valid.replace("\"confidence\":0.87", "\"confidence\":NaN"));
        raw("infinity-literal", "liveMeasurement", "not valid JSON", "Infinity is not a JSON number", valid.replace("\"confidence\":0.87", "\"confidence\":Infinity"));
        raw("body-over-256k-characters", "liveMeasurement", "document larger than",
                "a document over 262144 characters is refused before parsing (whitespace keeps the vector small)", valid + " ".repeat(ClientMeasurementValidator.MAX_DOCUMENT_CHARS));
        raw("string-over-4096-characters", "liveMeasurement", "string value longer than", "a string value over 4096 characters (the 21-million-character case at a testable size)",
                valid.replace("\"model\":\"iPhone15,3\"", "\"model\":\"" + "a".repeat(4097) + "\""));
        raw("schema-version-over-4096-characters", "liveMeasurement", "string value longer than", "a schemaVersion of 4097 characters is refused, never echoed back",
                valid.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"" + "9".repeat(4097) + "\""));
        raw("number-over-32-characters", "liveMeasurement", "number longer than", "a number of 42 characters",
                valid.replace("\"confidence\":0.87", "\"confidence\":0." + "1".repeat(40)));
        raw("name-over-128-characters", "liveMeasurement", "name longer than", "an object key of 129 characters",
                base + ",\"" + "k".repeat(129) + "\":1}");
    }

    // ---- plumbing -----------------------------------------------------------------------------------------

    private void raw(String id, String kind, String token, String reason, String payload) throws IOException {
        Result r = validate(kind, payload);
        if (r.valid() || !ClientMeasurementValidator.CODE_INVALID.equals(r.code)) {
            throw new IllegalStateException(id + ": reference validator did not reject with CLIENT_MEASUREMENT_INVALID: " + r.code);
        }
        if (r.errors.stream().noneMatch(e -> e.contains(token))) {
            throw new IllegalStateException(id + ": no error contains '" + token + "': " + r.errors);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("kind", kind);
        v.put("path", id + ".json");
        v.put("reason", reason);
        v.put("expectedStatus", 422);
        v.put("expectedCode", ClientMeasurementValidator.CODE_INVALID);
        v.put("expectedErrorContains", token);
        vectors.add(v);
        files.put(id + ".json", (payload + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private Result validate(String kind, String payload) {
        switch (kind) {
            case "clientCapture":
                return validator.validateClientCapture(payload);
            case "batch":
                return validator.validateBatch(payload);
            default:
                return validator.validateLiveMeasurement(payload);
        }
    }

    private static byte[] json(Object o) throws IOException {
        return (MAPPER.writer(PRETTY).writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
