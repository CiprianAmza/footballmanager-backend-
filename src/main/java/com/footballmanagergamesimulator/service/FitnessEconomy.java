package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.FitnessFactor;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TrainingController;
import com.footballmanagergamesimulator.model.TrainingSchedule;

import java.util.List;

/**
 * The fitness economy shared by human and AI clubs, as pure functions: what a classic match costs
 * (tempo × stamina), what a day without a match gives back (rest or training, intensity, natural
 * fitness), and how much of the default schedule a club actually trained (its training load).
 *
 * <p>The match-strength side (what a tired player is still worth) is
 * {@link com.footballmanagergamesimulator.compartment.FitnessFactor}.
 */
public final class FitnessEconomy {

    private FitnessEconomy() {}

    /** The default weekly schedule every AI club follows (and a human club without one). */
    private static final List<TrainingSchedule> DEFAULT_WEEK = TrainingController.buildDefaultSchedule(0);

    // ------------------------------------------------------------------ match

    /** The tempo's drain multiplier; a club without a tactic, or an unknown tempo, plays Standard (1.0). */
    public static double tempoMultiplier(MatchEngineConfig.Fitness cfg, String tempo) {
        return FitnessFactor.tempoMultiplier(cfg, tempo);
    }

    /** {@code staminaBase - staminaSlope × stamina/20}: stamina 20 → 0.85, 10 → 1.00, 1 → 1.135. */
    public static double staminaFactor(MatchEngineConfig.Fitness cfg, int stamina) {
        return FitnessFactor.staminaFactor(cfg, stamina);
    }

    /** Fitness one player loses in a classic match: base drain × tempo × stamina. */
    public static double matchDrain(MatchEngineConfig config, String tempo, int stamina) {
        return FitnessFactor.matchDrain(config, tempo, stamina);
    }

    /** The fitness a player brings to the scoring of a match at {@code tempo}; see {@link FitnessFactor#inMatch}. */
    public static double matchFitness(MatchEngineConfig config, double preMatch, String tempo, int stamina,
                                      double fresh) {
        return FitnessFactor.inMatch(config, preMatch, tempo, stamina, fresh);
    }

    /** Fitness after the match, never below the post-match floor. */
    public static double afterMatch(MatchEngineConfig config, double fitness, String tempo, int stamina) {
        return Math.max(config.getStamina().getPostMatchFloor(), fitness - matchDrain(config, tempo, stamina));
    }

    // ------------------------------------------------------------------ days

    /**
     * One calendar day of a club's weekly schedule. {@code rest}: every session of the day is Rest or
     * at intensity 0 (or the day has none), so nothing is trained. {@code intensity}: the sessions'
     * intensities / 300 (three slots at 100 = 1.0), capped at 1. {@code sessions}: the slots that train.
     */
    public record DayPlan(boolean rest, double intensity, int sessions) {}

    /** Monday = 0 … Sunday = 6, the same weekday the calendar shows ({@code CalendarService.getDayOfWeek}). */
    public static int weekday(int calendarDay) {
        return Math.floorMod(calendarDay, 7);
    }

    /** The plan of {@code weekday} in {@code schedule}; an empty schedule means the default one. */
    public static DayPlan dayPlan(List<TrainingSchedule> schedule, int weekday) {
        List<TrainingSchedule> week = schedule == null || schedule.isEmpty() ? DEFAULT_WEEK : schedule;
        double total = 0;
        int sessions = 0;
        for (TrainingSchedule row : week) {
            if (row.getDayOfWeek() != weekday) continue;
            if (trains(row)) {
                sessions++;
                total += Math.max(0, Math.min(100, row.getIntensity()));
            }
        }
        return new DayPlan(sessions == 0, Math.min(1.0, total / 300.0), sessions);
    }

    /** The default schedule's plan for a weekday (AI clubs). */
    public static DayPlan defaultDayPlan(int weekday) {
        return dayPlan(DEFAULT_WEEK, weekday);
    }

    private static boolean trains(TrainingSchedule row) {
        return row.getIntensity() > 0 && !"Rest".equalsIgnoreCase(row.getSessionType());
    }

    /** Base gain of a day without a match: rest-daily on a rest day, daily scaled by intensity otherwise. */
    public static double dayGain(MatchEngineConfig.Fitness cfg, DayPlan plan) {
        if (plan.rest()) return cfg.getRestDaily();
        return cfg.getDaily() * Math.max(0.0, 1.0 - cfg.getIntensityCut() * plan.intensity());
    }

    /** {@code naturalFitnessBase + naturalFitnessRange × nf/20}: 20 → 1.25, 10 → 1.00, 1 → 0.775. */
    public static double naturalFitnessFactor(MatchEngineConfig.Fitness cfg, int naturalFitness) {
        int nf = Math.max(1, Math.min(20, naturalFitness));
        return cfg.getNaturalFitnessBase() + cfg.getNaturalFitnessRange() * nf / 20.0;
    }

    /** Fitness after one day without a match, capped at 100. */
    public static double recover(MatchEngineConfig.Fitness cfg, double fitness, DayPlan plan, int naturalFitness) {
        if (fitness >= 100.0) return fitness;
        return Math.min(100.0, fitness + dayGain(cfg, plan) * naturalFitnessFactor(cfg, naturalFitness));
    }

    // ------------------------------------------------------------------ training load

    /** Sessions actually trained ÷ sessions of the default schedule, capped at 1; nothing scheduled = 1. */
    public static double trainingLoad(int trainedSessions, int defaultSessions) {
        if (defaultSessions <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, trainedSessions / (double) defaultSessions));
    }

    /** The monthly decline chances are multiplied by this: 1 at full load, up to 2 with no training. */
    public static double declineMultiplier(double load) {
        return 2.0 - Math.max(0.0, Math.min(1.0, load));
    }
}
