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
 * is 422) and {@code batchItems} (200 with per-item results, expected list in {@code expectedItems}).
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
        manifest.put("description", "Payloads the server must reject. Every vector expects HTTP 422 with code CLIENT_MEASUREMENT_INVALID, except kind batchItems (a valid envelope: per-item results in expectedItems). A body nested deeper than 8 is refused as a whole before it is materialised, including the over-100k-deep body, which a consumer must reject without exhausting its stack.");
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
        live("unknown-field-top-level", "unknown top-level field (additionalProperties false)", "extra", n -> n.put("extra", 1));
        live("unknown-field-nested-dimensions", "unknown field inside a dimension value", "extra",
                n -> ((ObjectNode) n.get("dimensions").get("lengthM")).put("extra", 1));
        live("unknown-field-nested-device", "unknown field inside device", "extra", n -> ((ObjectNode) n.get("device")).put("extra", "x"));
        live("unknown-field-nested-frame-of-reference", "unknown field inside frameOfReference", "extra",
                n -> ((ObjectNode) n.get("frameOfReference")).put("extra", "x"));
        live("unknown-field-nested-obb", "unknown field inside obb", "extra", n -> ((ObjectNode) n.get("obb")).put("extra", 1));
        live("unknown-field-nested-keypoint", "unknown field inside a keypoint", "extra",
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
        raw("capture-unknown-field", "clientCapture", "extra", "unknown field (additionalProperties false)", unknown.toString());
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
        raw("batch-unknown-envelope-field", "batch", "x", "unknown field next to items", "{\"x\":1,\"items\":[" + good + "]}");
        raw("batch-item-depth-9", "batch", "JSON depth above 8",
                "an item nested 9 levels deep fails the whole request (the parser refuses it before materialising it)",
                "{\"items\":[" + good + "," + example("live-measurement-point-to-point.json").set("x", nested(8)) + "]}");

        // a valid envelope: each item is judged on its own and a bad one does not fail the batch
        ObjectNode unknownField = good.deepCopy();
        unknownField.put("extra", 1);
        ObjectNode badEnum = good.deepCopy();
        badEnum.put("depthTier", "E");
        ObjectNode other = good.deepCopy();
        other.put("clientMeasurementId", "9a0b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d");
        String body = "{\"items\":[" + good + "," + unknownField + "," + other + "," + badEnum + "]}";
        List<String> expected = List.of("VALID", "INVALID", "VALID", "INVALID");
        List<Result> results = validator.validateBatchItems(body);
        for (int i = 0; i < expected.size(); i++) {
            if (results.get(i).valid() != expected.get(i).equals("VALID")) {
                throw new IllegalStateException("batch-per-item-rejection: item " + i + " " + results.get(i).errors);
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", "batch-per-item-rejection");
        v.put("kind", "batchItems");
        v.put("path", "batch-per-item-rejection.json");
        v.put("reason", "valid envelope, items 2 and 4 are invalid: the batch is answered per item (INVALID) and the valid items are processed");
        v.put("expectedItems", expected);
        vectors.add(v);
        files.put("batch-per-item-rejection.json", json(MAPPER.readTree(body)));
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
