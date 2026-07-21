package com.footballmanagergamesimulator.util;

import java.util.List;
import java.util.Random;

/**
 * Shared position-based weights for synthesising goal/assist events, used by
 * BOTH the live match engine ({@code LiveMatchSimulationService}) and the
 * batch/synthetic timeline generator ({@code MatchSimulationService}) so a
 * watched match and an un-watched one distribute scorers and assisters the
 * same way.
 *
 * <p>Scorer weights reward advanced positions (a striker is far more likely to
 * finish than a defender). Assist weights reward creators — wide players and
 * the attacking midfielder highest, then the deeper midfield and the striker,
 * and defenders least.
 */
public final class PositionScoringWeights {

    private PositionScoringWeights() {}

    /** Bounded attribute multiplier range: a 1..20 attribute maps into [MIN, MAX]
     *  so a named skill nudges the pick without swamping rating. */
    private static final double ATTR_MULT_MIN = 0.80;
    private static final double ATTR_MULT_MAX = 1.20;

    /**
     * Map a 1..20 attribute onto a bounded multiplier in [0.80, 1.20] (≈1.0 at
     * the 10-11 midpoint). Returns a neutral 1.0 when the attribute is unknown
     * ({@code <= 0}), so missing skills neither help nor hurt.
     */
    public static double attributeMultiplier(double attribute) {
        if (attribute <= 0) return 1.0;
        double clamped = Math.max(1.0, Math.min(20.0, attribute));
        return ATTR_MULT_MIN + (clamped - 1.0) / 19.0 * (ATTR_MULT_MAX - ATTR_MULT_MIN);
    }

    /** Positional component of the goal-scoring likelihood. */
    public static double scorerWeight(String position) {
        if (position == null) return 1.0;
        return switch (position) {
            case "ST" -> 3.0;
            case "AMC", "AML", "AMR" -> 2.0;
            case "MC", "ML", "MR" -> 1.2;
            case "DC", "DL", "DR", "DM" -> 0.4;
            default -> 1.0;
        };
    }

    /**
     * Full goal-scoring likelihood: the positional weight scaled by a bounded
     * Finishing multiplier (0.80..1.20). Finishing nudges the pick without
     * dominating rating; {@code <= 0} (skills unavailable) is neutral.
     */
    public static double scorerWeight(String position, int finishing) {
        return scorerWeight(position) * attributeMultiplier(finishing);
    }

    /** Positional component of the assist likelihood. */
    public static double assistWeight(String position) {
        if (position == null) return 1.0;
        return switch (position) {
            case "AMC" -> 3.0;
            case "AML", "AMR", "ML", "MR" -> 2.5;
            case "MC" -> 2.4;
            case "DM" -> 2.0;
            case "DL", "DR" -> 1.5;
            case "DC" -> 0.5;
            default -> 1.0;
        };
    }

    /**
     * Full assist likelihood: the positional weight scaled by a bounded
     * creativity multiplier (0.80..1.20) from the mean of Passing and Vision.
     * A gifted playmaker is favoured without swamping rating; {@code <= 0} for
     * either attribute (skills unavailable) is neutral.
     */
    public static double assistWeight(String position, int passing, int vision) {
        double creativity = (passing > 0 && vision > 0) ? (passing + vision) / 2.0 : 0;
        return assistWeight(position) * attributeMultiplier(creativity);
    }

    /**
     * Weighted pick from {@code players} using the supplied per-player weight.
     * Falls back to a uniform pick when every weight is non-positive. Returns
     * {@code null} only for an empty list.
     */
    public static <T> T weightedPick(List<T> players,
                                     java.util.function.ToDoubleFunction<T> weightFn,
                                     Random random) {
        if (players == null || players.isEmpty()) return null;
        if (players.size() == 1) return players.get(0);

        double total = 0;
        double[] weights = new double[players.size()];
        for (int i = 0; i < players.size(); i++) {
            weights[i] = Math.max(0, weightFn.applyAsDouble(players.get(i)));
            total += weights[i];
        }
        if (total <= 0) return players.get(random.nextInt(players.size()));

        double roll = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < players.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) return players.get(i);
        }
        return players.get(players.size() - 1);
    }
}
