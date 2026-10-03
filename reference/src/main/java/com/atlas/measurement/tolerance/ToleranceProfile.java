package com.atlas.measurement.tolerance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A parsed {@code tolerance-profile.json}. Thresholds come only from the file, never from constants. */
public final class ToleranceProfile {
    /** Classpath location of one released profile, bundled from the repository's tolerance/&lt;version&gt;/ folders. */
    public static String classpathResource(String version) {
        return "/tolerance/" + version + "/tolerance-profile.json";
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String version;
    private final Map<String, ToleranceRow> rows;
    private final JsonNode overrides;

    private ToleranceProfile(String version, Map<String, ToleranceRow> rows, JsonNode overrides) {
        this.version = version;
        this.rows = rows;
        this.overrides = overrides;
    }

    /**
     * One released profile by version. Every released version stays bundled, so vectors released against 1.0.0 keep
     * replaying against 1.0.0 after a retune publishes 1.1.0 (a vector manifest names its toleranceProfileVersion).
     */
    public static ToleranceProfile loadBundled(String version) throws IOException {
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IOException("not a profile version: " + version);
        }
        try (InputStream in = ToleranceProfile.class.getResourceAsStream(classpathResource(version))) {
            if (in == null) {
                throw new IOException("missing classpath resource " + classpathResource(version));
            }
            ToleranceProfile profile = parse(MAPPER.readTree(in));
            if (!profile.version().equals(version)) {
                throw new IOException("profile folder " + version + " holds version " + profile.version());
            }
            return profile;
        }
    }

    public static ToleranceProfile load(Path file) throws IOException {
        return parse(MAPPER.readTree(Files.readAllBytes(file)));
    }

    public static ToleranceProfile parse(JsonNode root) {
        Map<String, ToleranceRow> rows = new LinkedHashMap<>();
        for (JsonNode r : root.required("rows")) {
            ToleranceRow row = new ToleranceRow(
                    r.required("name").asText(),
                    r.required("floorM").asDouble(),
                    r.required("relFrac").asDouble(),
                    nullableDouble(r.required("sigmaK")),
                    nullableDouble(r.required("sigmaCapM")));
            rows.put(row.name, row);
        }
        return new ToleranceProfile(root.required("version").asText(), rows, root.required("overrides"));
    }

    public String version() {
        return version;
    }

    /** Effective row for {@code rowName} and {@code algorithmMajor}: field-wise override over the base row. */
    public ToleranceRow row(String rowName, int algorithmMajor) {
        ToleranceRow base = rows.get(rowName);
        if (base == null) {
            throw new IllegalArgumentException("unknown tolerance row: " + rowName);
        }
        JsonNode o = overrides.path(Integer.toString(algorithmMajor)).path(rowName);
        if (o.isMissingNode()) {
            return base;
        }
        return new ToleranceRow(
                rowName,
                o.has("floorM") ? o.get("floorM").asDouble() : base.floorM,
                o.has("relFrac") ? o.get("relFrac").asDouble() : base.relFrac,
                o.has("sigmaK") ? nullableDouble(o.get("sigmaK")) : base.sigmaK,
                o.has("sigmaCapM") ? nullableDouble(o.get("sigmaCapM")) : base.sigmaCapM);
    }

    private static Double nullableDouble(JsonNode n) {
        return n.isNull() ? null : n.asDouble();
    }
}
