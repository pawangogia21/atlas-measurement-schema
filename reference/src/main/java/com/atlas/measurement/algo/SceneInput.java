package com.atlas.measurement.algo;

/** One conformance window: a single depth frame in the depth grid (algorithm-spec.md, Inputs). */
public final class SceneInput {
    public final int width;
    public final int height;
    /** Depth-grid intrinsics (already scaled by the adapter). */
    public final double fx;
    public final double fy;
    public final double cx;
    public final double cy;
    /** Camera-to-world, row-major 4x4. */
    public final double[] pose;
    public final long windowIndex;
    /** Row-major, metres; NaN, infinite or non-positive means invalid. */
    public final float[] depth;
    /** Row-major, normalised 0..1. */
    public final float[] confidence;

    public SceneInput(int width, int height, double fx, double fy, double cx, double cy, double[] pose,
            long windowIndex, float[] depth, float[] confidence) {
        if (depth.length != width * height || confidence.length != width * height || pose.length != 16) {
            throw new IllegalArgumentException("inconsistent scene dimensions");
        }
        this.width = width;
        this.height = height;
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
        this.pose = pose;
        this.windowIndex = windowIndex;
        this.depth = depth;
        this.confidence = confidence;
    }
}
