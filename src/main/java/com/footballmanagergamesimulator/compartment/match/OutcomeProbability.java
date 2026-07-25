package com.footballmanagergamesimulator.compartment.match;

public record OutcomeProbability(double homeWin, double draw, double awayWin) {
    private static final double SUM_TOLERANCE = 1e-9;

    public OutcomeProbability {
        requireProbability(homeWin, "homeWin");
        requireProbability(draw, "draw");
        requireProbability(awayWin, "awayWin");
        if (Math.abs(homeWin + draw + awayWin - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("outcome probabilities must sum to 1.0");
        }
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
    }
}
