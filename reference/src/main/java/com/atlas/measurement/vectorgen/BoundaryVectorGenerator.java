package com.atlas.measurement.vectorgen;

import com.atlas.measurement.association.Association;
import com.atlas.measurement.tolerance.Tolerance;
import com.atlas.measurement.tolerance.Tolerance.DimensionPair;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the boundary vectors (design 21.2, 24 item 5): for every profile row and every term of the
 * tolerance function the difference exactly at, just under (-1e-5 m) and just over (+1e-5 m) the threshold;
 * the per-dimension AGREE / MINOR_DIFF / MAJOR_DIFF classification at each threshold; and the association
 * and NOT_COMPARABLE vectors (one per association branch and per reason code).
 *
 * <p>Every case carries an <em>intended</em> result stated here by construction (exact and under are within,
 * over is not; the branch or reason the case is built for). The generator fails if the reference
 * implementation disagrees, so a vector is never merely a recording of the implementation's own output.
 * Usage: {@code BoundaryVectorGenerator <outDir>} (the release layout is vectors/boundary/<version>/).
 */
public final class BoundaryVectorGenerator {
    public static final String VERSION = "1.0.0";
    private static final double STEP = 1e-5;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final String[] ROWS = {"accuracyGate", "accuracyGateP95", "agree", "minor"};

    private final ToleranceProfile profile;
    private final Map<String, byte[]> files = new TreeMap<>();

    private BoundaryVectorGenerator(ToleranceProfile profile) {
        this.profile = profile;
    }

