package com.footballmanagergamesimulator.compartment.calibration;

public record ScoringSensitivityResult(
        String weightKey,
        double baselineValue,
        double testedValue,
        double averagePoints,
        double pointsDelta,
        int goalsFor,
        int goalsAgainst,
        double xgFor,
        double xgAgainst,
        int wins,
        int draws,
        int losses,
        double attack,
        double midfield,
        double defense,
        double attackProtection,
        double confidenceInterval,
        int sampleCount) {
    public ScoringSensitivityResult {
        if (sampleCount < 0 || !Double.isFinite(averagePoints) || !Double.isFinite(pointsDelta)) {
            throw new IllegalArgumentException("invalid sensitivity result");
        }
    }
}
