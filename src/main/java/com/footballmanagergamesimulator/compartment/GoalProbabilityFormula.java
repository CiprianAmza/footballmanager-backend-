package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.Arrays;
import java.util.Objects;

/**
 * RNG-free probability preview for the future canonical scorer.
 *
 * <p>It computes matchup shares/xG and the exact Gamma-Poisson predictive PMF analytically. It
 * never samples a score and is not called by any runtime path in Phase 0/1.
 */
public final class GoalProbabilityFormula {

    private final CompartmentEngineConfig.Probability config;

    public GoalProbabilityFormula(CompartmentEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config").getProbability();
    }

    public MatchProbability expectedGoals(double homeAttack, double awayProtection,
                                          double awayAttack, double homeProtection,
                                          double openness) {
        requireNonNegative(homeAttack, "homeAttack");
        requireNonNegative(awayProtection, "awayProtection");
        requireNonNegative(awayAttack, "awayAttack");
        requireNonNegative(homeProtection, "homeProtection");
        requireNonNegative(openness, "openness");
        double qHome = matchupShare(homeAttack, awayProtection, config.getMatchupExponent());
        double qAway = matchupShare(awayAttack, homeProtection, config.getMatchupExponent());
        double homeXg = openness * qHome * config.getHomeAdvantage();
        double awayXg = openness * qAway;
        return new MatchProbability(qHome, qAway, homeXg, awayXg,
                predictiveGoals(homeXg), predictiveGoals(awayXg));
    }

    public GoalDistribution predictiveGoals(double mean) {
        requireNonNegative(mean, "mean");
        double shape = config.getGammaShape();
        int cap = config.getGoalCap();
        if (!Double.isFinite(shape) || shape <= 0) throw new IllegalStateException("gamma shape must be > 0");
        if (cap < 1) throw new IllegalStateException("goal cap must be >= 1");

        double[] pmf = new double[cap + 1];
        if (mean == 0) {
            pmf[0] = 1.0;
        } else {
            double success = shape / (shape + mean);
            double failure = mean / (shape + mean);
            pmf[0] = Math.pow(success, shape);
            double cumulative = pmf[0];
            for (int goals = 1; goals < cap; goals++) {
                pmf[goals] = pmf[goals - 1] * (shape + goals - 1.0) / goals * failure;
                cumulative += pmf[goals];
            }
            pmf[cap] = Math.max(0.0, 1.0 - cumulative);
        }
        double lower = config.getIntervalLowerQuantile();
        double upper = config.getIntervalUpperQuantile();
        if (lower < 0 || upper > 1 || lower > upper) {
            throw new IllegalStateException("predictive interval quantiles must satisfy 0 <= lower <= upper <= 1");
        }
        return new GoalDistribution(mean, shape, cap, pmf, quantile(pmf, lower), quantile(pmf, upper));
    }

    public static double matchupShare(double attack, double opponentProtection, double exponent) {
        requireNonNegative(attack, "attack");
        requireNonNegative(opponentProtection, "opponentProtection");
        if (!Double.isFinite(exponent) || exponent <= 0) {
            throw new IllegalArgumentException("exponent must be finite and > 0");
        }
        if (attack == 0 && opponentProtection == 0) return 0.5;
        double a = Math.pow(attack, exponent);
        double p = Math.pow(opponentProtection, exponent);
        return a / (a + p);
    }

    private static int quantile(double[] pmf, double threshold) {
        double cumulative = 0;
        for (int i = 0; i < pmf.length; i++) {
            cumulative += pmf[i];
            if (cumulative + 1e-15 >= threshold) return i;
        }
        return pmf.length - 1;
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    public record MatchProbability(double homeMatchupShare, double awayMatchupShare,
                                   double homeXg, double awayXg,
                                   GoalDistribution homeGoals, GoalDistribution awayGoals) {}

    public record GoalDistribution(double mean, double gammaShape, int cap,
                                   double[] probabilities, int p05, int p95) {
        public GoalDistribution {
            probabilities = Arrays.copyOf(probabilities, probabilities.length);
        }
        @Override public double[] probabilities() {
            return Arrays.copyOf(probabilities, probabilities.length);
        }
    }
}
