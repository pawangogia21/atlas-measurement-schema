package com.atlas.measurement.tolerance;

import java.util.List;

/**
 * The single tolerance function (design 21.2) and the AGREE / MINOR_DIFF / MAJOR_DIFF classification.
 * Normative text: tolerance/README.md. The Swift and Kotlin ports mirror this class.
 */
public final class Tolerance {
    public enum Outcome { AGREE, MINOR_DIFF, MAJOR_DIFF }

    /** One dimension of an associated pair. */
    public static final class DimensionPair {
        public final double server;
        public final double sigmaServer;
        public final double client;
        public final double sigmaClient;

        public DimensionPair(double server, double sigmaServer, double client, double sigmaClient) {
            this.server = server;
            this.sigmaServer = sigmaServer;
            this.client = client;
            this.sigmaClient = sigmaClient;
        }
    }

    private Tolerance() {}

    /** {@code max(floorM, relFrac*|ref|, min(sigmaK*combinedSigma, sigmaCapM))}; sigma term omitted if sigmaK is null. */
    public static double tol(double ref, double sigmaServer, double sigmaClient, ToleranceRow row) {
        requireFinite(ref, "ref");
        requireSigma(sigmaServer, "sigmaServer");
        requireSigma(sigmaClient, "sigmaClient");
        double t = Math.max(row.floorM, row.relFrac * Math.abs(ref));
        if (row.sigmaK != null) {
            double sigmaTerm = row.sigmaK * Math.sqrt(sigmaServer * sigmaServer + sigmaClient * sigmaClient);
            if (row.sigmaCapM != null) {
                sigmaTerm = Math.min(sigmaTerm, row.sigmaCapM);
            }
            t = Math.max(t, sigmaTerm);
        }
        return t;
    }

    /** Integer units of 1e-5 m: {@code floor(x * 1e5 + 0.5)} in double arithmetic, identical in every port. */
    public static long units(double metres) {
        return (long) Math.floor(metres * 1e5 + 0.5);
    }

    /** {@code diff <= tol} compared in metres rounded to 1e-5. */
    public static boolean withinTolerance(double diff, double tol) {
        return units(diff) <= units(tol);
    }

    public static Outcome classify(ToleranceProfile profile, int algorithmMajor, List<DimensionPair> dims) {
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("no dimensions to classify");
        }
        if (allWithin(profile.row("agree", algorithmMajor), dims)) {
            return Outcome.AGREE;
        }
        if (allWithin(profile.row("minor", algorithmMajor), dims)) {
            return Outcome.MINOR_DIFF;
        }
        return Outcome.MAJOR_DIFF;
    }

    private static boolean allWithin(ToleranceRow row, List<DimensionPair> dims) {
        for (DimensionPair d : dims) {
            double diff = Math.abs(d.client - d.server);
            if (!withinTolerance(diff, tol(d.server, d.sigmaServer, d.sigmaClient, row))) {
                return false;
            }
        }
        return true;
    }

    private static void requireFinite(double v, String name) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireSigma(double v, String name) {
        requireFinite(v, name);
        if (v < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }
}
