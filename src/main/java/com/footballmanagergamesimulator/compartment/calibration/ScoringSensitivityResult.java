package com.footballmanagergamesimulator.compartment.calibration;

public record ScoringSensitivityResult(
        String scenarioId,
        long seed,
        int seasons,
        int matches,
        String weightKey,
        double baselineValue,
        double testedValue,
        double baselineAveragePoints,
        double testedAveragePoints,
        double averagePoints,
        double pointsDelta,
        double baselineGoalsFor,
        double testedGoalsFor,
        double baselineGoalsAgainst,
        double testedGoalsAgainst,
        double baselineXgFor,
        double testedXgFor,
        double baselineXgAgainst,
        double testedXgAgainst,
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
        int sampleCount,
        String baselineFingerprint,
        String testedFingerprint,
        java.util.List<Double> pairedSeasonDeltas) {
    public ScoringSensitivityResult {
        scenarioId = java.util.Objects.requireNonNull(scenarioId, "scenarioId");
        baselineFingerprint = java.util.Objects.requireNonNull(baselineFingerprint, "baselineFingerprint");
        testedFingerprint = java.util.Objects.requireNonNull(testedFingerprint, "testedFingerprint");
        pairedSeasonDeltas = java.util.List.copyOf(pairedSeasonDeltas);
        if (sampleCount < 0 || !Double.isFinite(averagePoints) || !Double.isFinite(pointsDelta)
                || !Double.isFinite(baselineAveragePoints) || !Double.isFinite(testedAveragePoints)) {
            throw new IllegalArgumentException("invalid sensitivity result");
        }
    }

    public ScoringSensitivityResult(String weightKey, double baselineValue, double testedValue,
                                    double averagePoints, double pointsDelta, int goalsFor, int goalsAgainst,
                                    double xgFor, double xgAgainst, int wins, int draws, int losses,
                                    double attack, double midfield, double defense, double attackProtection,
                                    double confidenceInterval, int sampleCount) {
        this("legacy", 0L, 0, sampleCount, weightKey, baselineValue, testedValue, averagePoints, averagePoints,
                averagePoints, pointsDelta, goalsFor, goalsFor, goalsAgainst, goalsAgainst, xgFor, xgFor,
                xgAgainst, xgAgainst, goalsFor, goalsAgainst, xgFor, xgAgainst, wins, draws, losses,
                attack, midfield, defense, attackProtection, confidenceInterval, sampleCount, "", "", java.util.List.of());
    }
}
