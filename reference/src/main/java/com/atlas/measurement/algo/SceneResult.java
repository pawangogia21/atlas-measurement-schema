package com.atlas.measurement.algo;

/** Output of the conformance pipeline (algorithm-spec.md, Outputs). Values are Float64 until serialised. */
public final class SceneResult {
    public enum Status { OK, INSUFFICIENT_POINTS, NO_SUPPORT_PLANE, NO_OBJECT }

    public final Status status;
    public final int validPixels;
    public final int supportPlaneInliers;
    public final int objectPoints;
    // The fields below are NaN-free only when status == OK; otherwise zeros (and supportPlaneY may be set from NO_OBJECT).
    public final double supportPlaneY;
    public final double[] centerWorld;
    public final double[] halfExtentsM;
    public final double yawRad;
    public final double lengthM;
    public final double widthM;
    public final double heightM;

    SceneResult(Status status, int validPixels, int supportPlaneInliers, int objectPoints, double supportPlaneY,
            double[] centerWorld, double[] halfExtentsM, double yawRad, double lengthM, double widthM, double heightM) {
        this.status = status;
        this.validPixels = validPixels;
        this.supportPlaneInliers = supportPlaneInliers;
        this.objectPoints = objectPoints;
        this.supportPlaneY = supportPlaneY;
        this.centerWorld = centerWorld;
        this.halfExtentsM = halfExtentsM;
        this.yawRad = yawRad;
        this.lengthM = lengthM;
        this.widthM = widthM;
        this.heightM = heightM;
    }

    static SceneResult failure(Status status, int valid, int inliers, int objectPoints, double planeY) {
        return new SceneResult(status, valid, inliers, objectPoints, planeY, new double[3], new double[3], 0, 0, 0, 0);
    }
}
