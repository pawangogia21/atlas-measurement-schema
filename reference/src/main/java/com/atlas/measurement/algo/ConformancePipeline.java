package com.atlas.measurement.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference implementation of algorithm-spec.md: the tier A shared-math pipeline (unprojection, support plane by
 * RANSAC, gravity-aligned box by PCA). Every rule that the spec pins is marked with its section number.
 */
public final class ConformancePipeline {
    // Spec 4: constants. Changing any of them is a new algorithm major.
    public static final double Z_MIN = 0.1;
    public static final double Z_MAX = 5.0;
    public static final double MIN_CONFIDENCE = 0.5;
    public static final int MIN_POINTS = 50;
    public static final int RANSAC_ITERATIONS = 64;
    public static final int MIN_OBJECT_POINTS = 20;
    public static final double INLIER_SIGMAS = 5.0;
    public static final double TOP_BAND_M = 0.02;

    private ConformancePipeline() {}

    /** Depth noise model, metres (spec 4.2). */
    public static double sigmaZ(double z) {
        return 0.001 + 0.0015 * z * z;
    }

    public static SceneResult run(SceneInput in) {
        // Spec 5.1: valid pixels and unprojection, row-major.
        List<double[]> pts = new ArrayList<>(); // {x, y, z, zDepth}
        double[] p = in.pose;
        for (int v = 0; v < in.height; v++) {
            for (int u = 0; u < in.width; u++) {
                int i = v * in.width + u;
                double z = in.depth[i];
                double conf = in.confidence[i];
                if (!(z >= Z_MIN && z <= Z_MAX) || !(conf >= MIN_CONFIDENCE)) {
                    continue;
                }
                double xc = (u - in.cx) * z / in.fx;
                double yc = -(v - in.cy) * z / in.fy;
                double zc = -z;
                double xw = p[0] * xc + p[1] * yc + p[2] * zc + p[3];
                double yw = p[4] * xc + p[5] * yc + p[6] * zc + p[7];
                double zw = p[8] * xc + p[9] * yc + p[10] * zc + p[11];
                pts.add(new double[] {xw, yw, zw, z});
            }
        }
        int n = pts.size();
        if (n < MIN_POINTS) {
            return SceneResult.failure(SceneResult.Status.INSUFFICIENT_POINTS, n, 0, 0, 0);
        }

        // Spec 5.2: support plane y = c, RANSAC with a one-point sample.
        double[] ys = new double[n];
        for (int j = 0; j < n; j++) {
            ys[j] = pts.get(j)[1];
        }
        double[] sorted = ys.clone();
        java.util.Arrays.sort(sorted);
        double eligibleMaxY = sorted[(n - 1) / 5]; // rank floor((n-1)/5): the lower 20 percent of heights
        SplitMix64 rng = SplitMix64.forWindow(in.windowIndex);
        int bestInliers = 0;
        double bestC = 0;
        for (int it = 0; it < RANSAC_ITERATIONS; it++) {
            int idx = rng.nextInt(n); // always consumed, even when the candidate is not eligible
            double c = ys[idx];
            if (c > eligibleMaxY) {
                continue;
            }
            int inliers = 0;
            for (int j = 0; j < n; j++) {
                if (Math.abs(ys[j] - c) <= INLIER_SIGMAS * sigmaZ(pts.get(j)[3])) {
                    inliers++;
                }
            }
            if (inliers > bestInliers) {
                bestInliers = inliers;
                bestC = c;
            }
        }
        if (bestInliers == 0 || 10L * bestInliers < 3L * n) {
            return SceneResult.failure(SceneResult.Status.NO_SUPPORT_PLANE, n, bestInliers, 0, 0);
        }
        double[] inlierYs = new double[bestInliers];
        int k = 0;
        for (int j = 0; j < n; j++) {
            if (Math.abs(ys[j] - bestC) <= INLIER_SIGMAS * sigmaZ(pts.get(j)[3])) {
                inlierYs[k++] = ys[j];
            }
        }
        double planeY = Median.lowerMedian(inlierYs);
        int planeInliers = 0;
        for (int j = 0; j < n; j++) {
            if (Math.abs(ys[j] - planeY) <= INLIER_SIGMAS * sigmaZ(pts.get(j)[3])) {
                planeInliers++;
            }
        }

        // Spec 5.3: object points are those above the plane by more than the inlier threshold.
        List<double[]> obj = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            double[] q = pts.get(j);
            if (q[1] - planeY > INLIER_SIGMAS * sigmaZ(q[3])) {
                obj.add(q);
            }
        }
        int m = obj.size();
        if (m < MIN_OBJECT_POINTS) {
            return SceneResult.failure(SceneResult.Status.NO_OBJECT, n, planeInliers, m, planeY);
        }

