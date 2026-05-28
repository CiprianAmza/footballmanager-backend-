package com.footballmanagergamesimulator.engine.invariants;

/**
 * Inputs that define one team's "side" in an invariant test.
 *
 * <p>{@code basePower} is the raw squad rating (sum of best XI ratings before
 * morale/fitness/home adjustments). The runner converts this into an
 * "effective power" by applying morale + home advantage multipliers before
 * calling {@code MatchSimulationService.calculateScores}.
 *
 * <p>{@code morale} is a 0..100 squad-level value. Default 50 = "neutral".
 * Use 95 for a high-morale side, 25 for a low-morale side.
 *
 * <p>{@code home} controls whether home advantage applies to this side. In an
 * invariant test only one side should typically have it.
 *
 * @param label    short name used in diagnostic output (e.g. "Strong FC", "Underdog United")
 * @param basePower base squad rating
 * @param morale    0..100 morale level
 * @param home      true if this is the home team
 */
public record MatchSetup(String label, double basePower, double morale, boolean home) {

    /** Convenience: neutral morale (50), away. */
    public static MatchSetup of(String label, double basePower) {
        return new MatchSetup(label, basePower, 50.0, false);
    }

    /** Convenience: explicit morale, away. */
    public static MatchSetup of(String label, double basePower, double morale) {
        return new MatchSetup(label, basePower, morale, false);
    }

    /** Convenience: home variant of an existing setup. */
    public MatchSetup atHome() {
        return new MatchSetup(label, basePower, morale, true);
    }
}
