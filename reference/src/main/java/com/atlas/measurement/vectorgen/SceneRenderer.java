package com.atlas.measurement.vectorgen;

import com.atlas.measurement.algo.ConformancePipeline;
import com.atlas.measurement.algo.SceneInput;
import com.atlas.measurement.algo.SplitMix64;

/**
 * Analytic scene to depth renderer used only to produce conformance vectors (never by the Kits). Pure
 * {@link StrictMath} so the generated files are bit-identical on every platform.
 */
final class SceneRenderer {
    /** Scene description. The box rests on the floor y = 0; the optional wall is the plane z = wallZ. */
    static final class Spec {
        String id;
        int width;
        int height;
        double fxPerWidth = 0.9;
        double[] camera;
        double[] target;
        boolean floor = true;
        double[] box; // centerX, centerZ, yaw, lengthU, heightY, widthW (null: no box)
        Double wallZ;
        long windowIndex;
        double noiseScale; // 0: noise-free; otherwise multiplies sigmaZ(z)
        long noiseSeed;
        double dropConfidenceFrac; // fraction of pixels given confidence 0 (seeded)
        double dropDepthFrac; // fraction of pixels given NaN depth (seeded)
        long dropSeed;
        Float constantConfidence; // overrides all confidence values when set
        boolean allNaN;
        int keepOnlyEvery; // > 0: keep only pixels with index % keepOnlyEvery == 0, at most keepMax of them
        int keepMax;
        String category;
        String note;
    }

    final SceneInput input;
    final boolean confidenceVaries;

    SceneRenderer(Spec s) {
        double fx = s.fxPerWidth * s.width;
        double cx = (s.width - 1) / 2.0;
        double cy = (s.height - 1) / 2.0;
        double[] pose = lookAt(s.camera, s.target);
        float[] depth = new float[s.width * s.height];
        float[] conf = new float[s.width * s.height];
        SplitMix64 noise = new SplitMix64(s.noiseSeed);
        SplitMix64 drop = new SplitMix64(s.dropSeed);
        int kept = 0;
        for (int v = 0; v < s.height; v++) {
            for (int u = 0; u < s.width; u++) {
                int i = v * s.width + u;
                double[] d = {(u - cx) / fx, -(v - cy) / fx, -1.0};
                double[] dir = {
                    pose[0] * d[0] + pose[1] * d[1] + pose[2] * d[2],
                    pose[4] * d[0] + pose[5] * d[1] + pose[6] * d[2],
                    pose[8] * d[0] + pose[9] * d[1] + pose[10] * d[2]};
                double z = hit(s, new double[] {pose[3], pose[7], pose[11]}, dir);
                conf[i] = s.constantConfidence != null ? s.constantConfidence : 1.0f;
                if (Double.isNaN(z) || s.allNaN) {
                    depth[i] = Float.NaN;
                } else {
                    if (s.noiseScale > 0) {
                        double u1 = 1.0 - noise.nextDouble();
                        double u2 = noise.nextDouble();
                        double g = StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * Math.PI * u2);
                        z += g * s.noiseScale * ConformancePipeline.sigmaZ(z);
                    }
                    depth[i] = (float) z;
                }
                if (s.dropConfidenceFrac > 0 || s.dropDepthFrac > 0) {
                    double r = drop.nextDouble();
                    if (r < s.dropConfidenceFrac) {
                        conf[i] = 0.0f;
                    } else if (r < s.dropConfidenceFrac + s.dropDepthFrac) {
                        depth[i] = Float.NaN;
                    }
                }
                if (s.keepOnlyEvery > 0) {
                    boolean keep = i % s.keepOnlyEvery == 0 && !Float.isNaN(depth[i]) && kept < s.keepMax;
                    if (keep) {
                        kept++;
                    } else {
                        depth[i] = Float.NaN;
                    }
                }
            }
        }
        boolean varies = false;
        for (float c : conf) {
            if (c != conf[0]) {
                varies = true;
                break;
            }
        }
        this.confidenceVaries = varies;
        this.input = new SceneInput(s.width, s.height, fx, fx, cx, cy, pose, s.windowIndex, depth, conf);
    }

    /** Camera-to-world, row-major. The camera looks along its own -Z; x right, y up (ARKit convention). */
    static double[] lookAt(double[] pos, double[] target) {
        double[] f = norm(new double[] {target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]});
        double[] r = norm(new double[] {f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0}); // f x (0,1,0)
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]}; // r x f
        return new double[] {
            r[0], u[0], -f[0], pos[0],
            r[1], u[1], -f[1], pos[1],
            r[2], u[2], -f[2], pos[2],
            0, 0, 0, 1};
    }

    private static double[] norm(double[] a) {
        double l = StrictMath.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[] {a[0] / l, a[1] / l, a[2] / l};
    }

    /** Nearest positive ray parameter; NaN when nothing is hit. Directions have z-depth component 1, so s is the depth. */
    private static double hit(Spec s, double[] o, double[] d) {
        double best = Double.NaN;
        if (s.floor && d[1] < 0) {
            best = nearest(best, -o[1] / d[1]);
        }
        if (s.wallZ != null && d[2] != 0) {
            best = nearest(best, (s.wallZ - o[2]) / d[2]);
        }
        if (s.box != null) {
            best = nearest(best, boxHit(s.box, o, d));
        }
        return best;
    }

    private static double nearest(double a, double b) {
        if (Double.isNaN(b) || b <= 0) {
            return a;
        }
        return Double.isNaN(a) || b < a ? b : a;
    }

    private static double boxHit(double[] box, double[] o, double[] d) {
        double yaw = box[2];
        double c = StrictMath.cos(yaw);
        double sn = StrictMath.sin(yaw);
        // Box local axes in (x, z): u = (cos, -sin), w = (sin, cos); world to local is the transpose.
        double ox = o[0] - box[0];
        double oz = o[2] - box[1];
        double[] lo = {ox * c - oz * sn, o[1], ox * sn + oz * c};
        double[] ld = {d[0] * c - d[2] * sn, d[1], d[0] * sn + d[2] * c};
        double[] min = {-box[3] / 2, 0, -box[5] / 2};
        double[] max = {box[3] / 2, box[4], box[5] / 2};
        double t0 = 0;
        double t1 = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            if (ld[k] == 0) {
                if (lo[k] < min[k] || lo[k] > max[k]) {
                    return Double.NaN;
                }
                continue;
            }
            double a = (min[k] - lo[k]) / ld[k];
            double b = (max[k] - lo[k]) / ld[k];
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        }
        return t0 <= t1 && t0 > 0 ? t0 : Double.NaN;
    }
}
