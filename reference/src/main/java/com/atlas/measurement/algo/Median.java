package com.atlas.measurement.algo;

import java.util.Arrays;

/** Lower median with pixel-index tie-break (algorithm-spec.md, Median). */
public final class Median {
    private Median() {}

    /**
     * Position (an index into the input array) of the lower median: sort positions by (value, position), take
     * element {@code (n - 1) / 2} (0-based).
     */
    public static int lowerMedianPosition(double[] values) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            if (values[a] < values[b]) {
                return -1;
            }
            if (values[a] > values[b]) {
                return 1;
            }
            return Integer.compare(a, b);
        });
        return order[(values.length - 1) / 2];
    }

    public static double lowerMedian(double[] values) {
        return values[lowerMedianPosition(values)];
    }
}
