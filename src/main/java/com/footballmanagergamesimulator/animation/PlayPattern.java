package com.footballmanagergamesimulator.animation;

import java.util.Random;

/**
 * One entry of the play-pattern library. A pattern decides how the phase
 * LOOKS (ball route, spatial shape); it can never decide or alter the
 * canonical outcome, scorer, assister or minute it receives.
 */
public interface PlayPattern {

    /** Stable identifier, persisted inside {@link AnimationRecipe}. */
    String id();

    /**
     * Whether this pattern can animate the given spec: phase, outcome and
     * roster requirements (e.g. ONE_TWO needs an assister). Patterns unable
     * to support a combination simply opt out; the director always has the
     * safe fallback available.
     */
    boolean supports(MatchMomentSpec spec);

    /** Relative selection weight among eligible patterns (default 1). */
    default double weight(MatchMomentSpec spec) {
        return 1.0;
    }

    /**
     * Produce the choreography for this spec. Must be a pure function of
     * (spec, rng) — no clocks, no global state — so the same seed replays
     * identically forever.
     */
    Choreography choreograph(MatchMomentSpec spec, Random rng);
}
