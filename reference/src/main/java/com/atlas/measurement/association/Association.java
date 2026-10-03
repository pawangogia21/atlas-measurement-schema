package com.atlas.measurement.association;

import com.atlas.measurement.tolerance.Tolerance;
import com.atlas.measurement.tolerance.Tolerance.DimensionPair;
import com.atlas.measurement.tolerance.Tolerance.Outcome;
import com.atlas.measurement.tolerance.ToleranceProfile;
import com.atlas.measurement.validation.ClientMeasurementValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reference implementation of the association rule and the NOT_COMPARABLE reason codes (design 21.2 with the
 * clarifications of section 35), the producer of {@code vectors/boundary/<v>/association-cases.json}. Normative text:
 * tolerance/README.md. Geometry thresholds are association constants of design 21.2 (IoU 0.5, endpoints 0.10 m, normals
 * 5 degrees, plane offsets 0.05 m); they are provisional like the tolerance profile and a change is a new vector release.
 * Geometry comparisons use the same 1e-5 rounding as {@link Tolerance#units(double)}. The inputs are expressed in the
 * canonical frame already (the mapping itself is not part of the rule).
 */
public final class Association {
    public static final double IOU_MIN = 0.5;
    public static final double ENDPOINT_M = 0.10;
    public static final double NORMAL_DEG = 5.0;
    public static final double OFFSET_M = 0.05;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Association() {}

    /**
     * Decides every hint that is not superseded; superseded hints (some other hint's {@code supersedes} names them) are
     * excluded first and produce no result. Returns one result per remaining hint, in input order: outcome,
     * reasonCode, associationMethod, serverMeasurementId.
     *
     * <p>Model level, {@code ctx}: {@code modelDeleted}, {@code serverState} (READY, PENDING, FAILED),
     * {@code derivedDepthTier} (the server-derived tier: analytics only, never TIER_C) and {@code scan}
     * ({@code sessionId}, {@code worldOriginEpoch}, {@code transformToCanonical}).
     *
     * <p>Per hint: {@code id}, {@code mode}, {@code dims} (array of {@code {v, s}}: footprint length and width, then
     * height for OBJECT_BOX; one distance otherwise), {@code schemaVersion}, the claimed {@code depthTier},
     * {@code sessionId}, {@code worldOriginEpoch}, optional {@code supersedes}, and the geometry the schema carries:
     * {@code obb} (OBJECT_BOX), {@code geometry.endpointsWorld} (POINT_TO_POINT), {@code geometry.plane}
     * (PLANE_DISTANCE). Per server measurement: {@code id}, {@code mode}, {@code dims}, the same geometry fields and
     * {@code seed} (its seedClientMeasurementId).
     *
     * <p>Per hint, first match: MODEL_DELETED, SCHEMA_UNSUPPORTED (the hint's schemaVersion), TIER_C (the hint's claimed
     * tier), SERVER_FAILED; then PENDING (an outcome, not a reason); then MODE_MISMATCH (servers exist, none of the
     * hint's mode) or NO_ASSOCIATION when there are no servers. Branches: SEEDED, then GEOMETRIC/IOU one-to-one greedy for
     * a hint that has a frame mapping and the geometry its mode needs, otherwise DIMENSION_ONLY decided per hint.
     */
    public static ArrayNode decide(JsonNode ctx, JsonNode allHints, JsonNode servers, ToleranceProfile profile, int major) {
        List<JsonNode> hints = activeHints(allHints);
        ArrayNode results = MAPPER.createArrayNode();
        List<Integer> open = new ArrayList<>();
        ObjectNode[] out = new ObjectNode[hints.size()];
        for (int i = 0; i < hints.size(); i++) {
            JsonNode h = hints.get(i);
            String early = earlyReason(ctx, h);
            if (early != null) {
                out[i] = result(h, early.equals("PENDING") ? "PENDING" : "NOT_COMPARABLE",
                        early.equals("PENDING") ? null : early, null, null);
            } else if (servers.size() == 0) {
                out[i] = result(h, "NOT_COMPARABLE", "NO_ASSOCIATION", null, null);
            } else if (!anyOfMode(servers, h.get("mode").asText())) {
                out[i] = result(h, "NOT_COMPARABLE", "MODE_MISMATCH", null, null);
            } else {
                open.add(i);
            }
        }
        Set<String> taken = new HashSet<>();
        // 1. seeded: associated by construction
        List<Integer> unseeded = new ArrayList<>();
        for (int i : open) {
            JsonNode h = hints.get(i);
            JsonNode s = seededServer(servers, h);
            if (s != null && taken.add(s.get("id").asText())) {
                out[i] = paired(h, s, "SEEDED", profile, major);
            } else {
                unseeded.add(i);
            }
        }
        // 2. geometric: a hint whose own frame mapping exists and that carries the geometry its mode needs
        List<Integer> geometric = new ArrayList<>();
        List<Integer> dimensionOnly = new ArrayList<>();
        for (int i : unseeded) {
            (frameMapping(ctx, hints.get(i)) && hasGeometry(hints.get(i)) ? geometric : dimensionOnly).add(i);
        }
        geometric(hints, servers, geometric, taken, out, profile, major);
        // 3. dimension-only for the others
        dimensionOnly(hints, servers, dimensionOnly, taken, out, profile, major);
        for (int i = 0; i < out.length; i++) {
            results.add(out[i] != null ? out[i] : result(hints.get(i), "NOT_COMPARABLE", "NO_ASSOCIATION", null, null));
        }
        return results;
    }

    /** The hints that no other hint supersedes. */
    private static List<JsonNode> activeHints(JsonNode hints) {
        Set<String> superseded = new HashSet<>();
        for (JsonNode h : hints) {
            if (h.hasNonNull("supersedes")) {
                superseded.add(h.get("supersedes").asText());
            }
        }
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode h : hints) {
            if (!superseded.contains(h.get("id").asText())) {
                active.add(h);
            }
        }
        return active;
    }

    /** True only when the scan has a transform to canonical and the hint was captured in the scan's own session and epoch. */
    private static boolean frameMapping(JsonNode ctx, JsonNode hint) {
        JsonNode scan = ctx.path("scan");
        return scan.path("transformToCanonical").asBoolean(false)
                && hint.path("sessionId").asText("").equals(scan.path("sessionId").asText(null))
                && hint.path("worldOriginEpoch").asInt(-1) == scan.path("worldOriginEpoch").asInt(-2);
    }

    private static boolean hasGeometry(JsonNode hint) {
        switch (hint.get("mode").asText()) {
            case "OBJECT_BOX":
                return hint.path("obb").isObject();
            case "POINT_TO_POINT":
                return hint.path("geometry").path("endpointsWorld").isArray();
            case "PLANE_DISTANCE":
                return hint.path("geometry").path("plane").isObject();
            default:
                return false;
        }
    }

    private static String earlyReason(JsonNode ctx, JsonNode hint) {
        if (ctx.path("modelDeleted").asBoolean(false)) {
            return "MODEL_DELETED";
        }
        if (!ClientMeasurementValidator.isSupportedSchemaVersion(hint.path("schemaVersion").asText(""))) {
            return "SCHEMA_UNSUPPORTED";
        }
        if ("C".equals(hint.path("depthTier").asText())) {
            return "TIER_C";
        }
        String state = ctx.path("serverState").asText("READY");
        if (state.equals("FAILED")) {
            return "SERVER_FAILED";
        }
        return state.equals("PENDING") ? "PENDING" : null;
    }

    private static boolean anyOfMode(JsonNode servers, String mode) {
        for (JsonNode s : servers) {
            if (s.get("mode").asText().equals(mode)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode seededServer(JsonNode servers, JsonNode hint) {
        JsonNode best = null;
        for (JsonNode s : servers) {
            if (s.path("seed").asText("").equals(hint.get("id").asText())
                    && s.get("mode").asText().equals(hint.get("mode").asText())
                    && (best == null || s.get("id").asText().compareTo(best.get("id").asText()) < 0)) {
                best = s;
            }
        }
        return best;
    }

    private static final class Candidate {
        final int hint;
        final JsonNode server;
        final double score;

        Candidate(int hint, JsonNode server, double score) {
            this.hint = hint;
            this.server = server;
            this.score = score;
        }
    }

    private static void geometric(List<JsonNode> hints, JsonNode servers, List<Integer> hintIdx, Set<String> taken,
            ObjectNode[] out, ToleranceProfile profile, int major) {
        List<Candidate> cands = new ArrayList<>();
        for (int i : hintIdx) {
            JsonNode h = hints.get(i);
            for (JsonNode s : servers) {
                if (taken.contains(s.get("id").asText()) || !s.get("mode").asText().equals(h.get("mode").asText()) || !hasGeometry(s)) {
                    continue;
                }
                double score = geometricScore(h, s);
                if (score >= 0) {
                    cands.add(new Candidate(i, s, score));
                }
            }
        }
        // greedy by descending score; ties by lower serverMeasurementId, then lower hint id (determinism)
        cands.sort(Comparator.<Candidate>comparingDouble(c -> -c.score)
                .thenComparing(c -> c.server.get("id").asText())
                .thenComparing(c -> hints.get(c.hint).get("id").asText()));
        for (Candidate c : cands) {
            if (out[c.hint] == null && taken.add(c.server.get("id").asText())) {
                JsonNode h = hints.get(c.hint);
                out[c.hint] = paired(h, c.server, h.get("mode").asText().equals("OBJECT_BOX") ? "IOU" : "GEOMETRIC",
                        profile, major);
            }
        }
    }

    /**
     * Score >= 0 means the pair qualifies (higher is better); -1 means it does not. Throws on a non-finite coordinate
     * and on a zero-length plane normal: such an input is an error, never a silent match or mismatch.
     */
    static double geometricScore(JsonNode h, JsonNode s) {
        switch (h.get("mode").asText()) {
            case "OBJECT_BOX": {
                double iou = Box.iou(h.get("obb"), s.get("obb"));
                return Tolerance.units(iou) >= Tolerance.units(IOU_MIN) ? iou : -1;
            }
            case "POINT_TO_POINT": {
                JsonNode he = h.get("geometry").get("endpointsWorld");
                JsonNode se = s.get("geometry").get("endpointsWorld");
                double[] a0 = vec(he.get(0));
                double[] a1 = vec(he.get(1));
                double[] b0 = vec(se.get(0));
                double[] b1 = vec(se.get(1));
                double d = Math.min(Math.max(dist(a0, b0), dist(a1, b1)), Math.max(dist(a0, b1), dist(a1, b0)));
                // rounding can admit d slightly above the threshold: the score stays >= 0 (a qualifying pair)
                return Tolerance.units(d) <= Tolerance.units(ENDPOINT_M) ? Math.max(0, 1 - d / ENDPOINT_M) : -1;
            }
            case "PLANE_DISTANCE": {
                JsonNode hp = h.get("geometry").get("plane");
                JsonNode sp = s.get("geometry").get("plane");
                double[] n1 = vec(hp.get("normalWorld"));
                double[] n2 = vec(sp.get("normalWorld"));
                double len = norm(n1) * norm(n2);
                if (!(len > 0)) {
                    throw new IllegalArgumentException("a plane normal must have a non-zero length");
                }
                double o1 = finite(hp.get("offsetM").asDouble());
                double o2 = finite(sp.get("offsetM").asDouble());
                double dot = (n1[0] * n2[0] + n1[1] * n2[1] + n1[2] * n2[2]) / len;
                if (dot < 0) { // n and -n with the offset negated are the same plane (seen from the other side)
                    dot = -dot;
                    o2 = -o2;
                }
                double deg = Math.toDegrees(Math.acos(Math.min(1, dot)));
                double off = Math.abs(o1 - o2);
                if (Tolerance.units(deg) <= Tolerance.units(NORMAL_DEG) && Tolerance.units(off) <= Tolerance.units(OFFSET_M)) {
                    return 1 - Math.max(deg / NORMAL_DEG, off / OFFSET_M) / 2;
                }
                return -1;
            }
            default:
                throw new IllegalArgumentException("unknown mode " + h.get("mode").asText());
        }
    }

    /**
     * Dimension-only (no frame mapping, or no usable geometry): each hint is decided on its own. It is associated with
     * its candidate when exactly one server of its mode, not taken by a one-to-one branch, is not a MAJOR_DIFF
     * (design 21.2, "exactly one candidate qualifies"). Two hints that qualify for the same single server are both
     * associated (section 35.3).
     */
    private static void dimensionOnly(List<JsonNode> hints, JsonNode servers, List<Integer> hintIdx, Set<String> taken,
            ObjectNode[] out, ToleranceProfile profile, int major) {
        for (int i : hintIdx) {
            JsonNode h = hints.get(i);
            JsonNode candidate = null;
            int n = 0;
            for (JsonNode s : servers) {
                if (!taken.contains(s.get("id").asText()) && s.get("mode").asText().equals(h.get("mode").asText())
                        && classify(h, s, profile, major) != Outcome.MAJOR_DIFF) {
                    n++;
                    candidate = s;
                }
            }
            if (n == 1) {
                out[i] = paired(h, candidate, "DIMENSION_ONLY", profile, major);
            }
        }
    }

    /**
     * The tolerance classification of an associated pair. For OBJECT_BOX the footprint (length, width) of both sides is
     * sorted ascending before pairing, and the height stays separate: a box turned by 90 degrees has the same footprint
     * (section 35.3), whatever the branch.
     */
    static Outcome classify(JsonNode h, JsonNode s, ToleranceProfile profile, int major) {
        List<double[]> hd = dims(h);
        List<double[]> sd = dims(s);
        if (hd.size() != sd.size()) {
            return Outcome.MAJOR_DIFF;
        }
        List<DimensionPair> dims = new ArrayList<>();
        for (int i = 0; i < sd.size(); i++) {
            dims.add(new DimensionPair(sd.get(i)[0], sd.get(i)[1], hd.get(i)[0], hd.get(i)[1]));
        }
        return Tolerance.classify(profile, major, dims);
    }

    /** {@code [value, sigma]} per dimension; OBJECT_BOX with three dimensions has its first two sorted ascending. */
    private static List<double[]> dims(JsonNode m) {
        List<double[]> d = new ArrayList<>();
        for (JsonNode x : m.get("dims")) {
            d.add(new double[] {x.get("v").asDouble(), x.get("s").asDouble()});
        }
        if (m.get("mode").asText().equals("OBJECT_BOX") && d.size() == 3) {
            double[] a = d.get(0);
            double[] b = d.get(1);
            if (a[0] > b[0] || (a[0] == b[0] && a[1] > b[1])) {
                d.set(0, b);
                d.set(1, a);
            }
        }
        return d;
    }

    private static ObjectNode paired(JsonNode h, JsonNode s, String method, ToleranceProfile profile, int major) {
        return result(h, classify(h, s, profile, major).name(), null, method, s.get("id").asText());
    }

    private static ObjectNode result(JsonNode h, String outcome, String reason, String method, String serverId) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("hint", h.get("id").asText());
        r.put("outcome", outcome);
        r.put("reasonCode", reason);
        r.put("associationMethod", method);
        r.put("serverMeasurementId", serverId);
        return r;
    }

    private static double finite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("geometry values must be finite");
        }
        return v;
    }

    private static double[] vec(JsonNode a) {
        return new double[] {finite(a.get(0).asDouble()), finite(a.get(1).asDouble()), finite(a.get(2).asDouble())};
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static double norm(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    /**
     * Gravity-aligned (yaw only, Y-up) oriented boxes from {@code obb}: {@code halfExtentsM} = [x, y, z] with index 1
     * the vertical half-extent, footprint in the X-Z plane (rotated by {@code yawRad} about +Y).
     */
    static final class Box {
        private Box() {}

        static double iou(JsonNode a, JsonNode b) {
            double[] ca = vec(a.get("centerWorld"));
            double[] ha = vec(a.get("halfExtentsM"));
            double[] cb = vec(b.get("centerWorld"));
            double[] hb = vec(b.get("halfExtentsM"));
            double yLo = Math.max(ca[1] - ha[1], cb[1] - hb[1]);
            double yHi = Math.min(ca[1] + ha[1], cb[1] + hb[1]);
            double inter = 0;
            if (yHi > yLo) {
                inter = polygonArea(clip(footprint(ca, ha, finite(a.get("yawRad").asDouble())), footprint(cb, hb, finite(b.get("yawRad").asDouble())))) * (yHi - yLo);
            }
            double volA = 8 * ha[0] * ha[1] * ha[2];
            double volB = 8 * hb[0] * hb[1] * hb[2];
            double union = volA + volB - inter;
            return union > 0 ? inter / union : 0; // two degenerate (zero-volume) boxes do not overlap
        }

        private static double[][] footprint(double[] c, double[] h, double yaw) {
            double cos = Math.cos(yaw);
            double sin = Math.sin(yaw);
            double[][] local = {{-h[0], -h[2]}, {h[0], -h[2]}, {h[0], h[2]}, {-h[0], h[2]}};
            double[][] p = new double[4][];
            for (int i = 0; i < 4; i++) {
                p[i] = new double[] {c[0] + cos * local[i][0] + sin * local[i][1], c[2] - sin * local[i][0] + cos * local[i][1]};
            }
            return p;
        }

        /** Sutherland-Hodgman clip of convex counter-clockwise polygon {@code subject} by {@code clipper}. */
        private static List<double[]> clip(double[][] subject, double[][] clipper) {
            List<double[]> output = new ArrayList<>(List.of(subject));
            for (int i = 0; i < clipper.length && !output.isEmpty(); i++) {
                double[] e0 = clipper[i];
                double[] e1 = clipper[(i + 1) % clipper.length];
                List<double[]> input = output;
                output = new ArrayList<>();
                for (int j = 0; j < input.size(); j++) {
                    double[] cur = input.get(j);
                    double[] prev = input.get((j + input.size() - 1) % input.size());
                    boolean curIn = side(e0, e1, cur) >= 0;
                    boolean prevIn = side(e0, e1, prev) >= 0;
                    if (curIn != prevIn) {
                        double t = side(e0, e1, prev) / (side(e0, e1, prev) - side(e0, e1, cur));
                        output.add(new double[] {prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1])});
                    }
                    if (curIn) {
                        output.add(cur);
                    }
                }
            }
            return output;
        }

        private static double side(double[] a, double[] b, double[] p) {
            return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
        }

        private static double polygonArea(List<double[]> p) {
            double s = 0;
            for (int i = 0; i < p.size(); i++) {
                double[] a = p.get(i);
                double[] b = p.get((i + 1) % p.size());
                s += a[0] * b[1] - b[0] * a[1];
            }
            return Math.abs(s) / 2;
        }
    }
}
