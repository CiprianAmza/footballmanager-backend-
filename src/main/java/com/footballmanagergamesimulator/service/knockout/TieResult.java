package com.footballmanagergamesimulator.service.knockout;

/**
 * Outcome of a resolved knockout tie. "A" is the first team supplied to
 * {@link KnockoutTieResolver#resolve}; in a two-leg tie A hosts leg 1 and B
 * hosts leg 2.
 *
 * <p>Goal fields are always team-A-first (A's goals, then B's goals), regardless
 * of which side was at home in a given leg. For a single-leg tie {@code leg2A}
 * and {@code leg2B} are {@code -1} (not played) and {@code aggregateA/B} equal
 * the single match.
 *
 * <p>{@code aggregateA/B} is the normal-time aggregate, BEFORE any extra time.
 * If {@code extraTime} is true, {@code etA/etB} are the extra-time goals (added
 * on top of the aggregate). If {@code penalties} is true the tie was still level
 * after extra time and went to a shootout. {@code teamAWon} is the final winner.
 */
public record TieResult(
        LegFormat format,
        int leg1A, int leg1B,
        int leg2A, int leg2B,
        int aggregateA, int aggregateB,
        boolean extraTime, int etA, int etB,
        boolean penalties, int penaltyA, int penaltyB,
        boolean teamAWon) {

    public boolean teamBWon() {
        return !teamAWon;
    }

    /** Human-readable one-liner, e.g. "2-2 agg (1-1, 1-1) a.e.t. 0-0, pens A". */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (format == LegFormat.TWO_LEG) {
            sb.append(aggregateA + etA).append('-').append(aggregateB + etB).append(" agg (")
              .append(leg1A).append('-').append(leg1B).append(", ")
              .append(leg2A).append('-').append(leg2B).append(')');
        } else {
            sb.append(leg1A + etA).append('-').append(leg1B + etB);
        }
        if (extraTime) sb.append(" a.e.t. (+").append(etA).append('-').append(etB).append(')');
        if (penalties) sb.append(", pens ").append(penaltyA).append('-').append(penaltyB);
        return sb.toString();
    }
}