    public static void generate(Path outDir) throws IOException {
        new BoundaryVectorGenerator(ToleranceProfile.loadBundled()).run(outDir);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: BoundaryVectorGenerator <outDir>");
            System.exit(2);
        }
        generate(Path.of(args[0]));
    }

    private void run(Path out) throws IOException {
        ObjectNode cases = MAPPER.createObjectNode();
        cases.put("description", "Boundary vectors for the tolerance function (profile 1.0.0): every row and every term at exactly, just under (-1e-5 m) and just over (+1e-5 m) the threshold, rounding edges and per-dimension classification.");
        cases.put("tolEpsilon", 1e-9);
        ArrayNode threshold = cases.putArray("threshold");
        ArrayNode within = cases.putArray("within");
        ArrayNode classify = cases.putArray("classify");
        thresholds(threshold);
        rounding(within);
        classifyAtThresholds(classify);
        classifyMultiDimension(classify);
        files.put("boundary-cases.json", json(cases));

        ObjectNode assoc = MAPPER.createObjectNode();
        assoc.put("description", "Association and NOT_COMPARABLE vectors (design 21.2): one per association branch (SEEDED, IOU, GEOMETRIC, DIMENSION_ONLY) and per reason code, with the geometric thresholds at exactly, just under and just over. Replayed against the reference implementation (reference/.../association/Association.java); the association is server-side, so Swift and Kotlin do not replay this file.");
        assoc.put("algorithmMajor", 1);
        ArrayNode ac = assoc.putArray("cases");
        new AssociationCases(ac).build();
        files.put("association-cases.json", json(assoc));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("vectorSet", "boundary");
        manifest.put("version", VERSION);
        manifest.put("toleranceProfileVersion", profile.version());
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

    // ---- threshold vectors -------------------------------------------------------------------------------

    /** One term of the tolerance function made dominant: name, ref, sigmaServer, sigmaClient. */
    private static final class Term {
        final String name;
        final double ref;
        final double sigmaServer;
        final double sigmaClient;

        Term(String name, double ref, double sigmaServer, double sigmaClient) {
            this.name = name;
            this.ref = ref;
            this.sigmaServer = sigmaServer;
            this.sigmaClient = sigmaClient;
        }
    }

    private List<Term> terms(ToleranceRow row) {
        List<Term> t = new ArrayList<>();
        t.add(new Term("floor", 0.1, 0, 0));
        t.add(new Term("relative", 10, 0, 0));
        t.add(new Term("floor-relative-crossover", row.floorM / row.relFrac, 0, 0));
        if (row.sigmaK == null) {
            t.add(new Term("sigma-ignored", 0.1, 5, 5));
        } else {
            t.add(new Term("sigma", 0.1, 0.02, 0.02));
            t.add(new Term("sigma-cap", 0.1, 1.0, 1.0));
        }
        return t;
    }

    private void thresholds(ArrayNode out) {
        for (String rowName : ROWS) {
            ToleranceRow row = profile.row(rowName, 1);
            for (Term t : terms(row)) {
                double tol = Tolerance.tol(t.ref, t.sigmaServer, t.sigmaClient, row);
                String[] position = {"exact", "just-under", "just-over"};
                double[] diff = {tol, tol - STEP, tol + STEP};
                boolean[] intended = {true, true, false};
                for (int i = 0; i < 3; i++) {
                    boolean actual = Tolerance.withinTolerance(diff[i], tol);
                    if (actual != intended[i]) {
                        throw new IllegalStateException(rowName + "/" + t.name + "/" + position[i]
                                + ": reference says " + actual + ", intended " + intended[i]);
                    }
                    ObjectNode c = out.addObject();
                    c.put("id", rowName + "-" + t.name + "-" + position[i]);
                    c.put("row", rowName);
                    c.put("major", 1);
                    c.put("term", t.name);
                    c.put("ref", t.ref);
                    c.put("sigmaServer", t.sigmaServer);
                    c.put("sigmaClient", t.sigmaClient);
                    c.put("tol", tol);
                    c.put("diff", diff[i]);
                    c.put("within", intended[i]);
                }
            }
        }
    }

    private void rounding(ArrayNode out) {
        Object[][] cases = {
            {"rounding-exact", 0.02, 0.02, true},
            {"rounding-below-half-unit-rounds-down-to-tol", 0.020004, 0.02, true},
            {"rounding-above-half-unit-rounds-up-past-tol", 0.020006, 0.02, false},
            {"rounding-one-unit-over", 0.02001, 0.02, false},
            {"rounding-one-unit-under", 0.01999, 0.02, true},
            {"rounding-zero-diff", 0.0, 0.02, true},
        };
        for (Object[] c : cases) {
            boolean actual = Tolerance.withinTolerance((double) c[1], (double) c[2]);
            if (actual != (boolean) c[3]) {
                throw new IllegalStateException(c[0] + ": reference says " + actual);
            }
            ObjectNode n = out.addObject();
            n.put("id", (String) c[0]);
            n.put("diff", (double) c[1]);
            n.put("tol", (double) c[2]);
            n.put("within", (boolean) c[3]);
        }
    }

    // ---- classification ----------------------------------------------------------------------------------

    private void classifyAtThresholds(ArrayNode out) {
        for (String rowName : new String[] {"agree", "minor"}) {
            ToleranceRow row = profile.row(rowName, 1);
            for (Term t : terms(row)) {
                double tol = Tolerance.tol(t.ref, t.sigmaServer, t.sigmaClient, row);
                // agree row: at threshold AGREE, just over MINOR_DIFF; minor row: at threshold MINOR_DIFF
                // (it is beyond the agree tolerance of the same dimension), just over MAJOR_DIFF.
                boolean agreeRow = rowName.equals("agree");
                addClassify(out, rowName + "-" + t.name + "-exact", t, tol, agreeRow ? "AGREE" : "MINOR_DIFF");
                addClassify(out, rowName + "-" + t.name + "-just-over", t, tol + STEP, agreeRow ? "MINOR_DIFF" : "MAJOR_DIFF");
                if (agreeRow) {
                    addClassify(out, rowName + "-" + t.name + "-client-below-server", t, -tol, "AGREE");
                }
            }
        }
    }

    private void addClassify(ArrayNode out, String id, Term t, double signedDiff, String intended) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("server", t.ref);
        d.put("sigmaServer", t.sigmaServer);
        d.put("client", t.ref + signedDiff);
        d.put("sigmaClient", t.sigmaClient);
        addClassifyCase(out, id, intended, d);
    }

    private void classifyMultiDimension(ArrayNode out) {
        // per-dimension, not pooled: the large dimension has the larger tolerance, the small one decides
        addClassifyCase(out, "multi-all-dimensions-agree", "AGREE",
                dim(0.3, 0, 0.319), dim(3.0, 0, 3.05), dim(0.2, 0, 0.2));
        addClassifyCase(out, "multi-one-minor-dimension-decides", "MINOR_DIFF",
                dim(0.3, 0, 0.3), dim(0.3, 0, 0.34));
        addClassifyCase(out, "multi-one-major-dimension-decides", "MAJOR_DIFF",
                dim(1.0, 0, 1.0), dim(0.3, 0, 0.36));
        addClassifyCase(out, "multi-sigma-is-per-dimension", "MINOR_DIFF",
                dim(0.5, 0.02, 0.02, 0.55), dim(0.5, 0, 0.53));
    }

    private static ObjectNode dim(double server, double sigmaServer, double client) {
        return dim(server, sigmaServer, 0, client);
    }

    private static ObjectNode dim(double server, double sigmaServer, double sigmaClient, double client) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("server", server);
        d.put("sigmaServer", sigmaServer);
        d.put("client", client);
        d.put("sigmaClient", sigmaClient);
        return d;
    }

    private void addClassifyCase(ArrayNode out, String id, String intended, ObjectNode... dims) {
        List<DimensionPair> list = new ArrayList<>();
        for (ObjectNode d : dims) {
            list.add(new DimensionPair(d.get("server").asDouble(), d.get("sigmaServer").asDouble(),
                    d.get("client").asDouble(), d.get("sigmaClient").asDouble()));
        }
        String actual = Tolerance.classify(profile, 1, list).name();
        if (!actual.equals(intended)) {
            throw new IllegalStateException(id + ": reference says " + actual + ", intended " + intended);
        }
        ObjectNode c = out.addObject();
        c.put("id", id);
        c.put("major", 1);
        ArrayNode a = c.putArray("dims");
        for (ObjectNode d : dims) {
            a.add(d);
        }
        c.put("outcome", intended);
    }

    // ---- association and NOT_COMPARABLE ---------------------------------------------------------------

    private final class AssociationCases {
        private final ArrayNode out;

        AssociationCases(ArrayNode out) {
            this.out = out;
        }

        private ObjectNode ctx() {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("modelDeleted", false);
            c.put("schemaSupported", true);
            c.put("derivedDepthTier", "A");
            c.put("serverState", "READY");
            c.put("frameMapping", true);
            return c;
        }

        private ObjectNode box(String id, double hy, double yaw, double cx) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "OBJECT_BOX");
            ArrayNode dims = m.putArray("dims");
            dims.add(sv(0.4)).add(sv(0.6)).add(sv(2 * hy));
            ObjectNode b = m.putObject("box");
            b.putArray("center").add(cx).add(hy).add(0.0);
            b.putArray("half").add(0.3).add(hy).add(0.2);
            b.put("yaw", yaw);
            return m;
        }

        private ObjectNode sv(double v) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("v", v);
            n.put("s", 0.0);
            return n;
        }

        private ObjectNode p2p(String id, double[] a, double[] b, double distance) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "POINT_TO_POINT");
            m.putArray("dims").add(sv(distance));
            ArrayNode e = m.putArray("endpoints");
            e.addArray().add(a[0]).add(a[1]).add(a[2]);
            e.addArray().add(b[0]).add(b[1]).add(b[2]);
            return m;
        }

        private ObjectNode plane(String id, double angleDeg, double offset) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "PLANE_DISTANCE");
            m.putArray("dims").add(sv(1.0));
            ObjectNode p = m.putObject("plane");
            p.putArray("normal").add(Math.sin(Math.toRadians(angleDeg))).add(Math.cos(Math.toRadians(angleDeg))).add(0.0);
            p.put("offset", offset);
            return m;
        }

        private ObjectNode dimMeasurement(String id, String mode, double... values) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", mode);
            ArrayNode d = m.putArray("dims");
            for (double v : values) {
                d.add(sv(v));
            }
            return m;
        }

        private ArrayNode list(ObjectNode... n) {
            ArrayNode a = MAPPER.createArrayNode();
            for (ObjectNode o : n) {
                a.add(o);
            }
            return a;
        }

        /** Adds a case; {@code expected} is {outcome, reasonCode, method, serverId} per hint. */
        private void add(String id, String branch, ObjectNode ctx, ArrayNode hints, ArrayNode servers, String[]... expected) {
            ArrayNode actual;
            try {
                actual = Association.decide(ctx, hints, servers, profile, 1);
            } catch (RuntimeException e) {
                throw new IllegalStateException(id, e);
            }
            ArrayNode exp = MAPPER.createArrayNode();
            for (int i = 0; i < expected.length; i++) {
                ObjectNode r = MAPPER.createObjectNode();
                r.put("hint", hints.get(i).get("id").asText());
                r.put("outcome", expected[i][0]);
                r.put("reasonCode", expected[i][1]);
                r.put("associationMethod", expected[i][2]);
                r.put("serverMeasurementId", expected[i][3]);
                exp.add(r);
            }
            if (!actual.equals(exp)) {
                throw new IllegalStateException(id + ": reference says " + actual + ", intended " + exp);
            }
            ObjectNode c = out.addObject();
            c.put("id", id);
            c.put("branch", branch);
            c.set("context", ctx);
            c.set("hints", hints);
            c.set("servers", servers);
            c.set("expected", exp);
        }

        private String[] ok(String outcome, String method, String server) {
            return new String[] {outcome, null, method, server};
        }

        private String[] nc(String reason) {
            return new String[] {"NOT_COMPARABLE", reason, null, null};
        }

        void build() {
            // reason codes (precedence: MODEL_DELETED, SCHEMA_UNSUPPORTED, TIER_C, SERVER_FAILED, then per hint)
            ObjectNode h = box("h1", 0.5, 0, 0);
            ObjectNode s = box("s1", 0.5, 0, 0);
            ObjectNode deleted = ctx();
            deleted.put("modelDeleted", true);
            add("reason-model-deleted", "reason", deleted, list(h), list(s), nc("MODEL_DELETED"));
            ObjectNode schema = ctx();
            schema.put("schemaSupported", false);
            add("reason-schema-unsupported", "reason", schema, list(h), list(s), nc("SCHEMA_UNSUPPORTED"));
            ObjectNode tierC = ctx();
            tierC.put("derivedDepthTier", "C");
            add("reason-tier-c", "reason", tierC, list(h), list(s), nc("TIER_C"));
            ObjectNode failed = ctx();
            failed.put("serverState", "FAILED");
            add("reason-server-failed", "reason", failed, list(h), list(), nc("SERVER_FAILED"));
            add("reason-mode-mismatch", "reason", ctx(), list(h), list(p2p("s1", new double[] {0, 0, 0}, new double[] {1, 0, 0}, 1)), nc("MODE_MISMATCH"));
            add("reason-no-association-no-servers", "reason", ctx(), list(h), list(), nc("NO_ASSOCIATION"));
            add("reason-no-association-no-geometric-match", "reason", ctx(), list(h), list(box("s1", 0.5, 0, 5)), nc("NO_ASSOCIATION"));
            ObjectNode pending = ctx();
            pending.put("serverState", "PENDING");
            add("outcome-pending", "reason", pending, list(h), list(), new String[] {"PENDING", null, null, null});
            ObjectNode both = ctx();
            both.put("modelDeleted", true);
            both.put("serverState", "FAILED");
            add("precedence-model-deleted-over-server-failed", "reason", both, list(h), list(), nc("MODEL_DELETED"));
            ObjectNode tierCFailed = ctx();
            tierCFailed.put("derivedDepthTier", "C");
            tierCFailed.put("serverState", "FAILED");
            add("precedence-tier-c-over-server-failed", "reason", tierCFailed, list(h), list(), nc("TIER_C"));

            // branch 1: seeded, by construction (even when the dimensions disagree)
            ObjectNode seeded = box("s1", 0.5, 0, 0);
            seeded.put("seed", "h1");
            add("seeded-agree", "SEEDED", ctx(), list(h), list(seeded, box("s2", 0.5, 0, 0)), ok("AGREE", "SEEDED", "s1"));
            ObjectNode seededFar = box("s1", 0.9, 0, 0);
            seededFar.put("seed", "h1");
            add("seeded-can-be-major-diff", "SEEDED", ctx(), list(h), list(seededFar), ok("MAJOR_DIFF", "SEEDED", "s1"));

            // branch 2: frame mapping available. Client box height 1.0 (y 0..1), server height H (y 0..H, same
            // footprint): IoU = 1 / H, so H = 2 is exactly 0.5.
            ObjectNode hb = box("h1", 0.5, 0, 0);
            add("iou-exactly-0.5", "IOU", ctx(), list(hb), list(box("s1", 1.0, 0, 0)), ok("MAJOR_DIFF", "IOU", "s1"));
            add("iou-just-over-0.5", "IOU", ctx(), list(hb), list(box("s1", 0.99998, 0, 0)), ok("MAJOR_DIFF", "IOU", "s1"));
            add("iou-just-under-0.5", "IOU", ctx(), list(hb), list(box("s1", 1.00003, 0, 0)), nc("NO_ASSOCIATION"));
            add("iou-yawed-identical-boxes", "IOU", ctx(), list(box("h1", 0.5, 0.7, 0)), list(box("s1", 0.5, 0.7, 0)), ok("AGREE", "IOU", "s1"));
            add("iou-greedy-one-to-one-higher-score-wins", "IOU", ctx(),
                    list(box("h1", 0.5, 0, 0), box("h2", 0.5, 0, 0.05)), list(box("s1", 0.5, 0, 0.05)),
                    nc("NO_ASSOCIATION"), ok("AGREE", "IOU", "s1"));
            add("iou-tie-broken-by-lower-server-id", "IOU", ctx(), list(box("h1", 0.5, 0, 0)),
                    list(box("s2", 0.5, 0, 0), box("s1", 0.5, 0, 0)), ok("AGREE", "IOU", "s1"));

            double[] a = {0, 0, 0};
            double[] b = {1, 0, 0};
            ObjectNode server = p2p("s1", a, b, 1.0);
            add("p2p-endpoint-exactly-0.10", "GEOMETRIC", ctx(), list(p2p("h1", new double[] {0, 0.1, 0}, b, 1.005)), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            add("p2p-endpoint-just-under-0.10", "GEOMETRIC", ctx(), list(p2p("h1", new double[] {0, 0.09999, 0}, b, 1.005)), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            add("p2p-endpoint-just-over-0.10", "GEOMETRIC", ctx(), list(p2p("h1", new double[] {0, 0.10001, 0}, b, 1.005)), list(server), nc("NO_ASSOCIATION"));
            add("p2p-either-endpoint-order", "GEOMETRIC", ctx(), list(p2p("h1", b, a, 1.0)), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            ObjectNode plane = plane("s1", 0, 0.5);
            add("plane-normal-exactly-5-degrees", "GEOMETRIC", ctx(), list(plane("h1", 5, 0.5)), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-normal-just-over-5-degrees", "GEOMETRIC", ctx(), list(plane("h1", 5.00002, 0.5)), list(plane), nc("NO_ASSOCIATION"));
            add("plane-offset-exactly-0.05", "GEOMETRIC", ctx(), list(plane("h1", 0, 0.55)), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-offset-just-over-0.05", "GEOMETRIC", ctx(), list(plane("h1", 0, 0.55002)), list(plane), nc("NO_ASSOCIATION"));

            // branch 3: dimension-only (no frame mapping): unique qualifying candidate, never MAJOR_DIFF
            ObjectNode noMap = ctx();
            noMap.put("frameMapping", false);
            ObjectNode dh = dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.0);
            add("dimension-only-unique-agree", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.01), dimMeasurement("s2", "OBJECT_BOX", 0.8, 0.6, 1.0)),
                    ok("AGREE", "DIMENSION_ONLY", "s1"));
            add("dimension-only-unique-minor-diff", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.04)), ok("MINOR_DIFF", "DIMENSION_ONLY", "s1"));
            // the reference value of the tolerance is the server value (1.0 m: minor tol 0.05)
            add("dimension-only-exactly-at-minor-qualifies", "DIMENSION_ONLY", noMap,
                    list(dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.05)),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), ok("MINOR_DIFF", "DIMENSION_ONLY", "s1"));
            add("dimension-only-just-over-minor-is-no-association-not-major-diff", "DIMENSION_ONLY", noMap,
                    list(dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.05001)),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), nc("NO_ASSOCIATION"));
            add("dimension-only-two-candidates-is-no-association", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0), dimMeasurement("s2", "OBJECT_BOX", 0.41, 0.6, 1.0)),
                    nc("NO_ASSOCIATION"));
            add("dimension-only-two-hints-one-server-is-no-association", "DIMENSION_ONLY", noMap,
                    list(dh, dimMeasurement("h2", "OBJECT_BOX", 0.4, 0.6, 1.0)),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), nc("NO_ASSOCIATION"), nc("NO_ASSOCIATION"));
            add("dimension-only-point-to-point", "DIMENSION_ONLY", noMap, list(dimMeasurement("h1", "POINT_TO_POINT", 1.0)),
                    list(dimMeasurement("s1", "POINT_TO_POINT", 1.02)), ok("AGREE", "DIMENSION_ONLY", "s1"));
        }
    }

    private static byte[] json(Object o) throws IOException {
        return (MAPPER.writer(PRETTY).writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
