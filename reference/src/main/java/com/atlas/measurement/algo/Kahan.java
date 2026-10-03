package com.atlas.measurement.algo;

/** Kahan compensated summation in Float64 (algorithm-spec.md, Precision). No FMA, no reordering. */
public final class Kahan {
    private double sum;
    private double c;

    public void add(double x) {
        double y = x - c;
        double t = sum + y;
        c = (t - sum) - y;
        sum = t;
    }

    public double sum() {
        return sum;
    }
}
