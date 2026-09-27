# Tolerance Profiles

Versioned thresholds (`agree`, `minor`, `accuracyGate`, `accuracyGateP95`, with per-`algorithmMajor` overrides) for the one tolerance function behind AGREE/MINOR_DIFF/MAJOR_DIFF classification and the Kit accuracy gates. NOT_COMPARABLE comes from the association rule, not from these thresholds.

The tolerance function and thresholds are implemented once in this schema repo, then generated for Swift, Kotlin and Java. Every measurement comparison records the profile version used, so historical results can be explained after a threshold retune.

For more details, see AT-2 and AT-16.
