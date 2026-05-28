package com.footballmanagergamesimulator.engine.invariants;

/**
 * One testable rule about engine behavior. Read as:
 * <p>"Over {@code iterations} matches between {@code setupA} and {@code setupB},
 * setupA must win between {@code minWinRateA} and {@code maxWinRateA} (inclusive)."
 *
 * <p>For pure-dominance assertions (e.g. "6× stronger team must win ≥80%")
 * leave {@code maxWinRateA = 1.0} and only the lower bound matters.
 * For "balanced" assertions (e.g. "equal teams ~30-40% wins each side")
 * set both bounds.
 *
 * @param name        short kebab-case id, e.g. "power-6x-dominance"
 * @param description human-readable rationale shown in the report
 * @param setupA      home / "team being tested" side
 * @param setupB      away / opponent side
 * @param iterations  number of matches to simulate
 * @param minWinRateA inclusive lower bound on setup-A win rate (0..1)
 * @param maxWinRateA inclusive upper bound on setup-A win rate (0..1)
 */
public record EngineInvariant(
        String name,
        String description,
        MatchSetup setupA,
        MatchSetup setupB,
        int iterations,
        double minWinRateA,
        double maxWinRateA) {

    /** Lower-bound-only invariant: "A must win at least X% of the time". */
    public static EngineInvariant atLeast(String name, String description,
                                          MatchSetup setupA, MatchSetup setupB,
                                          int iterations, double minWinRateA) {
        return new EngineInvariant(name, description, setupA, setupB, iterations, minWinRateA, 1.0);
    }

    /** Range invariant: "A must win between min% and max% of the time". */
    public static EngineInvariant inRange(String name, String description,
                                          MatchSetup setupA, MatchSetup setupB,
                                          int iterations, double minWinRateA, double maxWinRateA) {
        return new EngineInvariant(name, description, setupA, setupB, iterations, minWinRateA, maxWinRateA);
    }

    /** Upper-bound-only invariant: "A must win at most X% of the time" (upset cap). */
    public static EngineInvariant atMost(String name, String description,
                                         MatchSetup setupA, MatchSetup setupB,
                                         int iterations, double maxWinRateA) {
        return new EngineInvariant(name, description, setupA, setupB, iterations, 0.0, maxWinRateA);
    }
}
