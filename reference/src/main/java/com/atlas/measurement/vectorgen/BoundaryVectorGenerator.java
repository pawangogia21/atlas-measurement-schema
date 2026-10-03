package com.atlas.measurement.vectorgen;

import com.atlas.measurement.tolerance.Tolerance;
import com.atlas.measurement.tolerance.Tolerance.DimensionPair;
import com.atlas.measurement.tolerance.Tolerance.Outcome;
import com.atlas.measurement.tolerance.ToleranceProfile;
import com.atlas.measurement.tolerance.ToleranceRow;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates boundary vectors for the tolerance function: exactly at, just below and just above
 * each threshold in each tolerance profile row. Used to verify the tolerance function's accuracy.
 * Usage: {@code BoundaryVectorGenerator <outDir>} (the release layout is vectors/boundary/<version>/).
 */
public final class BoundaryVectorGenerator {
    public static final String VERSION = "1.0.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final double EPSILON = 1e-9;
    private static final double TINY = 1e-10;

    private final Path out;
    private final List<Map<String, Object>> vectors = new ArrayList<>();
    private final Map<String, byte[]> files = new TreeMap<>();

    private BoundaryVectorGenerator(Path out) {
        this.out = out;
    }

    public static void generate(Path outDir) throws IOException, NoSuchAlgorithmException {
        new BoundaryVectorGenerator(outDir).run();
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 1) {
            System.err.println("usage: BoundaryVectorGenerator <outDir>");
            System.exit(1);
        }
        generate(Path.of(args[0]));
    }

    private void run() throws IOException, NoSuchAlgorithmException {
        ToleranceProfile profile = ToleranceProfile.loadBundled();

        // Generate test cases for floor thresholds
        generateFloorBoundaries(profile);

        // Generate test cases for relative thresholds
        generateRelativeBoundaries(profile);

        // Generate test cases for sigma thresholds
        generateSigmaBoundaries(profile);

        // Generate test cases for sigma cap thresholds
        generateSigmaCapBoundaries(profile);

        // Generate rounding edge cases
        generateRoundingEdges();

        // Generate per-dimension classification cases
        generateClassificationCases(profile);

        // Create manifest and write files
        writeManifestAndFiles();
    }

    private void generateFloorBoundaries(ToleranceProfile profile) {
        for (String rowName : new String[]{"accuracyGate", "accuracyGateP95", "agree", "minor"}) {
            ToleranceRow row = profile.row(rowName, 1);
            double floor = row.floorM;

            // Test exactly at floor
            addTolCase(
                    "floor-exact-" + rowName,
                    rowName, 1, floor, 0, 0,
                    floor);

            // Test just below floor (within)
            addTolCase(
                    "floor-just-below-" + rowName,
                    rowName, 1, floor - TINY, 0, 0,
                    floor - TINY);

            // Test just above floor (outside if relative/sigma are smaller)
            addTolCase(
                    "floor-just-above-" + rowName,
                    rowName, 1, floor + TINY, 0, 0,
                    floor + TINY);
        }
    }

    private void generateRelativeBoundaries(ToleranceProfile profile) {
        for (String rowName : new String[]{"accuracyGate", "accuracyGateP95", "agree", "minor"}) {
            ToleranceRow row = profile.row(rowName, 1);

            // Use a reference value where relative term dominates
            double refForRelative = 1.0 / row.relFrac;

            // Test exactly at relative threshold
            addTolCase(
                    "relative-exact-" + rowName,
                    rowName, 1, refForRelative, 0, 0,
                    refForRelative * row.relFrac);

            // Test just below relative threshold
            addTolCase(
                    "relative-just-below-" + rowName,
                    rowName, 1, refForRelative - TINY, 0, 0,
                    (refForRelative - TINY) * row.relFrac);

            // Test just above relative threshold
            addTolCase(
                    "relative-just-above-" + rowName,
                    rowName, 1, refForRelative + TINY, 0, 0,
                    (refForRelative + TINY) * row.relFrac);
        }
    }

    private void generateSigmaBoundaries(ToleranceProfile profile) {
        // Only rows with sigma: agree and minor
        for (String rowName : new String[]{"agree", "minor"}) {
            ToleranceRow row = profile.row(rowName, 1);
            if (row.sigmaK == null) {
                continue;
            }

            // Use a small reference so sigma term dominates (no floor or relative contribution)
            double smallRef = 0.001;

            // Test exactly at sigma threshold (without cap)
            double sigmaTerm = row.sigmaK * 0.07; // combined sigma of 0.05
            addTolCase(
                    "sigma-exact-" + rowName,
                    rowName, 1, smallRef, 0.05, 0.0,
                    sigmaTerm);

            // Test just below sigma threshold
            addTolCase(
                    "sigma-just-below-" + rowName,
                    rowName, 1, smallRef, 0.05 - TINY, 0.0,
                    row.sigmaK * (0.05 - TINY));

            // Test just above sigma threshold
            addTolCase(
                    "sigma-just-above-" + rowName,
                    rowName, 1, smallRef, 0.05 + TINY, 0.0,
                    row.sigmaK * (0.05 + TINY));
        }
    }

    private void generateSigmaCapBoundaries(ToleranceProfile profile) {
        // Only rows with sigma cap: agree and minor
        for (String rowName : new String[]{"agree", "minor"}) {
            ToleranceRow row = profile.row(rowName, 1);
            if (row.sigmaCapM == null || row.sigmaK == null) {
                continue;
            }

            // Use large sigma values to trigger the cap
            double smallRef = 0.001;
            double largeSigma = row.sigmaCapM / row.sigmaK + 0.01; // Larger than cap threshold

            // Test exactly at sigma cap
            addTolCase(
                    "sigma-cap-exact-" + rowName,
                    rowName, 1, smallRef, largeSigma, largeSigma,
                    row.sigmaCapM);

            // Test just below sigma cap
            addTolCase(
                    "sigma-cap-just-below-" + rowName,
                    rowName, 1, smallRef, largeSigma - TINY, largeSigma - TINY,
                    row.sigmaCapM - TINY);

            // Test just above sigma cap (uncapped is larger)
            double uncappedAboveCap = row.sigmaK * Math.sqrt(2 * (largeSigma + TINY) * (largeSigma + TINY));
            addTolCase(
                    "sigma-cap-just-above-" + rowName,
                    rowName, 1, smallRef, largeSigma + TINY, largeSigma + TINY,
                    Math.min(uncappedAboveCap, row.sigmaCapM));
        }
    }

    private void generateRoundingEdges() {
        // Test the 1e-5 rounding boundary
        double onBoundary = 0.02;
        double justBelowRound = 0.020004; // rounds down to 2000
        double justAboveRound = 0.020006; // rounds up to 2001

        // These are for the withinTolerance check
        addWithinCase("rounding-exact", onBoundary, onBoundary, true);
        addWithinCase("rounding-just-below", justBelowRound, onBoundary, true);
        addWithinCase("rounding-just-above", justAboveRound, onBoundary, false);
    }

    private void generateClassificationCases(ToleranceProfile profile) {
        // Test per-dimension classification
        ToleranceRow agree = profile.row("agree", 1);
        ToleranceRow minor = profile.row("minor", 1);

        // Single dimension: exactly at agree threshold
        addClassifyCase("classify-agree-exact", 1,
                new DimensionPair(1.0, 0, 1.02, 0),
                profile, Outcome.AGREE);

        // Single dimension: just below agree threshold
        addClassifyCase("classify-agree-just-below", 1,
                new DimensionPair(1.0, 0, 1.0199, 0),
                profile, Outcome.AGREE);

        // Single dimension: just above agree but within minor
        addClassifyCase("classify-minor-just-over-agree", 1,
                new DimensionPair(1.0, 0, 1.0201, 0),
                profile, Outcome.MINOR_DIFF);

        // Single dimension: exactly at minor threshold
        addClassifyCase("classify-minor-exact", 1,
                new DimensionPair(1.0, 0, 1.05, 0),
                profile, Outcome.MINOR_DIFF);

        // Single dimension: just above minor (major diff)
        addClassifyCase("classify-major-just-over-minor", 1,
                new DimensionPair(1.0, 0, 1.0501, 0),
                profile, Outcome.MAJOR_DIFF);

        // Multiple dimensions: worst dimension decides
        addClassifyCase("classify-worst-dimension-decides", 1,
                new DimensionPair[]{
                    new DimensionPair(1.0, 0, 1.0, 0),
                    new DimensionPair(0.3, 0, 0.36, 0)
                },
                profile, Outcome.MAJOR_DIFF);

        // Sigma widens tolerance: within agree because of sigma
        addClassifyCase("classify-sigma-widens-agree", 1,
                new DimensionPair(1.0, 0.02, 1.05, 0.02),
                profile, Outcome.AGREE);

        // Client below server (symmetric)
        addClassifyCase("classify-client-below-server", 1,
                new DimensionPair(1.0, 0, 0.98, 0),
                profile, Outcome.AGREE);
    }

    private void addTolCase(String id, String rowName, int major, double ref, double sigmaServer,
            double sigmaClient, double expectedTol) {
        ToleranceProfile profile;
        try {
            profile = ToleranceProfile.loadBundled();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ToleranceRow row = profile.row(rowName, major);
        double computed = Tolerance.tol(ref, sigmaServer, sigmaClient, row);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("profile", "base");
        m.put("row", rowName);
        m.put("major", major);
        m.put("ref", ref);
        m.put("sigmaServer", sigmaServer);
        m.put("sigmaClient", sigmaClient);
        m.put("tol", computed);
        vectors.add(m);
    }

    private void addWithinCase(String id, double diff, double tol, boolean expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("diff", diff);
        m.put("tol", tol);
        m.put("within", expected);
        vectors.add(m);
    }

    private void addClassifyCase(String id, int major, DimensionPair dim,
            ToleranceProfile profile, Outcome expected) {
        addClassifyCase(id, major, new DimensionPair[]{dim}, profile, expected);
    }

    private void addClassifyCase(String id, int major, DimensionPair[] dims,
            ToleranceProfile profile, Outcome expected) {
        List<DimensionPair> dimList = new ArrayList<>();
        for (DimensionPair d : dims) {
            dimList.add(d);
        }
        Outcome computed = Tolerance.classify(profile, major, dimList);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("major", major);

        List<Map<String, Object>> dimsMaps = new ArrayList<>();
        for (DimensionPair d : dims) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("server", d.server);
            dm.put("sigmaServer", d.sigmaServer);
            dm.put("client", d.client);
            dm.put("sigmaClient", d.sigmaClient);
            dimsMaps.add(dm);
        }
        m.put("dims", dimsMaps);
        m.put("outcome", computed.name());
        vectors.add(m);
    }

    private void writeManifestAndFiles() throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(out);

        // Write boundary cases file
        ObjectNode boundaryNode = MAPPER.createObjectNode();
        boundaryNode.put("description", "Boundary vectors for tolerance function: exactly at, just below and just above each threshold");
        boundaryNode.put("tolEpsilon", EPSILON);

        // Separate into different array types based on the id prefix
        ArrayNode tolCases = boundaryNode.putArray("tol");
        ArrayNode withinCases = boundaryNode.putArray("within");
        ArrayNode classifyCases = boundaryNode.putArray("classify");

        for (Map<String, Object> v : vectors) {
            String id = (String) v.get("id");
            ObjectNode node = MAPPER.valueToTree(v);

            if (id.startsWith("rounding-")) {
                withinCases.add(node);
            } else if (id.startsWith("classify-")) {
                classifyCases.add(node);
            } else {
                tolCases.add(node);
            }
        }

        byte[] content = MAPPER.writer(PRETTY).writeValueAsBytes(boundaryNode);
        Path boundaryFile = out.resolve("boundary-cases.json");
        Files.write(boundaryFile, content);
        files.put("boundary-cases.json", content);

        // Create manifest
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("version", VERSION);

        ArrayNode filesNode = manifest.putArray("files");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            ObjectNode f = filesNode.addObject();
            f.put("path", e.getKey());
            f.put("sha256", sha256(e.getValue()));
            f.put("bytes", e.getValue().length);
        }

        byte[] manifestContent = MAPPER.writer(PRETTY).writeValueAsBytes(manifest);
        Path manifestFile = out.resolve("manifest.json");
        Files.write(manifestFile, manifestContent);

        System.out.println("Generated boundary vectors to " + out);
        System.out.println("Files: " + files.size() + " vectors: " + vectors.size());
    }

    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
