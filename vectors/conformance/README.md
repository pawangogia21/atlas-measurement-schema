# Conformance Vectors

Cross-platform numerical-parity vectors: synthetic depth scenes with fixed seeds, 1 mm and 3 mm tolerances, for tier A algorithms and shared mathematical primitives.

Conformance vectors verify that core measurement primitives (unprojection, PCA, sigma propagation, tolerance function) produce identical results across Swift and Kotlin Kit cores on macOS (Accelerate), Linux (scalar) and JVM using noise-free (1 mm tolerance) and seeded noisy (3 mm tolerance) synthetic test data.

For more details, see AT-2 and AT-16.
