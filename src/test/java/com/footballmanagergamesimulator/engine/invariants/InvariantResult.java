package com.footballmanagergamesimulator.engine.invariants;

/**
 * Outcome of running one {@link EngineInvariant} N times.
 *
 * @param invariant      the invariant we measured
 * @param winsA          times setup-A won
 * @param draws          draws
 * @param winsB          times setup-B won
 * @param avgGoalsA      mean goals scored by A
 * @param avgGoalsB      mean goals scored by B
 * @param passed         true if win-rate-A is within [minWinRateA, maxWinRateA]
 */
public record InvariantResult(
        EngineInvariant invariant,
        int winsA,
        int draws,
        int winsB,
        double avgGoalsA,
        double avgGoalsB,
        boolean passed) {

    public double winRateA() {
        return winsA / (double) invariant.iterations();
    }

    public double drawRate() {
        return draws / (double) invariant.iterations();
    }

    public double winRateB() {
        return winsB / (double) invariant.iterations();
    }

    /** Human-readable diagnostic for the pass/fail report. */
    public String diagnostic() {
        double rateA = winRateA();
        if (passed) {
            return String.format("PASS  %s: A=%.1f%% (target %.1f%%-%.1f%%) D=%.1f%% B=%.1f%% goals %.2f-%.2f",
                    invariant.name(), rateA * 100,
                    invariant.minWinRateA() * 100, invariant.maxWinRateA() * 100,
                    drawRate() * 100, winRateB() * 100,
                    avgGoalsA, avgGoalsB);
        } else {
            String direction = rateA < invariant.minWinRateA() ? "TOO LOW" : "TOO HIGH";
            return String.format("FAIL  %s: A=%.1f%% (target %.1f%%-%.1f%%, %s) D=%.1f%% B=%.1f%% goals %.2f-%.2f",
                    invariant.name(), rateA * 100,
                    invariant.minWinRateA() * 100, invariant.maxWinRateA() * 100, direction,
                    drawRate() * 100, winRateB() * 100,
                    avgGoalsA, avgGoalsB);
        }
    }
}
