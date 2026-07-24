package com.footballmanagergamesimulator.compartment.calibration;

public record CalibrationSegmentSnapshot(
        long teamSamples,
        double meanObservedGoalsFor,
        double meanObservedGoalsAgainst,
        double meanCanonicalXgFor,
        double meanCanonicalXgAgainst,
        double meanAttack,
        double meanAttackProtection) {
}
