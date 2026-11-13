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
            chance += player.pace() >= EXCEPTIONAL
                    ? rules.getPace20Bonus() + levelsAbove(player.pace()) * rules.getPace20Step()
                    : -rules.getNonPace20Penalty();
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
     * Below 20 the striker converts {@code finishing19-factor × Finishing / 19} of the control
     * percentage (19 → 60%); at 20 {@code finishing20-factor} (80%), plus {@code finishing-step}
     * per further level (25 → 100%). Pace 19 caps the result at 25%, lower values scale that cap
     * proportionally, while pace 20 and above is free.
     */
    public double strikerGoalChance(double controlChance, int finishing, int pace,
                                    double opponentPace20Penalty) {
        requireAttribute(finishing, "finishing");
        requireAttribute(pace, "pace");
        if (!Double.isFinite(opponentPace20Penalty) || opponentPace20Penalty < 0) {
            throw new IllegalArgumentException("opponentPace20Penalty must be non-negative");
        }
        double finishingFactor = finishing >= EXCEPTIONAL
                ? clampProbability(rules.getFinishing20Factor() + levelsAbove(finishing) * rules.getFinishingStep())
                : rules.getFinishing19Factor() * finishing / 19.0;
        double chance = clampProbability(controlChance) * finishingFactor;
        if (pace < EXCEPTIONAL) {
            chance = Math.min(chance, rules.getPace19Chance() * pace / 19.0);
        }
        chance -= opponentPace20Penalty;
        return clampProbability(chance);
    }

    /**
     * Total penalty the opponent's Pace 20+ midfielders put on our striker: the base penalty
     * for each, plus {@code pace20-step} per level above 20.
     */
    public double opponentPace20Penalty(TeamCompartmentAggregator.PassingStyleProfile profile,
                                        Long redCardPlayerId) {
        if (profile == null) return 0.0;
        return profile.midfielders().stream()
                .filter(player -> !Objects.equals(player.playerId(), redCardPlayerId))
                .filter(player -> player.pace() >= EXCEPTIONAL)
                .mapToDouble(player -> rules.getOpponentPace20Penalty()
                        + levelsAbove(player.pace()) * rules.getPace20Step())
                .sum();
    }

    private static final int EXCEPTIONAL = 20;

    private static int levelsAbove(int value) {
        return Math.max(0, value - EXCEPTIONAL);
    }

    private static void requireAttribute(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be at least 1");
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
