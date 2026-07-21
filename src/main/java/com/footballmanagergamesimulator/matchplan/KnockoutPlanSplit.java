package com.footballmanagergamesimulator.matchplan;

/**
 * Pure mapping from a resolved knockout tie onto the {@link MatchPlan} inputs:
 * the regular-time (90') score, the extra-time goals, and the shootout — kept
 * strictly separate. Extra-time goals and shootout tallies are passed as nullable
 * (null = not played) and normalised to the {@code -1} sentinel the plan uses.
 *
 * <p>This is deliberately free of any {@code decidedBy}-string parsing or
 * {@code final - score90} arithmetic: the caller supplies the already-oriented
 * per-team values (from {@code KnockoutMatchResolution}), so the mapping is a
 * trivially testable, side-effect-free transform.
 */
public record KnockoutPlanSplit(int score90Home, int score90Away,
                                int etHome, int etAway,
                                int shootoutHome, int shootoutAway) {

    private static final int NOT_PLAYED = -1;

    /** League / non-knockout match: regular time only. */
    public static KnockoutPlanSplit regularOnly(int score90Home, int score90Away) {
        return new KnockoutPlanSplit(score90Home, score90Away,
                NOT_PLAYED, NOT_PLAYED, NOT_PLAYED, NOT_PLAYED);
    }

    /**
     * Knockout match. {@code et*}/{@code shootout*} are per-team values already
     * oriented to home/away; pass {@code null} for a phase that was not played.
     */
    public static KnockoutPlanSplit knockout(int score90Home, int score90Away,
                                             Integer etHome, Integer etAway,
                                             Integer shootoutHome, Integer shootoutAway) {
        return new KnockoutPlanSplit(score90Home, score90Away,
                sentinel(etHome), sentinel(etAway),
                sentinel(shootoutHome), sentinel(shootoutAway));
    }

    private static int sentinel(Integer value) {
        return value != null ? value : NOT_PLAYED;
    }
}
