package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.util.GameMonths;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * When a league's players get their monthly training update (growth and decline).
 *
 * <p>Each league has one update day per month ({@code update-day-of-month}, overridable per
 * league in {@code update-day-by-league}). Training does not happen on match days and the
 * day after, so the update runs on the first training day on or after that day, once per
 * month: the caller remembers the last month it updated (see {@link #monthKey}).
 */
@Component
@ConfigurationProperties(prefix = "simulation.training")
@Getter
@Setter
public class TrainingUpdateSchedule {

    /** The day of the month every league updates on, unless overridden. */
    private int updateDayOfMonth = 1;

    /** Per-league override: competition id → day of the month. */
    private Map<Long, Integer> updateDayByLeague = new HashMap<>();

    /** The update day this league uses in {@code month}, clamped to the month's length. */
    public int updateDay(Long leagueId, int month) {
        int day = leagueId == null ? updateDayOfMonth
                : updateDayByLeague.getOrDefault(leagueId, updateDayOfMonth);
        return Math.max(1, Math.min(GameMonths.LENGTHS[month], day));
    }

    /** Identifies a month across seasons, increasing with time. */
    public static int monthKey(int season, int day) {
        return season * 12 + GameMonths.monthOf(day);
    }

    /**
     * Whether the league's players are due their update today.
     *
     * @param lastUpdatedMonthKey the {@link #monthKey} of the last update, or null if none yet
     */
    public boolean isDue(Long leagueId, int season, int day, Integer lastUpdatedMonthKey) {
        int month = GameMonths.monthOf(day);
        if (GameMonths.dayOfMonth(day) < updateDay(leagueId, month)) return false;
        return lastUpdatedMonthKey == null || lastUpdatedMonthKey < monthKey(season, day);
    }
}
