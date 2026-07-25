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
        String inputFingerprint) {
    public CanonicalRuntimeScore(int homeGoals, int awayGoals, double homePower, double awayPower,
                                 CanonicalMatchEvaluation evaluation) {
        this(homeGoals, awayGoals, homePower, awayPower, evaluation, "", "");
    }

    public CanonicalRuntimeScore {
        evaluation = Objects.requireNonNull(evaluation, "evaluation");
        int cap = evaluation.probability().homeGoals().cap();
        if (evaluation.probability().awayGoals().cap() != cap) {
            throw new IllegalArgumentException("home and away goal caps must match");
        }
        if (homeGoals < 0 || homeGoals > cap || awayGoals < 0 || awayGoals > cap) {
            throw new IllegalArgumentException("goals must be within the evaluation cap");
        }
        if (!Double.isFinite(homePower) || homePower < 0.0
                || !Double.isFinite(awayPower) || awayPower < 0.0) {
            throw new IllegalArgumentException("powers must be finite and non-negative");
        }
        if (configFingerprint == null || inputFingerprint == null) {
            throw new IllegalArgumentException("fingerprints must not be null");
        }
    }
}
