package com.footballmanagergamesimulator.util;

/**
 * When managers can sign players, as a pure function of the season day (decided 2026-11-12):
 * summer window June&ndash;September (it spans the season boundary: June&ndash;July end one season,
 * August&ndash;September start the next) and the winter window in January.
 */
public final class TransferWindow {

    /** First day of June and of October (the day the summer window is already closed). */
    public static final int SUMMER_OPEN_DAY = GameMonths.START_DAYS[10];
    public static final int SUMMER_CLOSE_DAY = GameMonths.START_DAYS[2];
    /** First day of January and of February. */
    public static final int WINTER_OPEN_DAY = GameMonths.START_DAYS[5];
    public static final int WINTER_CLOSE_DAY = GameMonths.START_DAYS[6];

    private TransferWindow() {
    }

    public static boolean isOpen(int day) {
        return day >= SUMMER_OPEN_DAY || day < SUMMER_CLOSE_DAY
                || (day >= WINTER_OPEN_DAY && day < WINTER_CLOSE_DAY);
    }
}
