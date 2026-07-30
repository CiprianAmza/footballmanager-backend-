package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.TeamCompartmentAggregator;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;

import java.util.List;
import java.util.Objects;

/** Pure arithmetic for the explicit high-recovery PASSING STYLE mechanic. */
public final class PassingStyleMatchMechanic {
    private final CompartmentEngineConfig.PassingStyle rules;

    public PassingStyleMatchMechanic(CompartmentEngineConfig config) {
        this.rules = Objects.requireNonNull(config, "config").getPassingStyle();
        validateDistribution(rules.getStrikerOpportunityDistribution());
    }

    /** Probability that each already-generated normal opponent goal is erased. */
    public double suppression(TeamCompartmentAggregator.PassingStyleProfile profile,
                              Long ownRedCardPlayerId,
                              TeamCompartmentAggregator.TeamAggregationResult opponent) {
        return suppression(profile, ownRedCardPlayerId, opponent.passingType(), opponent.pressing());
    }

    double suppression(TeamCompartmentAggregator.PassingStyleProfile profile,
                       Long ownRedCardPlayerId, String opponentPassing, String opponentPressing) {
        if (profile == null || !profile.active()) return 0.0;
        double chance;
        if ("Long".equalsIgnoreCase(opponentPassing)) {
            chance = rules.getLongPassingSuppression();
        } else if ("Very Aggressive".equalsIgnoreCase(opponentPressing)) {
            chance = rules.getVeryAggressiveSuppression();
        } else if ("Aggressive".equalsIgnoreCase(opponentPressing)) {
            chance = rules.getAggressiveSuppression();
        } else {
            chance = rules.getBaseSuppression();
        }
        for (TeamCompartmentAggregator.PassingMidfielder player : profile.midfielders()) {
            if (Objects.equals(player.playerId(), ownRedCardPlayerId)) continue;
            chance += player.pace() == 20 ? rules.getPace20Bonus() : -rules.getNonPace20Penalty();
        }
        return clampProbability(chance);
    }

    public int sampleOpportunityCount(double draw) {
        if (!Double.isFinite(draw) || draw < 0.0 || draw >= 1.0) {
            throw new IllegalArgumentException("draw must be in [0,1)");
        }
        double cumulative = 0.0;
        List<Double> distribution = rules.getStrikerOpportunityDistribution();
        for (int count = 0; count < distribution.size(); count++) {
            cumulative += distribution.get(count);
            if (draw < cumulative) return count;
        }
        return distribution.size() - 1;
    }

    /**
     * Goal probability for every special striker opportunity.
     * Finishing 20 unlocks the whole control percentage. Finishing 19 loses 40% of that chance,
     * and every lower value loses progressively more through a linear 1..19 scale.
     * Pace 19 caps the result at 25%, lower values scale that cap proportionally, while pace 20 is free.
     */
    public double strikerGoalChance(double controlChance, int finishing, int pace,
                                    int opponentPace20Midfielders) {
        requireAttribute(finishing, "finishing");
        requireAttribute(pace, "pace");
        if (opponentPace20Midfielders < 0) {
            throw new IllegalArgumentException("opponentPace20Midfielders must be non-negative");
        }
        double finishingFactor = finishing == 20 ? 1.0
                : rules.getFinishing19Factor() * finishing / 19.0;
        double chance = clampProbability(controlChance) * finishingFactor;
        if (pace < 20) {
            chance = Math.min(chance, rules.getPace19Chance() * pace / 19.0);
        }
        chance -= opponentPace20Midfielders * rules.getOpponentPace20Penalty();
        return clampProbability(chance);
    }

    public int pace20Midfielders(TeamCompartmentAggregator.PassingStyleProfile profile,
                                 Long redCardPlayerId) {
        if (profile == null) return 0;
        return (int) profile.midfielders().stream()
                .filter(player -> !Objects.equals(player.playerId(), redCardPlayerId))
                .filter(player -> player.pace() == 20)
                .count();
    }

    private static void requireAttribute(int value, String name) {
        if (value < 1 || value > 20) throw new IllegalArgumentException(name + " must be in [1,20]");
    }

    private static double clampProbability(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void validateDistribution(List<Double> distribution) {
        if (distribution == null || distribution.size() != 7) {
            throw new IllegalArgumentException("passing-style opportunity distribution must have 7 buckets");
        }
        double sum = 0.0;
        for (Double value : distribution) {
            if (value == null || !Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("passing-style distribution must be finite and non-negative");
            }
            sum += value;
        }
        if (Math.abs(sum - 1.0) > 1e-9) {
            throw new IllegalArgumentException("passing-style distribution must sum to 1.0");
        }
    }
}