        // Spec 5.4: height is the highest object point; the footprint uses the top face only (points within
        // TOP_BAND_M of the highest point), so visible side faces do not bias the yaw.
        double hMax = Double.NEGATIVE_INFINITY;
        for (double[] q : obj) {
            hMax = Math.max(hMax, q[1] - planeY);
        }
        List<double[]> top = new ArrayList<>();
        for (double[] q : obj) {
            if (q[1] - planeY >= hMax - TOP_BAND_M) {
                top.add(q);
            }
        }
        obj = top;
        int mTop = obj.size();

        // Spec 5.5: footprint PCA, Kahan sums in point order, population covariance of (x, 0, z).
        Kahan sx = new Kahan();
        Kahan sz = new Kahan();
        for (double[] q : obj) {
            sx.add(q[0]);
            sz.add(q[2]);
        }
        double mx = sx.sum() / mTop;
        double mz = sz.sum() / mTop;
        Kahan cxx = new Kahan();
        Kahan cxz = new Kahan();
        Kahan czz = new Kahan();
        for (double[] q : obj) {
            double dx = q[0] - mx;
            double dz = q[2] - mz;
            cxx.add(dx * dx);
            cxz.add(dx * dz);
            czz.add(dz * dz);
        }
        double[][] cov = {{cxx.sum() / mTop, 0, cxz.sum() / mTop}, {0, 0, 0}, {cxz.sum() / mTop, 0, czz.sum() / mTop}};
        Jacobi3.Result eig = Jacobi3.solve(cov);
        double ex = eig.vectors[0][0];
        double ez = eig.vectors[0][2];
        double yaw = StrictMath.atan2(-ez, ex);
        if (yaw > Math.PI / 2) {
            yaw -= Math.PI;
        } else if (yaw <= -Math.PI / 2) {
            yaw += Math.PI;
        }
        // Local axes from the normalised yaw: u = (cos yaw, -sin yaw), w = (sin yaw, cos yaw) in (x, z).
        double ux = StrictMath.cos(yaw);
        double uz = -StrictMath.sin(yaw);
        double wx = -uz;
        double wz = ux;
        double sMin = Double.POSITIVE_INFINITY;
        double sMax = Double.NEGATIVE_INFINITY;
        double tMin = Double.POSITIVE_INFINITY;
        double tMax = Double.NEGATIVE_INFINITY;
        for (double[] q : obj) {
            double s = q[0] * ux + q[2] * uz;
            double t = q[0] * wx + q[2] * wz;
            sMin = Math.min(sMin, s);
            sMax = Math.max(sMax, s);
            tMin = Math.min(tMin, t);
            tMax = Math.max(tMax, t);
        }
        double lu = sMax - sMin;
        double lw = tMax - tMin;
        double sc = (sMin + sMax) / 2;
        double tc = (tMin + tMax) / 2;
        double[] center = {sc * ux + tc * wx, planeY + hMax / 2, sc * uz + tc * wz};
        double[] half = {lu / 2, hMax / 2, lw / 2};
        return new SceneResult(SceneResult.Status.OK, n, planeInliers, m, planeY, center, half, yaw,
                Math.max(lu, lw), Math.min(lu, lw), hMax);
    }
}
