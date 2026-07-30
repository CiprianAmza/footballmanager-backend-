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
        Double awayXg,
        int homeCollectiveGoals,
        int awayCollectiveGoals,
        Long homeShooterPlayerId,
        Long awayShooterPlayerId,
        int homeShooterGoals,
        int awayShooterGoals,
        Long homeRedCardPlayerId,
        Long awayRedCardPlayerId,
        int homeShooterShots,
        int awayShooterShots,
        Long homePassingPlayerId,
        Long awayPassingPlayerId,
        int homePassingGoals,
        int awayPassingGoals,
        int homePassingOpportunities,
        int awayPassingOpportunities,
        double homePassingControl,
        double awayPassingControl) {
    public static final String ALGORITHM_VERSION = ScoreEngineKind.COMPARTMENT_V1.algorithmVersion();
    private static final String FINGERPRINT_PATTERN = "[0-9a-f]{64}";

    /** Score decisions without match-only SHOOTER/red-card metadata (notably admin overrides). */
    public MatchScoringDecision(String fixtureKey, long seed, ScoreEngineKind scoreEngine,
                                String scoreAlgorithmVersion, String configFingerprint,
                                String inputFingerprint, int homeScore90, int awayScore90,
                                double homePower, double awayPower, Double homeXg, Double awayXg) {
        this(fixtureKey, seed, scoreEngine, scoreAlgorithmVersion, configFingerprint, inputFingerprint,
                homeScore90, awayScore90, homePower, awayPower, homeXg, awayXg,
                homeScore90, awayScore90, null, null, 0, 0, null, null, 0, 0,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

    /** Compatibility constructor for decisions created before attempts were persisted. */
    public MatchScoringDecision(
            String fixtureKey, long seed, ScoreEngineKind scoreEngine,
            String scoreAlgorithmVersion, String configFingerprint, String inputFingerprint,
            int homeScore90, int awayScore90, double homePower, double awayPower,
            Double homeXg, Double awayXg, int homeCollectiveGoals, int awayCollectiveGoals,
            Long homeShooterPlayerId, Long awayShooterPlayerId,
            int homeShooterGoals, int awayShooterGoals,
            Long homeRedCardPlayerId, Long awayRedCardPlayerId) {
        this(fixtureKey, seed, scoreEngine, scoreAlgorithmVersion, configFingerprint, inputFingerprint,
                homeScore90, awayScore90, homePower, awayPower, homeXg, awayXg,
                homeCollectiveGoals, awayCollectiveGoals,
                homeShooterPlayerId, awayShooterPlayerId, homeShooterGoals, awayShooterGoals,
                homeRedCardPlayerId, awayRedCardPlayerId,
                homeShooterGoals, awayShooterGoals,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

    /** Compatibility constructor for callers that persist SHOOTER attempts but no passing style. */
    public MatchScoringDecision(
            String fixtureKey, long seed, ScoreEngineKind scoreEngine,
            String scoreAlgorithmVersion, String configFingerprint, String inputFingerprint,
            int homeScore90, int awayScore90, double homePower, double awayPower,
            Double homeXg, Double awayXg, int homeCollectiveGoals, int awayCollectiveGoals,
            Long homeShooterPlayerId, Long awayShooterPlayerId,
            int homeShooterGoals, int awayShooterGoals,
            Long homeRedCardPlayerId, Long awayRedCardPlayerId,
            int homeShooterShots, int awayShooterShots) {
        this(fixtureKey, seed, scoreEngine, scoreAlgorithmVersion, configFingerprint, inputFingerprint,
                homeScore90, awayScore90, homePower, awayPower, homeXg, awayXg,
                homeCollectiveGoals, awayCollectiveGoals,
                homeShooterPlayerId, awayShooterPlayerId, homeShooterGoals, awayShooterGoals,
                homeRedCardPlayerId, awayRedCardPlayerId, homeShooterShots, awayShooterShots,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

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
        if (homeCollectiveGoals < 0 || awayCollectiveGoals < 0
                || homeShooterGoals < 0 || awayShooterGoals < 0
                || homeShooterShots < 0 || awayShooterShots < 0
                || homePassingGoals < 0 || awayPassingGoals < 0
                || homePassingOpportunities < 0 || awayPassingOpportunities < 0) {
            throw new IllegalArgumentException("goal components must be non-negative");
        }
        if (homeShooterGoals > homeShooterShots || awayShooterGoals > awayShooterShots) {
            throw new IllegalArgumentException("SHOOTER goals cannot exceed SHOOTER attempts");
        }
        if (homePassingGoals > homePassingOpportunities || awayPassingGoals > awayPassingOpportunities) {
            throw new IllegalArgumentException("PASSING STYLE goals cannot exceed opportunities");
        }
        if (homeScore90 != homeCollectiveGoals + homeShooterGoals + homePassingGoals
                || awayScore90 != awayCollectiveGoals + awayShooterGoals + awayPassingGoals) {
            throw new IllegalArgumentException("score must equal collective plus individual goals");
        }
        if ((homeShooterShots > 0 && homeShooterPlayerId == null)
                || (awayShooterShots > 0 && awayShooterPlayerId == null)) {
            throw new IllegalArgumentException("SHOOTER attempts require a SHOOTER player");
        }
        if ((homePassingOpportunities > 0 && homePassingPlayerId == null)
                || (awayPassingOpportunities > 0 && awayPassingPlayerId == null)) {
            throw new IllegalArgumentException("PASSING STYLE opportunities require a striker");
        }
        requirePositiveIfPresent(homeShooterPlayerId, "homeShooterPlayerId");
        requirePositiveIfPresent(awayShooterPlayerId, "awayShooterPlayerId");
        requirePositiveIfPresent(homePassingPlayerId, "homePassingPlayerId");
        requirePositiveIfPresent(awayPassingPlayerId, "awayPassingPlayerId");
        requirePositiveIfPresent(homeRedCardPlayerId, "homeRedCardPlayerId");
        requirePositiveIfPresent(awayRedCardPlayerId, "awayRedCardPlayerId");
        if (!Double.isFinite(homePower) || homePower < 0 || !Double.isFinite(awayPower) || awayPower < 0) {
            throw new IllegalArgumentException("powers must be finite and non-negative");
        }
        if ((homeXg == null) != (awayXg == null)) throw new IllegalArgumentException("xG must be paired");
        if (homeXg != null && (!Double.isFinite(homeXg) || homeXg < 0 || !Double.isFinite(awayXg) || awayXg < 0)) {
            throw new IllegalArgumentException("xG must be finite and non-negative");
        }
        if (!finiteProbability(homePassingControl) || !finiteProbability(awayPassingControl)) {
            throw new IllegalArgumentException("PASSING STYLE control must be in [0,1]");
        }
    }

    private static void requirePositiveIfPresent(Long playerId, String field) {
        if (playerId != null && playerId <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static boolean finiteProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
