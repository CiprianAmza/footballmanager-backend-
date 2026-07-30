package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;

import java.util.Objects;

/** Authoritative regular-time score and powers produced by the canonical runtime. */
public record CanonicalRuntimeScore(
        int homeGoals,
        int awayGoals,
        double homePower,
        double awayPower,
        CanonicalMatchEvaluation evaluation,
        String configFingerprint,
        String inputFingerprint,
        int homeCollectiveGoals,
        int awayCollectiveGoals,
        Long homeShooterPlayerId,
        Long awayShooterPlayerId,
        int homeShooterGoals,
        int awayShooterGoals,
        Long homeRedCardPlayerId,
        Long awayRedCardPlayerId,
        double homeEffectiveAttack,
        double homeEffectiveProtection,
        double awayEffectiveAttack,
        double awayEffectiveProtection,
        double homeXg,
        double awayXg,
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
    public CanonicalRuntimeScore(int homeGoals, int awayGoals, double homePower, double awayPower,
                                 CanonicalMatchEvaluation evaluation) {
        this(homeGoals, awayGoals, homePower, awayPower, evaluation, "", "");
    }

    /** Backwards-compatible constructor for analytical tests without match-only events. */
    public CanonicalRuntimeScore(int homeGoals, int awayGoals, double homePower, double awayPower,
                                 CanonicalMatchEvaluation evaluation,
                                 String configFingerprint, String inputFingerprint) {
        this(homeGoals, awayGoals, homePower, awayPower, evaluation, configFingerprint, inputFingerprint,
                homeGoals, awayGoals, null, null, 0, 0, null, null,
                homePower, 0.0, awayPower, 0.0,
                evaluation.probability().homeXg(), evaluation.probability().awayXg(), 0, 0,
                null, null, 0, 0, 0, 0, 0.0, 0.0);
    }

    public CanonicalRuntimeScore {
        evaluation = Objects.requireNonNull(evaluation, "evaluation");
        int cap = evaluation.probability().homeGoals().cap();
        if (evaluation.probability().awayGoals().cap() != cap) {
            throw new IllegalArgumentException("home and away goal caps must match");
        }
        if (homeGoals < 0 || awayGoals < 0
                || homeCollectiveGoals < 0 || homeCollectiveGoals > cap
                || awayCollectiveGoals < 0 || awayCollectiveGoals > cap
                || homeShooterGoals < 0 || awayShooterGoals < 0
                || homeShooterShots < 0 || awayShooterShots < 0
                || homePassingGoals < 0 || awayPassingGoals < 0
                || homePassingOpportunities < 0 || awayPassingOpportunities < 0) {
            throw new IllegalArgumentException("collective goals must be within the cap and all goals non-negative");
        }
        if (homeShooterGoals > homeShooterShots || awayShooterGoals > awayShooterShots) {
            throw new IllegalArgumentException("SHOOTER goals cannot exceed SHOOTER attempts");
        }
        if (homePassingGoals > homePassingOpportunities || awayPassingGoals > awayPassingOpportunities) {
            throw new IllegalArgumentException("PASSING STYLE goals cannot exceed opportunities");
        }
        if (homeGoals != homeCollectiveGoals + homeShooterGoals + homePassingGoals
                || awayGoals != awayCollectiveGoals + awayShooterGoals + awayPassingGoals) {
            throw new IllegalArgumentException("total goals must equal collective plus individual goals");
        }
        if ((homeShooterShots > 0 && homeShooterPlayerId == null)
                || (awayShooterShots > 0 && awayShooterPlayerId == null)) {
            throw new IllegalArgumentException("SHOOTER attempts require a SHOOTER player");
        }
        if ((homePassingOpportunities > 0 && homePassingPlayerId == null)
                || (awayPassingOpportunities > 0 && awayPassingPlayerId == null)) {
            throw new IllegalArgumentException("PASSING STYLE opportunities require a striker");
        }
        if (!finiteProbability(homePassingControl) || !finiteProbability(awayPassingControl)) {
            throw new IllegalArgumentException("PASSING STYLE control must be in [0,1]");
        }
        if (!Double.isFinite(homePower) || homePower < 0.0
                || !Double.isFinite(awayPower) || awayPower < 0.0) {
            throw new IllegalArgumentException("powers must be finite and non-negative");
        }
        if (!finiteNonNegative(homeEffectiveAttack) || !finiteNonNegative(homeEffectiveProtection)
                || !finiteNonNegative(awayEffectiveAttack) || !finiteNonNegative(awayEffectiveProtection)
                || !finiteNonNegative(homeXg) || !finiteNonNegative(awayXg)) {
            throw new IllegalArgumentException("effective powers and xG must be finite and non-negative");
        }
        if (Math.abs(homePower - homeEffectiveAttack - homeEffectiveProtection) > 1e-9
                || Math.abs(awayPower - awayEffectiveAttack - awayEffectiveProtection) > 1e-9) {
            throw new IllegalArgumentException("team power must equal effective attack plus protection");
        }
        if (configFingerprint == null || inputFingerprint == null) {
            throw new IllegalArgumentException("fingerprints must not be null");
        }
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean finiteProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
