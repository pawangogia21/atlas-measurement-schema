package com.atlas.measurement.association;

import com.atlas.measurement.tolerance.Tolerance;
import com.atlas.measurement.tolerance.Tolerance.DimensionPair;
import com.atlas.measurement.tolerance.Tolerance.Outcome;
import com.atlas.measurement.tolerance.ToleranceProfile;
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
 * Reference implementation of the association rule and the NOT_COMPARABLE reason codes (design 21.2),
 * the producer of {@code vectors/boundary/<v>/association-cases.json}. Normative text: tolerance/README.md.
 * Geometry thresholds are association constants of design 21.2 (IoU 0.5, endpoints 0.10 m, normals 5 degrees,
 * plane offsets 0.05 m); they are provisional like the tolerance profile and a change is a new vector release.
 * Geometry comparisons use the same 1e-5 rounding as {@link Tolerance#units(double)}.
 */
public final class Association {
    public static final double IOU_MIN = 0.5;
    public static final double ENDPOINT_M = 0.10;
    public static final double NORMAL_DEG = 5.0;
    public static final double OFFSET_M = 0.05;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Association() {}

    /**
     * Decides every hint. {@code ctx}: modelDeleted, schemaSupported, derivedDepthTier, serverState
     * (READY, PENDING, FAILED), frameMapping. Measurements: {@code id}, {@code mode}, {@code dims}
     * (array of {@code {v, s}}: footprint sorted then height for OBJECT_BOX; one distance otherwise),
     * {@code box}/{@code endpoints}/{@code plane} for the geometric branch, and {@code seed} (a server
     * measurement's seedClientMeasurementId). Returns one result per hint: outcome, reasonCode,
     * associationMethod, serverMeasurementId.
     *
     * <p>Reason precedence (first match): MODEL_DELETED, SCHEMA_UNSUPPORTED, TIER_C, SERVER_FAILED; then
     * PENDING; then per hint MODE_MISMATCH (servers exist, none of the hint's mode) or NO_ASSOCIATION.
     */
    public static ArrayNode decide(JsonNode ctx, JsonNode hints, JsonNode servers, ToleranceProfile profile, int major) {
        ArrayNode results = MAPPER.createArrayNode();
        String early = earlyReason(ctx);
        List<Integer> open = new ArrayList<>();
        ObjectNode[] out = new ObjectNode[hints.size()];
        for (int i = 0; i < hints.size(); i++) {
            JsonNode h = hints.get(i);
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
        // 2. geometric with frame mapping, 3. dimension-only without
        if (ctx.path("frameMapping").asBoolean(false)) {
            geometric(hints, servers, unseeded, taken, out, profile, major);
        } else {
            dimensionOnly(hints, servers, unseeded, taken, out, profile, major);
        }
        for (int i = 0; i < out.length; i++) {
            results.add(out[i] != null ? out[i] : result(hints.get(i), "NOT_COMPARABLE", "NO_ASSOCIATION", null, null));
        }
        return results;
    }

    private static String earlyReason(JsonNode ctx) {
        if (ctx.path("modelDeleted").asBoolean(false)) {
            return "MODEL_DELETED";
        }
        if (!ctx.path("schemaSupported").asBoolean(true)) {
            return "SCHEMA_UNSUPPORTED";
        }
        if ("C".equals(ctx.path("derivedDepthTier").asText())) {
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

    private static void geometric(JsonNode hints, JsonNode servers, List<Integer> hintIdx, Set<String> taken,
            ObjectNode[] out, ToleranceProfile profile, int major) {
        List<Candidate> cands = new ArrayList<>();
        for (int i : hintIdx) {
            JsonNode h = hints.get(i);
            for (JsonNode s : servers) {
                if (taken.contains(s.get("id").asText()) || !s.get("mode").asText().equals(h.get("mode").asText())) {
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

    /** Score >= 0 means the pair qualifies (higher is better); -1 means it does not. */
    static double geometricScore(JsonNode h, JsonNode s) {
        switch (h.get("mode").asText()) {
            case "OBJECT_BOX": {
                double iou = Box.iou(h.get("box"), s.get("box"));
                return Tolerance.units(iou) >= Tolerance.units(IOU_MIN) ? iou : -1;
            }
            case "POINT_TO_POINT": {
                double[] a0 = vec(h.get("endpoints").get(0));
                double[] a1 = vec(h.get("endpoints").get(1));
                double[] b0 = vec(s.get("endpoints").get(0));
                double[] b1 = vec(s.get("endpoints").get(1));
                double d = Math.min(Math.max(dist(a0, b0), dist(a1, b1)), Math.max(dist(a0, b1), dist(a1, b0)));
                return Tolerance.units(d) <= Tolerance.units(ENDPOINT_M) ? 1 - d / ENDPOINT_M : -1;
            }
            case "PLANE_DISTANCE": {
                double[] n1 = vec(h.get("plane").get("normal"));
                double[] n2 = vec(s.get("plane").get("normal"));
                double dot = (n1[0] * n2[0] + n1[1] * n2[1] + n1[2] * n2[2]) / (norm(n1) * norm(n2));
                double deg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
                double off = Math.abs(h.get("plane").get("offset").asDouble() - s.get("plane").get("offset").asDouble());
                if (Tolerance.units(deg) <= Tolerance.units(NORMAL_DEG) && Tolerance.units(off) <= Tolerance.units(OFFSET_M)) {
                    return 1 - Math.max(deg / NORMAL_DEG, off / OFFSET_M) / 2;
                }
                return -1;
            }
            default:
                throw new IllegalArgumentException("unknown mode " + h.get("mode").asText());
        }
    }

    private static void dimensionOnly(JsonNode hints, JsonNode servers, List<Integer> hintIdx, Set<String> taken,
            ObjectNode[] out, ToleranceProfile profile, int major) {
        // the unique candidate of each hint, or null when there are zero or several
        JsonNode[] unique = new JsonNode[hints.size()];
        for (int i : hintIdx) {
            JsonNode h = hints.get(i);
            int n = 0;
            for (JsonNode s : servers) {
                if (!taken.contains(s.get("id").asText()) && s.get("mode").asText().equals(h.get("mode").asText())
                        && classify(h, s, profile, major) != Outcome.MAJOR_DIFF) {
                    n++;
                    unique[i] = s;
                }
            }
            if (n != 1) {
                unique[i] = null;
            }
        }
        for (int i : hintIdx) {
            if (unique[i] == null) {
                continue;
            }
            int claims = 0;
            for (int j : hintIdx) {
                if (unique[j] != null && unique[j].get("id").asText().equals(unique[i].get("id").asText())) {
                    claims++;
                }
            }
            if (claims == 1) {
                out[i] = paired(hints.get(i), unique[i], "DIMENSION_ONLY", profile, major);
            }
        }
    }

    private static Outcome classify(JsonNode h, JsonNode s, ToleranceProfile profile, int major) {
        JsonNode hd = h.get("dims");
        JsonNode sd = s.get("dims");
        if (hd.size() != sd.size()) {
            return Outcome.MAJOR_DIFF;
        }
        List<DimensionPair> dims = new ArrayList<>();
        for (int i = 0; i < sd.size(); i++) {
            dims.add(new DimensionPair(sd.get(i).get("v").asDouble(), sd.get(i).get("s").asDouble(),
                    hd.get(i).get("v").asDouble(), hd.get(i).get("s").asDouble()));
        }
        return Tolerance.classify(profile, major, dims);
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

    private static double[] vec(JsonNode a) {
        return new double[] {a.get(0).asDouble(), a.get(1).asDouble(), a.get(2).asDouble()};
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static double norm(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    /** Gravity-aligned (yaw only, Y-up) oriented boxes: footprint in the X-Z plane, height along Y. */
    static final class Box {
        private Box() {}

        static double iou(JsonNode a, JsonNode b) {
            double[] ca = vec(a.get("center"));
            double[] ha = vec(a.get("half"));
            double[] cb = vec(b.get("center"));
            double[] hb = vec(b.get("half"));
            double yLo = Math.max(ca[1] - ha[1], cb[1] - hb[1]);
            double yHi = Math.min(ca[1] + ha[1], cb[1] + hb[1]);
            double inter = 0;
            if (yHi > yLo) {
                inter = polygonArea(clip(footprint(ca, ha, a.get("yaw").asDouble()), footprint(cb, hb, b.get("yaw").asDouble()))) * (yHi - yLo);
            }
            double volA = 8 * ha[0] * ha[1] * ha[2];
            double volB = 8 * hb[0] * hb[1] * hb[2];
            return inter / (volA + volB - inter);
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
