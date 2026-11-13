package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.util.GameMonths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingUpdateScheduleTest {

    private static final int SEASON = 3;
    /** 10 September and 12 September on the game calendar. */
    private static final int SEP_10 = 32 + 9;
    private static final int SEP_12 = 32 + 11;

    private TrainingUpdateSchedule schedule(int defaultDay, Map<Long, Integer> byLeague) {
        TrainingUpdateSchedule schedule = new TrainingUpdateSchedule();
        schedule.setUpdateDayOfMonth(defaultDay);
        schedule.setUpdateDayByLeague(new java.util.HashMap<>(byLeague));
        return schedule;
    }

    @Test
    @DisplayName("""
            GIVEN the game calendar starting on 1 August
            WHEN days are mapped to months
            THEN 1 August, 10 September, 1 February and 28 February land where the calendar shows them
            """)
    void daysMapToTheGameCalendar() {
        assertEquals(0, GameMonths.monthOf(1));
        assertEquals(1, GameMonths.monthOf(SEP_10));
        assertEquals(10, GameMonths.dayOfMonth(SEP_10));
        assertEquals(6, GameMonths.monthOf(185));
        assertEquals(1, GameMonths.dayOfMonth(185));
        assertEquals(28, GameMonths.dayOfMonth(212));
    }

    @Test
    @DisplayName("""
            GIVEN every league updating on the 10th and no update yet this month
            WHEN the days around 10 September are checked
            THEN the 9th is not due, the 10th is due, and once September is recorded the 12th is no longer due
            """)
    void dueOnceAMonthFromTheUpdateDay() {
        TrainingUpdateSchedule schedule = schedule(10, Map.of());
        Integer august = TrainingUpdateSchedule.monthKey(SEASON, 15);

        assertFalse(schedule.isDue(5L, SEASON, SEP_10 - 1, august));
        assertTrue(schedule.isDue(5L, SEASON, SEP_10, august));
        Integer september = TrainingUpdateSchedule.monthKey(SEASON, SEP_10);
        assertFalse(schedule.isDue(5L, SEASON, SEP_12, september));
    }

    @Test
    @DisplayName("""
            GIVEN the update day 10 September falls on a match day, so there is no training that day
            WHEN the next training day, 12 September, is checked
            THEN the update is still due, because September has not been updated yet
            """)
    void aMissedUpdateDayRunsOnTheNextTrainingDay() {
        TrainingUpdateSchedule schedule = schedule(10, Map.of());
        Integer august = TrainingUpdateSchedule.monthKey(SEASON, 15);

        assertTrue(schedule.isDue(5L, SEASON, SEP_12, august));
    }

    @Test
    @DisplayName("""
            GIVEN the default update day 1 and league 7 overridden to the 20th
            WHEN 10 September is checked for both leagues
            THEN the default league is due and league 7 is not
            """)
    void aLeagueCanHaveItsOwnDay() {
        TrainingUpdateSchedule schedule = schedule(1, Map.of(7L, 20));

        assertTrue(schedule.isDue(5L, SEASON, SEP_10, null));
        assertFalse(schedule.isDue(7L, SEASON, SEP_10, null));
        assertEquals(20, schedule.updateDay(7L, 1));
    }

    @Test
    @DisplayName("""
            GIVEN a league updating on the 31st
            WHEN February (28 days) is checked
            THEN its update day becomes 28 February
            """)
    void anUpdateDayPastTheMonthEndUsesTheLastDay() {
        TrainingUpdateSchedule schedule = schedule(31, Map.of());

        assertEquals(28, schedule.updateDay(null, 6));
        assertTrue(schedule.isDue(null, SEASON, 212, null));
    }

    @Test
    @DisplayName("""
            GIVEN an update recorded in July of season 3
            WHEN 1 August of season 4 is checked
            THEN the update is due: month keys keep increasing across seasons
            """)
    void monthsKeepCountingAcrossSeasons() {
        TrainingUpdateSchedule schedule = schedule(1, Map.of());
        Integer julySeason3 = TrainingUpdateSchedule.monthKey(3, 340);

        assertTrue(schedule.isDue(null, 4, 1, julySeason3));
    }
}
