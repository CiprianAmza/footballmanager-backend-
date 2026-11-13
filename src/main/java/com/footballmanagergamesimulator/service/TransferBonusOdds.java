package com.footballmanagergamesimulator.service;

/**
 * How likely the selling club thinks a performance bonus is to be paid (design §3.5, defaults to tune in testing).
 * The bonus runs for {@link #SEASONS} seasons and is paid once, so the chance is that of the condition being met in
 * at least one of them.
 *
 * <ul>
 *   <li>Goals: per season, the player's goals last season divided by the target (at most 1).</li>
 *   <li>Title: per season, from the buyer's league finish last season: 1st 50%, 2nd 25%, 3rd 15%, else 5%.</li>
 * </ul>
 */
public final class TransferBonusOdds {

    public static final int SEASONS = 2;
    public static final int MAX_GOALS_TARGET = 60;

    private TransferBonusOdds() {
    }

    public static double goalsPerSeason(int goalsLastSeason, int target) {
        if (target <= 0) return 0;
        return Math.min(1.0, Math.max(0, goalsLastSeason) / (double) target);
    }

    /** {@code lastLeaguePosition} 0 = unknown. */
    public static double titlePerSeason(long lastLeaguePosition) {
        if (lastLeaguePosition == 1) return 0.50;
        if (lastLeaguePosition == 2) return 0.25;
        if (lastLeaguePosition == 3) return 0.15;
        return 0.05;
    }

    /** Met at least once in {@link #SEASONS} seasons. */
    public static double overContract(double perSeason) {
        return 1 - Math.pow(1 - perSeason, SEASONS);
    }
}
