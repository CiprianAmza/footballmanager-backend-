package com.footballmanagergamesimulator.compartment.calibration;

public record CalibrationThresholds(
        long minimumSamples,
        double maximumMeanHomeGoalsDelta,
        double maximumMeanAwayGoalsDelta,
        double maximumOutcomeRateDelta,
        double maximumUpsetRateDelta,
        double maximumMulticlassBrierScore) {
    public CalibrationThresholds {
        if (minimumSamples < 0) throw new IllegalArgumentException("minimumSamples must be non-negative");
        requireNonNegativeFinite(maximumMeanHomeGoalsDelta, "maximumMeanHomeGoalsDelta");
        requireNonNegativeFinite(maximumMeanAwayGoalsDelta, "maximumMeanAwayGoalsDelta");
        requireUnit(maximumOutcomeRateDelta, "maximumOutcomeRateDelta");
        requireUnit(maximumUpsetRateDelta, "maximumUpsetRateDelta");
        requireUnit(maximumMulticlassBrierScore, "maximumMulticlassBrierScore");
    }

    public static CalibrationThresholds recommended() {
        return new CalibrationThresholds(10_000, 0.20, 0.20, 0.04, 0.04, 0.24);
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
    }
}
