package com.footballmanagergamesimulator.compartment.match;

import java.util.Objects;

/**
 * Expected points (xPts) from goal distributions: P(win), P(draw), P(loss) by convolving the two
 * sides' goal PMFs, xPts = 3·P(win) + P(draw). Pure and deterministic.
 */
public final class ExpectedPoints {

    /** Goals 0..MAX_POISSON_GOALS per side for the Poisson fallback. */
    public static final int MAX_POISSON_GOALS = 10;

    private ExpectedPoints() {
    }

    /** Win/draw/loss probabilities from the home side's point of view. */
    public record Outcome(double homeWin, double draw, double awayWin) {
        public double homeXpts() {
            return 3.0 * homeWin + draw;
        }

        public double awayXpts() {
            return 3.0 * awayWin + draw;
        }
    }

    /** Exact outcome of two independent goal PMFs (index = goals). */
    public static Outcome fromDistributions(double[] home, double[] away) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(away, "away");
        double homeWin = 0.0;
        double draw = 0.0;
        double awayWin = 0.0;
        for (int h = 0; h < home.length; h++) {
            if (home[h] == 0.0) continue;
            for (int a = 0; a < away.length; a++) {
                double mass = home[h] * away[a];
                if (h > a) homeWin += mass;
                else if (h == a) draw += mass;
                else awayWin += mass;
            }
        }
        return new Outcome(homeWin, draw, awayWin);
    }

    /**
     * Fallback when only the two xG values are known: independent Poisson goals with those means,
     * 0..{@link #MAX_POISSON_GOALS} per side, the tail beyond the last value folded into it (the same
     * convention as the underlying-performance view).
     */
    public static Outcome poisson(double homeXg, double awayXg) {
        return fromDistributions(poissonPmf(homeXg), poissonPmf(awayXg));
    }

    static double[] poissonPmf(double mean) {
        if (!Double.isFinite(mean) || mean < 0.0) {
            throw new IllegalArgumentException("xG must be finite and non-negative");
        }
        double[] pmf = new double[MAX_POISSON_GOALS + 1];
        pmf[0] = Math.exp(-mean);
        double accounted = pmf[0];
        for (int k = 1; k < MAX_POISSON_GOALS; k++) {
            pmf[k] = pmf[k - 1] * mean / k;
            accounted += pmf[k];
        }
        pmf[MAX_POISSON_GOALS] = Math.max(0.0, 1.0 - accounted);
        return pmf;
    }

    /** Distribution of the sum of two independent counts. */
    public static double[] convolve(double[] left, double[] right) {
        double[] result = new double[left.length + right.length - 1];
        for (int i = 0; i < left.length; i++) {
            if (left[i] == 0.0) continue;
            for (int j = 0; j < right.length; j++) result[i + j] += left[i] * right[j];
        }
        return result;
    }

    /** Binomial thinning: each of n events is kept with probability {@code keep}. */
    public static double[] thin(double[] counts, double keep) {
        double[] result = new double[counts.length];
        for (int n = 0; n < counts.length; n++) {
            if (counts[n] == 0.0) continue;
            double[] kept = binomial(n, keep);
            for (int k = 0; k <= n; k++) result[k] += counts[n] * kept[k];
        }
        return result;
    }

    /** P(k successes) for k = 0..n with success probability p. */
    public static double[] binomial(int n, double p) {
        double[] result = new double[n + 1];
        result[0] = 1.0;
        for (int trial = 0; trial < n; trial++) {
            for (int k = trial + 1; k >= 1; k--) {
                result[k] = result[k] * (1.0 - p) + result[k - 1] * p;
            }
            result[0] *= 1.0 - p;
        }
        return result;
    }

    /** Count distribution with {@code shots ~ shotDistribution} and each shot scoring with {@code chance}. */
    public static double[] compound(java.util.List<Double> shotDistribution, double chance) {
        int max = shotDistribution.size() - 1;
        double[] result = new double[max + 1];
        for (int shots = 0; shots <= max; shots++) {
            double weight = shotDistribution.get(shots);
            if (weight == 0.0) continue;
            double[] goals = binomial(shots, chance);
            for (int g = 0; g <= shots; g++) result[g] += weight * goals[g];
        }
        return result;
    }

    public static double mean(double[] pmf) {
        double mean = 0.0;
        for (int k = 0; k < pmf.length; k++) mean += k * pmf[k];
        return mean;
    }
}
