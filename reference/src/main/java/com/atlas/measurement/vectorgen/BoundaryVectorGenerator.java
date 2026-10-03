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
        cases.put("description", "Boundary vectors for the tolerance function (profile 1.0.0): every row and every term at exactly, just under (-1e-5 m) and just over (+1e-5 m) the threshold, rounding edges, per-dimension classification and invalid inputs (non-finite, out of range) that every port must reject with an error: in the invalid cases a non-finite number is the string NaN, Infinity or -Infinity (JSON has no such number).");
        cases.put("tolEpsilon", 1e-9);
        ArrayNode threshold = cases.putArray("threshold");
        ArrayNode within = cases.putArray("within");
        ArrayNode classify = cases.putArray("classify");
        ArrayNode invalid = cases.putArray("invalid");
        thresholds(threshold);
        rounding(within);
        classifyAtThresholds(classify);
        classifyMultiDimension(classify);
        invalidInputs(invalid);
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

    // ---- invalid input -----------------------------------------------------------------------------------

    /** A JSON number, or for a non-finite value the string NaN, Infinity or -Infinity. */
    private static com.fasterxml.jackson.databind.JsonNode num(double v) {
        if (Double.isNaN(v)) {
            return MAPPER.getNodeFactory().textNode("NaN");
        }
        if (Double.isInfinite(v)) {
            return MAPPER.getNodeFactory().textNode(v > 0 ? "Infinity" : "-Infinity");
        }
        return MAPPER.getNodeFactory().numberNode(v);
    }

    private void invalidInputs(ArrayNode out) {
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300};
        String[] names = {"nan", "infinity", "negative-infinity", "huge-1e300"};
        for (int i = 0; i < bad.length; i++) {
            final double b = bad[i];
            classifyError(out, "classify-client-" + names[i], dim(1.0, 0, 0, b));
            classifyError(out, "classify-server-" + names[i], dim(b, 0, 0, 1.0));
            if (i < 3) { // a huge finite sigma is valid: the sigma term is capped
                classifyError(out, "classify-sigma-server-" + names[i], dim(1.0, b, 0, 1.0));
                classifyError(out, "classify-sigma-client-" + names[i], dim(1.0, 0, b, 1.0));
                tolError(out, "tol-sigma-server-" + names[i], 1.0, b, 0);
            }
            withinError(out, "within-diff-" + names[i], b, 0.02);
            withinError(out, "within-tol-" + names[i], 0.02, b);
            tolError(out, "tol-ref-" + names[i], b, 0, 0);
        }
        classifyError(out, "classify-negative-sigma-server", dim(1.0, -0.001, 0, 1.0));
        classifyError(out, "classify-negative-sigma-client", dim(1.0, 0, -0.001, 1.0));
        classifyError(out, "classify-one-bad-dimension-among-good-ones", dim(1.0, 0, 0, 1.0), dim(0.3, 0, 0, Double.NaN));
        classifyError(out, "classify-no-dimensions");
        tolError(out, "tol-negative-sigma-client", 1.0, 0, -1);
    }

    private void classifyError(ArrayNode out, String id, ObjectNode... dims) {
        List<DimensionPair> list = new ArrayList<>();
        for (ObjectNode d : dims) {
            list.add(new DimensionPair(d.get("server").asDouble(), d.get("sigmaServer").asDouble(),
                    d.get("client").asDouble(), d.get("sigmaClient").asDouble()));
        }
        requireError(id, () -> Tolerance.classify(profile, 1, list));
        ObjectNode c = out.addObject();
        c.put("id", id);
        c.put("op", "classify");
        c.put("major", 1);
        ArrayNode a = c.putArray("dims");
        for (ObjectNode d : dims) {
            ObjectNode e = a.addObject();
            for (String k : new String[] {"server", "sigmaServer", "client", "sigmaClient"}) {
                e.set(k, num(d.get(k).asDouble()));
            }
        }
        c.put("expect", "ERROR");
    }

    private void withinError(ArrayNode out, String id, double diff, double tol) {
        requireError(id, () -> Tolerance.withinTolerance(diff, tol));
        ObjectNode c = out.addObject();
        c.put("id", id);
        c.put("op", "within");
        c.set("diff", num(diff));
        c.set("tol", num(tol));
        c.put("expect", "ERROR");
    }

    private void tolError(ArrayNode out, String id, double ref, double sigmaServer, double sigmaClient) {
        ToleranceRow row = profile.row("agree", 1);
        requireError(id, () -> Tolerance.tol(ref, sigmaServer, sigmaClient, row));
        ObjectNode c = out.addObject();
        c.put("id", id);
        c.put("op", "tol");
        c.put("row", "agree");
        c.put("major", 1);
        c.set("ref", num(ref));
        c.set("sigmaServer", num(sigmaServer));
        c.set("sigmaClient", num(sigmaClient));
        c.put("expect", "ERROR");
    }

    private static void requireError(String id, Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(id + ": the reference returned a result for an invalid input, intended an error");
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
        private static final String SESSION = "b1d0a2c4-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
        private static final String OTHER_SESSION = "0f0e0d0c-0b0a-4908-8706-050403020100";

        private final ArrayNode out;

        AssociationCases(ArrayNode out) {
            this.out = out;
        }

        /** Model level context: the scan has a transform to canonical and sits in SESSION at epoch 1. */
        private ObjectNode ctx() {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("modelDeleted", false);
            c.put("derivedDepthTier", "A");
            c.put("serverState", "READY");
            ObjectNode scan = c.putObject("scan");
            scan.put("sessionId", SESSION);
            scan.put("worldOriginEpoch", 1);
            scan.put("transformToCanonical", true);
            return c;
        }

        /** A client hint: the measurement plus the per-hint context (schemaVersion, claimed tier, own session and epoch). */
        private ObjectNode hint(ObjectNode m) {
            ObjectNode h = MAPPER.createObjectNode();
            h.put("id", m.get("id").asText());
            h.put("mode", m.get("mode").asText());
            h.put("schemaVersion", "1.0");
            h.put("depthTier", "A");
            h.put("sessionId", SESSION);
            h.put("worldOriginEpoch", 1);
            m.fields().forEachRemaining(e -> {
                if (!h.has(e.getKey())) {
                    h.set(e.getKey(), e.getValue());
                }
            });
            return h;
        }

        private ObjectNode with(ObjectNode h, String field, Object value) {
            ObjectNode c = h.deepCopy();
            if (value instanceof Integer) {
                c.put(field, (Integer) value);
            } else {
                c.put(field, (String) value);
            }
            return c;
        }

        private ObjectNode sv(double v) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("v", v);
            n.put("s", 0.0);
            return n;
        }

        /** Footprint 0.4 x 0.6 (obb half-extents 0.2 and 0.3), height 2*hy, resting on y = 0. */
        private ObjectNode box(String id, double hy, double yaw, double cx) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "OBJECT_BOX");
            m.putArray("dims").add(sv(0.4)).add(sv(0.6)).add(sv(2 * hy));
            obb(m, new double[] {cx, hy, 0.0}, new double[] {0.3, hy, 0.2}, yaw);
            return m;
        }

        private void obb(ObjectNode m, double[] center, double[] half, double yaw) {
            ObjectNode b = m.putObject("obb");
            ArrayNode c = b.putArray("centerWorld");
            for (double v : center) {
                c.add(v);
            }
            ArrayNode h = b.putArray("halfExtentsM");
            for (double v : half) {
                h.add(v);
            }
            b.put("yawRad", yaw);
        }

        private ObjectNode p2p(String id, double[] a, double[] b, double distance) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "POINT_TO_POINT");
            m.putArray("dims").add(sv(distance));
            ArrayNode e = m.putObject("geometry").putArray("endpointsWorld");
            e.addArray().add(a[0]).add(a[1]).add(a[2]);
            e.addArray().add(b[0]).add(b[1]).add(b[2]);
            return m;
        }

        private ObjectNode plane(String id, double angleDeg, double offset) {
            return plane(id, new double[] {Math.sin(Math.toRadians(angleDeg)), Math.cos(Math.toRadians(angleDeg)), 0.0}, offset);
        }

        private ObjectNode plane(String id, double[] normal, double offset) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("id", id);
            m.put("mode", "PLANE_DISTANCE");
            m.putArray("dims").add(sv(1.0));
            ObjectNode p = m.putObject("geometry").putObject("plane");
            p.putArray("normalWorld").add(normal[0]).add(normal[1]).add(normal[2]);
            p.put("offsetM", offset);
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

        /** Hints that no other hint supersedes, restated here so the intended result does not come from the reference. */
        private List<ObjectNode> active(ArrayNode hints) {
            List<String> superseded = new ArrayList<>();
            for (var h : hints) {
                if (h.has("supersedes")) {
                    superseded.add(h.get("supersedes").asText());
                }
            }
            List<ObjectNode> active = new ArrayList<>();
            for (var h : hints) {
                if (!superseded.contains(h.get("id").asText())) {
                    active.add((ObjectNode) h);
                }
            }
            return active;
        }

        /** Adds a case; {@code expected} is {outcome, reasonCode, method, serverId} per active hint, in order. */
        private void add(String id, String branch, ObjectNode ctx, ArrayNode hints, ArrayNode servers, String[]... expected) {
            ArrayNode actual;
            try {
                actual = Association.decide(ctx, hints, servers, profile, 1);
            } catch (RuntimeException e) {
                throw new IllegalStateException(id, e);
            }
            List<ObjectNode> active = active(hints);
            if (active.size() != expected.length) {
                throw new IllegalStateException(id + ": " + active.size() + " active hints but " + expected.length + " expected results");
            }
            ArrayNode exp = MAPPER.createArrayNode();
            for (int i = 0; i < expected.length; i++) {
                ObjectNode r = MAPPER.createObjectNode();
                r.put("hint", active.get(i).get("id").asText());
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

        private ObjectNode noMapping() {
            ObjectNode c = ctx();
            ((ObjectNode) c.get("scan")).put("transformToCanonical", false);
            return c;
        }

        void build() {
            reasons();
            seeded();
            geometric();
            dimensionOnly();
            perHint();
        }

        private void reasons() {
            ObjectNode h = hint(box("h1", 0.5, 0, 0));
            ObjectNode s = box("s1", 0.5, 0, 0);
            ObjectNode deleted = ctx();
            deleted.put("modelDeleted", true);
            add("reason-model-deleted", "reason", deleted, list(h), list(s), nc("MODEL_DELETED"));
            add("reason-schema-unsupported", "reason", ctx(), list(with(h, "schemaVersion", "2.0")), list(s), nc("SCHEMA_UNSUPPORTED"));
            add("reason-tier-c", "reason", ctx(), list(with(h, "depthTier", "C")), list(s), nc("TIER_C"));
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
            tierCFailed.put("serverState", "FAILED");
            add("precedence-tier-c-over-server-failed", "reason", tierCFailed, list(with(h, "depthTier", "C")), list(), nc("TIER_C"));
            add("precedence-schema-unsupported-over-tier-c", "reason", ctx(),
                    list(with(with(h, "schemaVersion", "2.0"), "depthTier", "C")), list(s), nc("SCHEMA_UNSUPPORTED"));
            add("precedence-tier-c-over-pending", "reason", pending, list(with(h, "depthTier", "C")), list(), nc("TIER_C"));
            // TIER_C is the hint's claimed tier: a server-derived tier C does not exclude a hint that claims A
            ObjectNode derivedC = ctx();
            derivedC.put("derivedDepthTier", "C");
            add("derived-tier-c-does-not-exclude-a-hint-claiming-a", "reason", derivedC, list(h), list(s), ok("AGREE", "IOU", "s1"));
            // per hint, not per model: only the hint that carries the condition is excluded
            add("mixed-hints-unsupported-schema-and-claimed-tier-c", "reason", ctx(),
                    list(h, with(box0("h2", s), "schemaVersion", "2.0"), with(box0("h3", s), "depthTier", "C")), list(s),
                    ok("AGREE", "IOU", "s1"), nc("SCHEMA_UNSUPPORTED"), nc("TIER_C"));
        }

        /** A hint equal to the server measurement {@code like}, with its own id. */
        private ObjectNode box0(String id, ObjectNode like) {
            ObjectNode m = like.deepCopy();
            m.put("id", id);
            return hint(m);
        }

        private void seeded() {
            ObjectNode h = hint(box("h1", 0.5, 0, 0));
            ObjectNode seeded = box("s1", 0.5, 0, 0);
            seeded.put("seed", "h1");
            add("seeded-agree", "SEEDED", ctx(), list(h), list(seeded, box("s2", 0.5, 0, 0)), ok("AGREE", "SEEDED", "s1"));
            ObjectNode seededFar = box("s1", 0.9, 0, 0);
            seededFar.put("seed", "h1");
            add("seeded-can-be-major-diff", "SEEDED", ctx(), list(h), list(seededFar), ok("MAJOR_DIFF", "SEEDED", "s1"));
            // two servers carry the same seed: the lower server id is the seeded one
            ObjectNode a = box("s1", 0.5, 0, 0);
            a.put("seed", "h1");
            ObjectNode b = box("s2", 0.5, 0, 0);
            b.put("seed", "h1");
            add("seeded-two-servers-same-seed-lower-id-wins", "SEEDED", ctx(), list(h), list(b, a), ok("AGREE", "SEEDED", "s1"));
            // a seed names a hint of another mode: ignored
            ObjectNode wrongMode = p2p("s1", new double[] {0, 0, 0}, new double[] {1, 0, 0}, 1);
            wrongMode.put("seed", "h1");
            add("seeded-other-mode-is-ignored", "SEEDED", ctx(), list(h), list(wrongMode, box("s2", 0.5, 0, 0)), ok("AGREE", "IOU", "s2"));
            // seeded works without a frame mapping and without geometry
            add("seeded-without-frame-mapping", "SEEDED", noMapping(), list(h), list(seeded), ok("AGREE", "SEEDED", "s1"));
            // the footprint is compared sorted in every branch: a swapped length and width is not a MAJOR_DIFF
            ObjectNode swapped = hint(box("h1", 0.5, 0, 0));
            ((ArrayNode) swapped.get("dims")).removeAll();
            ((ArrayNode) swapped.get("dims")).add(sv(0.6)).add(sv(0.4)).add(sv(1.0));
            add("seeded-swapped-footprint-agrees", "SEEDED", ctx(), list(swapped), list(seeded), ok("AGREE", "SEEDED", "s1"));
        }

        private void geometric() {
            // frame mapping available. Client box height 1.0 (y 0..1), server height H (y 0..H, same footprint):
            // IoU = 1 / H, so H = 2 is exactly 0.5.
            ObjectNode hb = hint(box("h1", 0.5, 0, 0));
            add("iou-exactly-0.5", "IOU", ctx(), list(hb), list(box("s1", 1.0, 0, 0)), ok("MAJOR_DIFF", "IOU", "s1"));
            add("iou-just-over-0.5", "IOU", ctx(), list(hb), list(box("s1", 0.99998, 0, 0)), ok("MAJOR_DIFF", "IOU", "s1"));
            add("iou-just-under-0.5", "IOU", ctx(), list(hb), list(box("s1", 1.00003, 0, 0)), nc("NO_ASSOCIATION"));
            // the geometry thresholds are compared in 1e-5 units like the tolerance: 0.499996 rounds to 0.5 and qualifies
            add("iou-0.499996-rounds-up-to-0.5-qualifies", "IOU", ctx(), list(hb), list(box("s1", 1.000008, 0, 0)), ok("MAJOR_DIFF", "IOU", "s1"));
            add("iou-0.499994-rounds-down-below-0.5", "IOU", ctx(), list(hb), list(box("s1", 1.000012, 0, 0)), nc("NO_ASSOCIATION"));
            add("iou-yawed-identical-boxes", "IOU", ctx(), list(hint(box("h1", 0.5, 0.7, 0))), list(box("s1", 0.5, 0.7, 0)), ok("AGREE", "IOU", "s1"));
            add("iou-greedy-one-to-one-higher-score-wins", "IOU", ctx(),
                    list(hint(box("h1", 0.5, 0, 0)), hint(box("h2", 0.5, 0, 0.05))), list(box("s1", 0.5, 0, 0.05)),
                    nc("NO_ASSOCIATION"), ok("AGREE", "IOU", "s1"));
            add("iou-tie-broken-by-lower-server-id", "IOU", ctx(), list(hint(box("h1", 0.5, 0, 0))),
                    list(box("s2", 0.5, 0, 0), box("s1", 0.5, 0, 0)), ok("AGREE", "IOU", "s1"));
            // a box turned by 90 degrees has the same footprint: hint length and width are swapped, the sorted footprint agrees
            ObjectNode turned = box("h1", 0.5, Math.PI / 2, 0);
            ((ArrayNode) turned.get("dims")).removeAll();
            ((ArrayNode) turned.get("dims")).add(sv(0.6)).add(sv(0.4)).add(sv(1.0));
            add("iou-box-turned-90-degrees-agrees", "IOU", ctx(), list(hint(turned)), list(box("s1", 0.5, 0, 0)), ok("AGREE", "IOU", "s1"));

            double[] a = {0, 0, 0};
            double[] b = {1, 0, 0};
            ObjectNode server = p2p("s1", a, b, 1.0);
            add("p2p-endpoint-exactly-0.10", "GEOMETRIC", ctx(), list(hint(p2p("h1", new double[] {0, 0.1, 0}, b, 1.005))), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            add("p2p-endpoint-just-under-0.10", "GEOMETRIC", ctx(), list(hint(p2p("h1", new double[] {0, 0.09999, 0}, b, 1.005))), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            add("p2p-endpoint-just-over-0.10", "GEOMETRIC", ctx(), list(hint(p2p("h1", new double[] {0, 0.10001, 0}, b, 1.005))), list(server), nc("NO_ASSOCIATION"));
            add("p2p-endpoint-0.100004-rounds-down-to-0.10-qualifies", "GEOMETRIC", ctx(),
                    list(hint(p2p("h1", new double[] {0, 0.100004, 0}, b, 1.005))), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            add("p2p-endpoint-0.100006-rounds-up-past-0.10", "GEOMETRIC", ctx(),
                    list(hint(p2p("h1", new double[] {0, 0.100006, 0}, b, 1.005))), list(server), nc("NO_ASSOCIATION"));
            add("p2p-either-endpoint-order", "GEOMETRIC", ctx(), list(hint(p2p("h1", b, a, 1.0))), list(server), ok("AGREE", "GEOMETRIC", "s1"));
            ObjectNode plane = plane("s1", 0, 0.5);
            add("plane-normal-exactly-5-degrees", "GEOMETRIC", ctx(), list(hint(plane("h1", 5, 0.5))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-normal-just-over-5-degrees", "GEOMETRIC", ctx(), list(hint(plane("h1", 5.00002, 0.5))), list(plane), nc("NO_ASSOCIATION"));
            add("plane-normal-5.000004-degrees-rounds-down-qualifies", "GEOMETRIC", ctx(), list(hint(plane("h1", 5.000004, 0.5))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-normal-5.000006-degrees-rounds-up-past", "GEOMETRIC", ctx(), list(hint(plane("h1", 5.000006, 0.5))), list(plane), nc("NO_ASSOCIATION"));
            add("plane-offset-exactly-0.05", "GEOMETRIC", ctx(), list(hint(plane("h1", 0, 0.55))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-offset-just-over-0.05", "GEOMETRIC", ctx(), list(hint(plane("h1", 0, 0.55002))), list(plane), nc("NO_ASSOCIATION"));
            add("plane-offset-0.050004-rounds-down-qualifies", "GEOMETRIC", ctx(), list(hint(plane("h1", 0, 0.550004))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-offset-0.050006-rounds-up-past", "GEOMETRIC", ctx(), list(hint(plane("h1", 0, 0.550006))), list(plane), nc("NO_ASSOCIATION"));
            // sign-invariant: n with offset o and -n with offset -o are the same plane (seen from the other side)
            double[] down = {0, -1, 0};
            add("plane-seen-from-the-other-side-is-the-same-plane", "GEOMETRIC", ctx(), list(hint(plane("h1", down, -0.5))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-flipped-normal-offset-not-negated-is-another-plane", "GEOMETRIC", ctx(), list(hint(plane("h1", down, 0.5))), list(plane), nc("NO_ASSOCIATION"));
            add("plane-flipped-offset-difference-exactly-0.05", "GEOMETRIC", ctx(), list(hint(plane("h1", down, -0.55))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
            add("plane-flipped-offset-difference-just-over-0.05", "GEOMETRIC", ctx(), list(hint(plane("h1", down, -0.55002))), list(plane), nc("NO_ASSOCIATION"));
            double rad = Math.toRadians(5);
            add("plane-flipped-normal-exactly-5-degrees", "GEOMETRIC", ctx(),
                    list(hint(plane("h1", new double[] {-Math.sin(rad), -Math.cos(rad), 0}, -0.5))), list(plane), ok("AGREE", "GEOMETRIC", "s1"));
        }

        private void dimensionOnly() {
            // no frame mapping: unique qualifying candidate per hint, never MAJOR_DIFF
            ObjectNode noMap = noMapping();
            ObjectNode dh = hint(dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.0));
            add("dimension-only-unique-agree", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.01), dimMeasurement("s2", "OBJECT_BOX", 0.8, 0.6, 1.0)),
                    ok("AGREE", "DIMENSION_ONLY", "s1"));
            add("dimension-only-unique-minor-diff", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.04)), ok("MINOR_DIFF", "DIMENSION_ONLY", "s1"));
            // the reference value of the tolerance is the server value (1.0 m: minor tol 0.05)
            add("dimension-only-exactly-at-minor-qualifies", "DIMENSION_ONLY", noMap,
                    list(hint(dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.05))),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), ok("MINOR_DIFF", "DIMENSION_ONLY", "s1"));
            add("dimension-only-just-over-minor-is-no-association-not-major-diff", "DIMENSION_ONLY", noMap,
                    list(hint(dimMeasurement("h1", "OBJECT_BOX", 0.4, 0.6, 1.05001))),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), nc("NO_ASSOCIATION"));
            add("dimension-only-two-candidates-is-no-association", "DIMENSION_ONLY", noMap, list(dh),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0), dimMeasurement("s2", "OBJECT_BOX", 0.41, 0.6, 1.0)),
                    nc("NO_ASSOCIATION"));
            // section 35.3: each hint is decided on its own, two hints with the same single candidate are both associated
            add("dimension-only-two-hints-one-server-both-associated", "DIMENSION_ONLY", noMap,
                    list(dh, hint(dimMeasurement("h2", "OBJECT_BOX", 0.4, 0.6, 1.0))),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)),
                    ok("AGREE", "DIMENSION_ONLY", "s1"), ok("AGREE", "DIMENSION_ONLY", "s1"));
            add("dimension-only-point-to-point", "DIMENSION_ONLY", noMap, list(hint(dimMeasurement("h1", "POINT_TO_POINT", 1.0))),
                    list(dimMeasurement("s1", "POINT_TO_POINT", 1.02)), ok("AGREE", "DIMENSION_ONLY", "s1"));
            // footprint sorted ascending on both sides, height separate: hint 1.0 x 0.5 against server 0.5 x 1.0
            add("dimension-only-swapped-footprint-is-sorted", "DIMENSION_ONLY", noMap,
                    list(hint(dimMeasurement("h1", "OBJECT_BOX", 1.0, 0.5, 0.3))), list(dimMeasurement("s1", "OBJECT_BOX", 0.5, 1.0, 0.3)),
                    ok("AGREE", "DIMENSION_ONLY", "s1"));
            add("dimension-only-height-is-not-sorted-into-the-footprint", "DIMENSION_ONLY", noMap,
                    list(hint(dimMeasurement("h1", "OBJECT_BOX", 0.5, 0.3, 1.0))), list(dimMeasurement("s1", "OBJECT_BOX", 0.5, 1.0, 0.3)),
                    nc("NO_ASSOCIATION"));
            // superseded hints are excluded first: only the re-save is associated, it does not compete with the old one
            ObjectNode resave = hint(dimMeasurement("h2", "OBJECT_BOX", 0.4, 0.6, 1.0));
            resave.put("supersedes", "h1");
            add("dimension-only-superseded-hint-is-excluded", "DIMENSION_ONLY", noMap, list(dh, resave),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), ok("AGREE", "DIMENSION_ONLY", "s1"));
            ObjectNode resave3 = hint(dimMeasurement("h3", "OBJECT_BOX", 0.4, 0.6, 1.0));
            resave3.put("supersedes", "h2");
            add("superseded-chain-keeps-only-the-latest", "DIMENSION_ONLY", noMap, list(dh, resave, resave3),
                    list(dimMeasurement("s1", "OBJECT_BOX", 0.4, 0.6, 1.0)), ok("AGREE", "DIMENSION_ONLY", "s1"));
            // a server taken by the one-to-one geometric branch is not a dimension-only candidate
            add("dimension-only-skips-a-server-taken-by-a-geometric-match", "DIMENSION_ONLY", ctx(),
                    list(hint(box("h1", 0.5, 0, 0)), with(hint(box("h2", 0.5, 0, 5)), "worldOriginEpoch", 2)),
                    list(box("s1", 0.5, 0, 0)), ok("AGREE", "IOU", "s1"), nc("NO_ASSOCIATION"));
        }

        /** F2: the frame mapping, schema and tier are per hint. */
        private void perHint() {
            ObjectNode s1 = box("s1", 0.5, 0, 0);
            ObjectNode s2 = box("s2", 0.5, 0, 5);
            // h1 sits in the scan's epoch (geometric, takes s1), h2 was captured after a relocalization (epoch 2): dimension-only
            add("mixed-epoch-hints-geometric-and-dimension-only", "mixed", ctx(),
                    list(hint(box("h1", 0.5, 0, 0)), with(hint(box("h2", 0.5, 0, 5)), "worldOriginEpoch", 2)), list(s1, s2),
                    ok("AGREE", "IOU", "s1"), ok("AGREE", "DIMENSION_ONLY", "s2"));
            // the same two hints with the matching epoch are both geometric
            add("mixed-epoch-both-in-the-scans-epoch-are-geometric", "mixed", ctx(),
                    list(hint(box("h1", 0.5, 0, 0)), hint(box("h2", 0.5, 0, 5))), list(s1, s2),
                    ok("AGREE", "IOU", "s1"), ok("AGREE", "IOU", "s2"));
            // a relocalized hint alone ignores geometry: two servers with the same dimensions are ambiguous
            add("relocalized-hint-uses-dimensions-only", "DIMENSION_ONLY", ctx(), list(with(hint(box("h1", 0.5, 0, 0)), "worldOriginEpoch", 2)), list(s1, s2),
                    nc("NO_ASSOCIATION"));
            add("hint-from-another-session-uses-dimensions-only", "DIMENSION_ONLY", ctx(), list(with(hint(box("h1", 0.5, 0, 0)), "sessionId", OTHER_SESSION)),
                    list(s1, s2), nc("NO_ASSOCIATION"));
            add("scan-without-transform-uses-dimensions-only", "DIMENSION_ONLY", noMapping(), list(hint(box("h1", 0.5, 0, 0))), list(s1, s2), nc("NO_ASSOCIATION"));
            // a mapped hint without the geometry its mode needs falls back to dimension-only instead of a dead end
            ObjectNode noObb = hint(box("h1", 0.5, 0, 0));
            noObb.remove("obb");
            add("mapped-box-hint-without-obb-falls-back-to-dimensions", "DIMENSION_ONLY", ctx(), list(noObb), list(s1), ok("AGREE", "DIMENSION_ONLY", "s1"));
            ObjectNode noEndpoints = hint(p2p("h1", new double[] {0, 0, 0}, new double[] {1, 0, 0}, 1.0));
            noEndpoints.remove("geometry");
            add("mapped-point-to-point-hint-without-endpoints-falls-back", "DIMENSION_ONLY", ctx(), list(noEndpoints),
                    list(p2p("s1", new double[] {0, 0, 0}, new double[] {1, 0, 0}, 1.0)), ok("AGREE", "DIMENSION_ONLY", "s1"));
            ObjectNode noPlane = hint(plane("h1", 0, 0.5));
            noPlane.remove("geometry");
            add("mapped-plane-hint-without-plane-falls-back", "DIMENSION_ONLY", ctx(), list(noPlane), list(plane("s1", 0, 0.5)), ok("AGREE", "DIMENSION_ONLY", "s1"));
            // one hint with geometry that does not match is NO_ASSOCIATION (no fallback when the geometry was usable)
            add("mapped-hint-with-geometry-that-does-not-match-has-no-fallback", "IOU", ctx(), list(hint(box("h1", 0.5, 0, 0))), list(s2), nc("NO_ASSOCIATION"));
        }
    }

    private static byte[] json(Object o) throws IOException {
        return (MAPPER.writer(PRETTY).writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
