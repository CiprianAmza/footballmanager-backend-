package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

/**
 * What a player's fitness (0..100) leaves of his contribution:
 * {@code max(floor, 1 - sensitivity × max(0, fresh - fitness) / 100)}.
 *
 * <p>At {@code fresh} (80, the season-start level) and above he plays at exactly 100%: fitness above
 * it is a reserve that delays the drop, never a bonus. With the shipped numbers 60 gives 0.85, 40 gives
 * 0.70 and the floor (0.60) is reached at 26.7. The same factor ranks players when an eleven is picked,
 * so a tired starter makes way for a fresher squad player only when that is the better choice.
 */
public final class FitnessFactor {

    private FitnessFactor() {}

    public static double of(double fitness, double fresh, double sensitivity, double floor) {
        double clamped = Math.max(0.0, Math.min(100.0, fitness));
        double deficit = Math.max(0.0, fresh - clamped);
        return Math.max(floor, 1.0 - sensitivity * deficit / 100.0);
    }

    public static double of(double fitness, CompartmentEngineConfig.Rating rating) {
        CompartmentEngineConfig.Rating r = rating == null ? new CompartmentEngineConfig.Rating() : rating;
        return of(fitness, r.getFitnessFresh(), r.getFitnessSensitivity(), r.getFitnessFloor());
    }

    public static double of(double fitness, CompartmentEngineConfig config) {
        return of(fitness, config == null ? null : config.getRating());
    }

    // ------------------------------------------------------------------ tempo: drain and in-match fatigue

    /** The tempo's drain multiplier; a club without a tactic, or an unknown tempo, plays Standard (1.0). */
    public static double tempoMultiplier(MatchEngineConfig.Fitness cfg, String tempo) {
        if (tempo == null || tempo.isBlank()) return 1.0;
        Double value = cfg.getTempoDrain().get(tempo.trim());
        return value == null ? 1.0 : value;
    }

    /** {@code staminaBase - staminaSlope × stamina/20}: stamina 20 → 0.85, 10 → 1.00, 1 → 1.135. */
    public static double staminaFactor(MatchEngineConfig.Fitness cfg, int stamina) {
        int s = Math.max(1, Math.min(20, stamina));
        return cfg.getStaminaBase() - cfg.getStaminaSlope() * s / 20.0;
    }

    /** Fitness one player loses in a classic match: base drain × tempo × stamina. */
    public static double matchDrain(MatchEngineConfig config, String tempo, int stamina) {
        return config.getStamina().getBatchMatchFitnessDrain()
                * tempoMultiplier(config.getFitness(), tempo)
                * staminaFactor(config.getFitness(), stamina);
    }

    /**
     * The fitness a player brings to the scoring of a match played at {@code tempo}. A tempo that
     * costs no more than Standard leaves the pre-match fitness exactly as it is (so Standard and
     * slower tempos, and every AI club on default settings, are untouched). Above Standard the
     * player cannot carry the pace for the whole match: he plays at
     * {@code base - inMatchShare × (drain(tempo) - drain(Standard))}, where {@code base} is the
     * pre-match fitness, capped at {@code fresh} when {@code inMatchFromFresh} — the reserve above
     * the fresh level protects against accumulated tiredness, not against the pace of the match.
     */
    public static double inMatch(MatchEngineConfig config, double preMatch, String tempo, int stamina, double fresh) {
        if (config == null) return preMatch;
        MatchEngineConfig.Fitness cfg = config.getFitness();
        if (cfg.getInMatchShare() <= 0) return preMatch;
        double extra = matchDrain(config, tempo, stamina) - matchDrain(config, "Standard", stamina);
        if (extra <= 0) return preMatch;
        double base = cfg.isInMatchFromFresh() ? Math.min(preMatch, fresh) : preMatch;
        return Math.max(0.0, base - cfg.getInMatchShare() * extra);
    }
}
