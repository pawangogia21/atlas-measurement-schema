package com.atlas.measurement.algo;

/** SplitMix64 (algorithm-spec.md, RNG). Counter-based; the state is the seed on construction. */
public final class SplitMix64 {
    public static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** The per-window generator: {@code SplitMix64(windowIndex XOR 0x9E3779B97F4A7C15)}. */
    public static SplitMix64 forWindow(long windowIndex) {
        return new SplitMix64(windowIndex ^ GOLDEN);
    }

    public long nextLong() {
        state += GOLDEN;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform integer in [0, n) by rejection sampling: reject {@code r < 2^64 mod n}, never plain modulo. */
    public int nextInt(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }
        long bound = n;
        long threshold = Long.remainderUnsigned(-bound, bound); // 2^64 mod n
        while (true) {
            long r = nextLong();
            if (Long.compareUnsigned(r, threshold) >= 0) {
                return (int) Long.remainderUnsigned(r, bound);
            }
        }
    }

    /** Uniform double in [0, 1) from the top 53 bits. Used by the vector generator only (noise), not by the spec. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
