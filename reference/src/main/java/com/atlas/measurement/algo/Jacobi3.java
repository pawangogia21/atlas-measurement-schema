package com.atlas.measurement.algo;

/**
 * Jacobi eigen-solver for a symmetric 3x3 matrix in Float64 (algorithm-spec.md, PCA). Sweep pair order
 * (0,1), (0,2), (1,2), at most 50 sweeps, relative off-diagonal tolerance 1e-12.
 */
public final class Jacobi3 {
    public static final int MAX_SWEEPS = 50;
    public static final double TOLERANCE = 1e-12;
    private static final int[][] PAIRS = {{0, 1}, {0, 2}, {1, 2}};

    /** Eigenvalues sorted descending (ties by original index); {@code vectors[k]} is the unit eigenvector of {@code values[k]}. */
    public static final class Result {
        public final double[] values = new double[3];
        public final double[][] vectors = new double[3][3];
        public int sweeps;
    }

    private Jacobi3() {}

    public static Result solve(double[][] input) {
        double[][] a = new double[3][3];
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int i = 0; i < 3; i++) {
            a[i] = input[i].clone();
        }
        int sweeps = 0;
        while (sweeps < MAX_SWEEPS) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            double diag = Math.abs(a[0][0]) + Math.abs(a[1][1]) + Math.abs(a[2][2]);
            if (off <= TOLERANCE * diag) {
                break;
            }
            for (int[] pq : PAIRS) {
                rotate(a, v, pq[0], pq[1]);
            }
            sweeps++;
        }
        Result r = new Result();
        r.sweeps = sweeps;
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (x, y) -> {
            // Plain comparisons (not Double.compare) so -0.0 and 0.0 tie, as in every other language.
            if (a[x][x] > a[y][y]) {
                return -1;
            }
            if (a[x][x] < a[y][y]) {
                return 1;
            }
            return Integer.compare(x, y);
        });
        for (int k = 0; k < 3; k++) {
            int col = order[k];
            r.values[k] = a[col][col];
            double[] e = {v[0][col], v[1][col], v[2][col]};
            // Sign rule: the largest-magnitude component is positive; equal magnitudes: lowest index.
            int big = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(e[i]) > Math.abs(e[big])) {
                    big = i;
                }
            }
            if (e[big] < 0) {
                for (int i = 0; i < 3; i++) {
                    e[i] = -e[i];
                }
            }
            r.vectors[k] = e;
        }
        return r;
    }

    private static void rotate(double[][] a, double[][] v, int p, int q) {
        double apq = a[p][q];
        if (apq == 0.0) {
            return;
        }
        double theta = (a[q][q] - a[p][p]) / (2.0 * apq);
        double t = (theta >= 0 ? 1.0 : -1.0) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
        double c = 1.0 / Math.sqrt(t * t + 1.0);
        double s = t * c;
        a[p][p] = a[p][p] - t * apq;
        a[q][q] = a[q][q] + t * apq;
        a[p][q] = 0.0;
        a[q][p] = 0.0;
        int r = 3 - p - q; // the remaining index
        double arp = a[r][p];
        double arq = a[r][q];
        a[r][p] = c * arp - s * arq;
        a[p][r] = a[r][p];
        a[r][q] = c * arq + s * arp;
        a[q][r] = a[r][q];
        for (int k = 0; k < 3; k++) {
            double vkp = v[k][p];
            double vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = c * vkq + s * vkp;
        }
    }
}
