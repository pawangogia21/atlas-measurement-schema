package com.atlas.measurement.vectorgen;

import com.atlas.measurement.validation.ClientMeasurementValidator;
import com.atlas.measurement.validation.ClientMeasurementValidator.Result;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Generates the client-capture vectors 1.1.0 (design 36.8): the optional {@code clientCapture.worldOriginEpoch}, an
 * integer 0..2147483647 that is the epoch of the AR world frame of the uploaded scan. Valid vectors (absent, 0, the
 * maximum) and invalid ones (negative, one over the maximum, a fractional number, a string). It is a new set, not
 * an edit of negative/1.0.0, because released vector sets are immutable. {@code expectedValid} is true for a
 * payload the intake must accept; an invalid one expects 422 {@code CLIENT_MEASUREMENT_INVALID} with the text
 * {@code expectedErrorContains} in its errors. The generator fails if the reference validator disagrees.
 * The base object of every vector is {@code json-schema/v1/examples/client-capture.json}: editing that example
 * changes the released set's bytes (and the byte-identical test fails).
 * Usage: {@code ClientCaptureEpochVectorGenerator <outDir>} (release layout vectors/client-capture/<version>/).
 */
public final class ClientCaptureEpochVectorGenerator {
    public static final String VERSION = "1.1.0";
    public static final String SET = "client-capture";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    private final ClientMeasurementValidator validator = new ClientMeasurementValidator();
    private final Map<String, byte[]> files = new TreeMap<>();
    private final List<Map<String, Object>> vectors = new ArrayList<>();

    private ClientCaptureEpochVectorGenerator() {}

    public static void generate(Path outDir) throws IOException {
        new ClientCaptureEpochVectorGenerator().run(outDir);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ClientCaptureEpochVectorGenerator <outDir>");
            System.exit(2);
        }
        generate(Path.of(args[0]));
    }

    private void run(Path out) throws IOException {
        valid("epoch-absent", "worldOriginEpoch is optional: an older Kit or a non-AR upload omits it", n -> { });
        valid("epoch-0", "lower bound 0", n -> n.put("worldOriginEpoch", 0));
        valid("epoch-2147483647", "upper bound 2147483647", n -> n.put("worldOriginEpoch", 2147483647));
        invalid("epoch-negative", "below the lower bound", "minimum", n -> n.put("worldOriginEpoch", -1));
        invalid("epoch-2147483648", "one over the upper bound", "maximum", n -> n.put("worldOriginEpoch", 2147483648L));
        invalid("epoch-fractional", "not an integer", "type", n -> n.put("worldOriginEpoch", 1.5));
        invalid("epoch-string", "a string, not an integer", "type", n -> n.put("worldOriginEpoch", "7"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("vectorSet", SET);
        manifest.put("version", VERSION);
        manifest.put("description", "clientCapture.worldOriginEpoch (schema 1.1, design 36.8): optional integer 0..2147483647. Each file is a complete clientCapture object; expectedValid says whether the intake accepts it, otherwise it answers 422 CLIENT_MEASUREMENT_INVALID with expectedErrorContains in the errors.");
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

    private void valid(String id, String reason, Consumer<ObjectNode> mutate) throws IOException {
        String body = body(mutate);
        Result r = validator.validateClientCapture(body);
        if (!r.valid()) {
            throw new IllegalStateException(id + " should be valid: " + r.errors);
        }
        add(id, reason, body, true, null);
    }

    private void invalid(String id, String reason, String token, Consumer<ObjectNode> mutate) throws IOException {
        String body = body(mutate);
        Result r = validator.validateClientCapture(body);
        if (r.valid() || !String.join("\n", r.errors).contains(token)) {
            throw new IllegalStateException(id + " should be invalid with '" + token + "': " + r.errors);
        }
        add(id, reason, body, false, token);
    }

    private static String body(Consumer<ObjectNode> mutate) throws IOException {
        ObjectNode n;
        try (InputStream in = ClientCaptureEpochVectorGenerator.class.getResourceAsStream("/json-schema/v1/examples/client-capture.json")) {
            n = (ObjectNode) MAPPER.readTree(in);
        }
        mutate.accept(n);
        return n.toString();
    }

    private void add(String id, String reason, String body, boolean valid, String token) throws IOException {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("kind", "clientCapture");
        v.put("path", id + ".json");
        v.put("reason", reason);
        v.put("expectedValid", valid);
        if (!valid) {
            v.put("expectedStatus", 422);
            v.put("expectedCode", "CLIENT_MEASUREMENT_INVALID");
            v.put("expectedErrorContains", token);
        }
        vectors.add(v);
        files.put(id + ".json", json(MAPPER.readTree(body)));
    }

    private static byte[] json(Object o) throws IOException {
        return (MAPPER.writer(PRETTY).writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
