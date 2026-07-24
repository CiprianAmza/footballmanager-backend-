package com.footballmanagergamesimulator.matchplan;

import java.util.Objects;

/** Immutable regular-time scoring decision persisted with a MatchPlan. */
public record MatchScoringDecision(
        String fixtureKey,
        long seed,
        ScoreEngineKind scoreEngine,
        String scoreAlgorithmVersion,
        String configFingerprint,
        String inputFingerprint,
        int homeScore90,
        int awayScore90,
        double homePower,
        double awayPower,
        Double homeXg,
        Double awayXg) {
    public static final String ALGORITHM_VERSION = ScoreEngineKind.COMPARTMENT_V1.algorithmVersion();
    private static final String FINGERPRINT_PATTERN = "[0-9a-f]{64}";

    public MatchScoringDecision {
        if (fixtureKey == null || fixtureKey.isBlank()) throw new IllegalArgumentException("fixtureKey must not be blank");
        Objects.requireNonNull(scoreEngine, "scoreEngine");
        if (scoreAlgorithmVersion == null || scoreAlgorithmVersion.isBlank()) {
            throw new IllegalArgumentException("scoreAlgorithmVersion must not be blank");
        }
        if (!scoreEngine.algorithmVersion().equals(scoreAlgorithmVersion)) {
            throw new IllegalArgumentException("algorithm version does not match score engine: " + scoreEngine);
        }
        if (configFingerprint == null || !configFingerprint.matches(FINGERPRINT_PATTERN)
                || inputFingerprint == null || !inputFingerprint.matches(FINGERPRINT_PATTERN)) {
            throw new IllegalArgumentException("fingerprints must be lowercase SHA-256 values");
        }
        if (homeScore90 < 0 || awayScore90 < 0) throw new IllegalArgumentException("scores must be non-negative");
        if (!Double.isFinite(homePower) || homePower < 0 || !Double.isFinite(awayPower) || awayPower < 0) {
            throw new IllegalArgumentException("powers must be finite and non-negative");
        }
        if ((homeXg == null) != (awayXg == null)) throw new IllegalArgumentException("xG must be paired");
        if (homeXg != null && (!Double.isFinite(homeXg) || homeXg < 0 || !Double.isFinite(awayXg) || awayXg < 0)) {
            throw new IllegalArgumentException("xG must be finite and non-negative");
        }
    }
}
