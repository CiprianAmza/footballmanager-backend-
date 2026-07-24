package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.GoalProbabilityFormula;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;

import java.util.Objects;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;

/** Pure, deterministic inverse-CDF sampler for one canonical match evaluation. */
@Component
public final class CanonicalScoreSampler {
    private static final double SUM_TOLERANCE = 1e-9;

    public GoalSample sample(CanonicalMatchEvaluation evaluation, long seed) {
        Objects.requireNonNull(evaluation, "evaluation");
        SplittableRandom random = new SplittableRandom(seed);
        GoalProbabilityFormula.GoalDistribution home = evaluation.probability().homeGoals();
        GoalProbabilityFormula.GoalDistribution away = evaluation.probability().awayGoals();
        validate(home);
        validate(away);
        return new GoalSample(sample(home, random.nextDouble()), sample(away, random.nextDouble()));
    }

    private static int sample(GoalProbabilityFormula.GoalDistribution distribution, double random) {
        double cumulative = 0.0;
        double[] probabilities = distribution.probabilities();
        for (int bucket = 0; bucket < probabilities.length; bucket++) {
            cumulative += probabilities[bucket];
            if (random < cumulative) return bucket;
        }
        return probabilities.length - 1;
    }

    private static void validate(GoalProbabilityFormula.GoalDistribution distribution) {
        Objects.requireNonNull(distribution, "distribution");
        int cap = distribution.cap();
        double[] probabilities = distribution.probabilities();
        if (cap < 0 || probabilities.length != cap + 1) {
            throw new IllegalArgumentException("invalid goal PMF length/cap");
        }
        double sum = 0.0;
        for (double probability : probabilities) {
            if (!Double.isFinite(probability) || probability < 0.0) {
                throw new IllegalArgumentException("goal PMF must be finite and non-negative");
            }
            sum += probability;
        }
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("goal PMF must sum to 1.0");
        }
    }

    public record GoalSample(int homeGoals, int awayGoals) {}
}
