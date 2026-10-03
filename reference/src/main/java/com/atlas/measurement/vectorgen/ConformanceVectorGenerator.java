package com.atlas.measurement.vectorgen;

import com.atlas.measurement.algo.ConformancePipeline;
import com.atlas.measurement.algo.Jacobi3;
import com.atlas.measurement.algo.Kahan;
import com.atlas.measurement.algo.Median;
import com.atlas.measurement.algo.SceneInput;
import com.atlas.measurement.algo.SceneResult;
import com.atlas.measurement.algo.SplitMix64;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * Generates the conformance vector set (design 24 item 5) deterministically: same code and seeds give
 * byte-identical files, so a regeneration compared by sha256 proves reproducibility. Usage:
 * {@code ConformanceVectorGenerator <outDir>} (the release layout is vectors/conformance/<version>/).
 */
public final class ConformanceVectorGenerator {
    public static final String VERSION = "1.0.0";
    public static final String SPEC_VERSION = "1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    private final Path out;
    private final Map<String, byte[]> files = new TreeMap<>();
    private final List<Map<String, Object>> vectors = new ArrayList<>();

    private ConformanceVectorGenerator(Path out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ConformanceVectorGenerator <outDir>");
            System.exit(2);
        }
        generate(Path.of(args[0]));
    }

    public static void generate(Path out) throws IOException {
        new ConformanceVectorGenerator(out).run();
    }

    private void run() throws IOException {
        for (SceneRenderer.Spec s : scenes()) {
            addScene(s);
        }
        addPrimitives();
        writeManifest();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path p = out.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }

    // ---- scenes -------------------------------------------------------------------------------------------

    private static SceneRenderer.Spec scene(String id, String category, int w, int h, double[] cam, double[] target,
            double[] box, String note) {
        SceneRenderer.Spec s = new SceneRenderer.Spec();
        s.id = id;
        s.category = category;
        s.width = w;
        s.height = h;
        s.camera = cam;
        s.target = target;
        s.box = box;
        s.note = note;
        s.windowIndex = 1;
        return s;
    }

    static List<SceneRenderer.Spec> scenes() {
        List<SceneRenderer.Spec> l = new ArrayList<>();
        // box = {centerX, centerZ, yaw, lengthU, heightY, widthW}
        double[] boxA = {0.10, -1.2, 0.35, 0.60, 0.30, 0.40};
        double[] boxB = {-0.05, -0.9, -0.60, 0.30, 0.10, 0.20};
        double[] boxC = {0.0, -1.6, 1.10, 1.00, 0.60, 0.50};
        double[] camA = {0.0, 1.3, 0.4};
        double[] camB = {0.2, 1.1, 0.0};
        double[] camC = {-0.3, 1.6, 0.8};

        l.add(scene("S01-box-60x40x30-64x48", "noise-free", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "Box 0.60 x 0.40 x 0.30 m, yaw 0.35 rad, 64x48 depth grid."));
        l.add(scene("S02-box-60x40x30-128x96", "noise-free", 128, 96, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "Same scene as S01 at 128x96 (variable depth resolution)."));
        l.add(scene("S03-box-30x20x10-64x48", "noise-free", 64, 48, camB, new double[] {-0.05, 0.05, -0.9}, boxB,
                "Small box 0.30 x 0.20 x 0.10 m, yaw -0.60 rad."));
        l.add(scene("S04-box-100x50x60-128x96", "noise-free", 128, 96, camC, new double[] {0.0, 0.3, -1.6}, boxC,
                "Large box 1.00 x 0.50 x 0.60 m, yaw 1.10 rad."));

        SceneRenderer.Spec n1 = scene("N01-box-60x40x30-64x48-seed1", "noisy", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "S01 with seeded Gaussian depth noise, sigma = sigmaZ(z), seed 1.");
        n1.noiseScale = 1.0;
        n1.noiseSeed = 1;
        l.add(n1);
        SceneRenderer.Spec n2 = scene("N02-box-60x40x30-128x96-seed4", "noisy", 128, 96, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "S02 with seeded Gaussian depth noise, sigma = sigmaZ(z), seed 4.");
        n2.noiseScale = 1.0;
        n2.noiseSeed = 4;
        l.add(n2);
        SceneRenderer.Spec n3 = scene("N03-box-30x20x10-64x48-seed3", "noisy", 64, 48, camB, new double[] {-0.05, 0.05, -0.9}, boxB,
                "S03 with seeded Gaussian depth noise, sigma = sigmaZ(z), seed 3.");
        n3.noiseScale = 1.0;
        n3.noiseSeed = 3;
        l.add(n3);
        SceneRenderer.Spec n4 = scene("N04-box-100x50x60-128x96-seed2", "noisy", 128, 96, camC, new double[] {0.0, 0.3, -1.6}, boxC,
                "S04 with seeded Gaussian depth noise, sigma = sigmaZ(z), seed 2.");
        n4.noiseScale = 1.0;
        n4.noiseSeed = 2;
        l.add(n4);
        SceneRenderer.Spec n5 = scene("N05-box-60x40x30-64x48-dropouts", "noisy", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "S01 with noise (seed 5) and seeded dropouts: 10 percent of pixels get confidence 0, 2 percent NaN depth.");
        n5.noiseScale = 1.0;
        n5.noiseSeed = 5;
        n5.dropConfidenceFrac = 0.10;
        n5.dropDepthFrac = 0.02;
        n5.dropSeed = 55;
        l.add(n5);

        SceneRenderer.Spec d1 = scene("D01-floor-only", "degenerate", 64, 48, camA, new double[] {0.1, 0.0, -1.2}, null,
                "Floor only, no object: status NO_OBJECT.");
        l.add(d1);
        SceneRenderer.Spec d2 = scene("D02-all-invalid-depth", "degenerate", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "Every depth value NaN: status INSUFFICIENT_POINTS.");
        d2.allNaN = true;
        l.add(d2);
        SceneRenderer.Spec d3 = scene("D03-low-confidence", "degenerate", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "Valid depth everywhere but confidence 0.49 (below the 0.5 cut): status INSUFFICIENT_POINTS.");
        d3.constantConfidence = 0.49f;
        l.add(d3);
        SceneRenderer.Spec d4 = scene("D04-sparse-30-pixels", "degenerate", 64, 48, camA, new double[] {0.1, 0.15, -1.2}, boxA,
                "Only 30 valid pixels (fewer than the 50 minimum): status INSUFFICIENT_POINTS.");
        d4.keepOnlyEvery = 97;
        d4.keepMax = 30;
        l.add(d4);
        SceneRenderer.Spec d5 = scene("D05-wall-only", "degenerate", 64, 48, new double[] {0.0, 1.2, 0.0}, new double[] {0.0, 1.2, -3.0}, null,
                "A vertical wall 3 m ahead, no floor: no horizontal plane, status NO_SUPPORT_PLANE.");
        d5.floor = false;
        d5.wallZ = -3.0;
        l.add(d5);
        return l;
    }

    private void addScene(SceneRenderer.Spec s) throws IOException {
        SceneRenderer r = new SceneRenderer(s);
        SceneInput in = r.input;
        SceneResult res = ConformancePipeline.run(in);
        String dir = "scenes/" + s.id + "/";

        files.put(dir + "depth.f32", floats(in.depth));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", s.note);
        input.put("category", s.category);
        input.put("width", in.width);
        input.put("height", in.height);
        input.put("depthFile", "depth.f32");
        if (r.confidenceVaries) {
            files.put(dir + "confidence.f32", floats(in.confidence));
            input.put("confidence", Map.of("file", "confidence.f32"));
        } else {
            input.put("confidence", Map.of("constant", in.confidence[0]));
        }
        Map<String, Object> intr = new LinkedHashMap<>();
        intr.put("fx", in.fx);
        intr.put("fy", in.fy);
        intr.put("cx", in.cx);
        intr.put("cy", in.cy);
        input.put("depthIntrinsics", intr);
        List<Double> pose = new ArrayList<>();
        for (double d : in.pose) {
            pose.add(d);
        }
        input.put("poseCameraToWorldRowMajor", pose);
        input.put("windowIndex", in.windowIndex);
        files.put(dir + "input.json", json(input));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("status", res.status.name());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("validPixels", res.validPixels);
        counts.put("supportPlaneInliers", res.supportPlaneInliers);
        counts.put("objectPoints", res.objectPoints);
        expected.put("counts", counts);
        if (res.status == SceneResult.Status.OK) {
            expected.put("supportPlaneY", (float) res.supportPlaneY);
            Map<String, Object> obb = new LinkedHashMap<>();
            obb.put("centerWorld", f3(res.centerWorld));
            obb.put("halfExtentsM", f3(res.halfExtentsM));
            obb.put("yawRad", (float) res.yawRad);
            expected.put("obb", obb);
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("lengthM", (float) res.lengthM);
            dims.put("widthM", (float) res.widthM);
            dims.put("heightM", (float) res.heightM);
            expected.put("dimensions", dims);
        }
        if (s.box != null) {
            Map<String, Object> truth = new LinkedHashMap<>();
            truth.put("note", "Analytic ground truth of the rendered box. Informational, never compared.");
            truth.put("lengthM", Math.max(s.box[3], s.box[5]));
            truth.put("widthM", Math.min(s.box[3], s.box[5]));
            truth.put("heightM", s.box[4]);
            expected.put("truth", truth);
        }
        files.put(dir + "expected.json", json(expected));

        boolean ok = res.status == SceneResult.Status.OK;
        boolean noisy = s.category.equals("noisy");
        Map<String, Object> tol = new LinkedHashMap<>();
        tol.put("status", exact());
        tol.put("counts.validPixels", exact());
        tol.put("counts.supportPlaneInliers", exact());
        tol.put("counts.objectPoints", exact());
        if (ok) {
            double metres = noisy ? 0.003 : 0.001;
            double rad = noisy ? 0.006 : 0.002;
            tol.put("supportPlaneY", abs(metres, "m"));
            tol.put("obb.centerWorld[0..2]", abs(metres, "m"));
            tol.put("obb.halfExtentsM[0..2]", abs(metres, "m"));
            tol.put("obb.yawRad", abs(rad, "rad, compared modulo pi"));
            tol.put("dimensions.lengthM", abs(metres, "m"));
            tol.put("dimensions.widthM", abs(metres, "m"));
            tol.put("dimensions.heightM", abs(metres, "m"));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", s.id);
        v.put("kind", "scene");
        v.put("category", s.category);
        v.put("input", dir + "input.json");
        v.put("expected", dir + "expected.json");
        v.put("tolerances", tol);
        vectors.add(v);
    }

    private static Map<String, Object> exact() {
        return Map.of("match", "exact");
    }

    private static Map<String, Object> abs(double value, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("match", "abs");
        m.put("value", value);
        m.put("unit", unit);
        return m;
    }

    private static List<Float> f3(double[] a) {
        return List.of((float) a[0], (float) a[1], (float) a[2]);
    }

    private static byte[] floats(float[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : a) {
            // Canonical quiet NaN so the bytes do not depend on how the NaN was produced.
            b.putInt(Float.isNaN(f) ? 0x7fc00000 : Float.floatToRawIntBits(f));
        }
        return b.array();
    }

    // ---- primitives ---------------------------------------------------------------------------------------

    private void addPrimitives() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", "Shared-math primitives of algorithm-spec.md. Integers and hex strings match exactly; floats per the manifest tolerances.");

        List<Object> sm = new ArrayList<>();
        for (long seed : new long[] {0L, 1L, 0x9E3779B97F4A7C15L, 0xFFFFFFFFFFFFFFFFL}) {
            SplitMix64 g = new SplitMix64(seed);
            List<String> outs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                outs.add(String.format("0x%016x", g.nextLong()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seed", String.format("0x%016x", seed));
            m.put("first8", outs);
            sm.add(m);
        }
        root.put("splitmix64", sm);

        List<Object> win = new ArrayList<>();
        for (long w : new long[] {0L, 1L, 7L, 123456789L}) {
            SplitMix64 g = SplitMix64.forWindow(w);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windowIndex", w);
            m.put("firstOutput", String.format("0x%016x", g.nextLong()));
            win.add(m);
        }
        root.put("windowSeed", win);

        List<Object> bounded = new ArrayList<>();
        for (int n : new int[] {3, 7, 1000, 2147483647}) {
            SplitMix64 g = SplitMix64.forWindow(7);
            List<Integer> draws = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                draws.add(g.nextInt(n));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windowIndex", 7);
            m.put("n", n);
            m.put("rejectionThreshold2pow64ModN", Long.toUnsignedString(Long.remainderUnsigned(-(long) n, n)));
            m.put("first16", draws);
            bounded.add(m);
        }
        root.put("boundedInt", bounded);

        List<Object> med = new ArrayList<>();
        for (double[] vals : new double[][] {{3, 1, 2, 1}, {5}, {2, 2, 2, 2, 2}, {9, 8, 7, 6, 5, 4}, {0.5, -1.5, 0.5, 2.5}}) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("values", vals);
            int pos = Median.lowerMedianPosition(vals);
            m.put("position", pos);
            m.put("value", vals[pos]);
            med.add(m);
        }
        root.put("lowerMedian", med);

        List<Object> kahan = new ArrayList<>();
        for (double[] vals : new double[][] {{1e16, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1e16}, {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}}) {
            Kahan k = new Kahan();
            for (double x : vals) {
                k.add(x);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("values", vals);
            m.put("sum", k.sum());
            kahan.add(m);
        }
        root.put("kahanSum", kahan);

        List<Object> jac = new ArrayList<>();
        for (double[][] a : new double[][][] {
            {{4, 1, 0.5}, {1, 3, 0.2}, {0.5, 0.2, 2}},
            {{2, 0, 0}, {0, 2, 0}, {0, 0, 1}},
            {{1.5, 0, 0.8}, {0, 0, 0}, {0.8, 0, 0.9}},
            {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}}}) {
            Jacobi3.Result r = Jacobi3.solve(a);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matrix", a);
            m.put("sweeps", r.sweeps);
            m.put("eigenvalues", r.values);
            m.put("eigenvectors", r.vectors);
            jac.add(m);
        }
        root.put("jacobi3", jac);
        files.put("primitives.json", json(root));

        Map<String, Object> tol = new LinkedHashMap<>();
        tol.put("splitmix64", exact());
        tol.put("windowSeed", exact());
        tol.put("boundedInt", exact());
        tol.put("lowerMedian", exact());
        tol.put("kahanSum.sum", abs(0.0, "none, bit-exact Float64"));
        tol.put("jacobi3.sweeps", exact());
        tol.put("jacobi3.eigenvalues", abs(1e-9, "none"));
        tol.put("jacobi3.eigenvectors", abs(1e-9, "none"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", "P01-primitives");
        v.put("kind", "primitives");
        v.put("category", "noise-free");
        v.put("input", "primitives.json");
        v.put("tolerances", tol);
        vectors.add(0, v);
    }

    // ---- manifest -----------------------------------------------------------------------------------------

    private void writeManifest() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vectorSet", "conformance");
        m.put("version", VERSION);
        m.put("algorithmSpec", "algorithm-spec.md (spec version " + SPEC_VERSION + ")");
        m.put("scope", "Tier A and shared math only. Adapters, platform depth resolution and confidence mapping and tier B algorithms are excluded.");
        Map<String, Object> formats = new LinkedHashMap<>();
        formats.put("depth.f32", "Float32 little-endian, row-major, width*height values, metres; NaN is invalid.");
        formats.put("confidence.f32", "Float32 little-endian, row-major, normalised 0..1.");
        m.put("fileFormats", formats);
        m.put("tolerancePolicy", "1 mm (and 0.002 rad) for noise-free scenes, 3 mm (and 0.006 rad) for noisy scenes; counts and enums match exactly (design 24 item 5).");
        m.put("vectors", vectors);
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", e.getKey());
            f.put("sha256", sha256(e.getValue()));
            f.put("bytes", e.getValue().length);
            list.add(f);
        }
        m.put("files", list);
        files.put("manifest.json", json(m));
    }

    private static byte[] json(Object o) throws IOException {
        return (MAPPER.writer(PRETTY).writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
