package com.footballmanagergamesimulator.util;

/**
 * The game's season calendar: days 1&ndash;365, starting in August and ending in July
 * (February has 28 days).
 */
public final class GameMonths {

    public static final String[] NAMES = {
            "August", "September", "October", "November", "December",
            "January", "February", "March", "April", "May", "June", "July"
    };
    public static final int[] START_DAYS = {
            1, 32, 62, 93, 123, 154, 185, 213, 244, 274, 305, 335
    };
    public static final int[] LENGTHS = {
            31, 30, 31, 30, 31, 31, 28, 31, 30, 31, 30, 31
    };

    private GameMonths() {
    }

    /** 0 = August … 11 = July. Days outside 1&ndash;365 are clamped. */
    public static int monthOf(int day) {
        int safe = Math.max(1, Math.min(365, day));
        for (int i = START_DAYS.length - 1; i >= 0; i--) {
            if (safe >= START_DAYS[i]) return i;
        }
        return 0;
    }

    /** 1-based day inside its month. */
    public static int dayOfMonth(int day) {
        int safe = Math.max(1, Math.min(365, day));
        return safe - START_DAYS[monthOf(safe)] + 1;
    }
}
