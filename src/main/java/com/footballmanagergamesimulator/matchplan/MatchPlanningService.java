package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds the canonical {@link MatchPlan} from an already-decided scoreline. This
 * is the ONLY place goal minutes and types are chosen; both the live and the
 * instant executor consume the resulting plan, so their timelines are identical.
 * Scorers are deliberately NOT chosen here — they are resolved at execution time
 * from the players on the pitch (see {@link ContributionResolver}).
 *
 * <p>Extra-time goals get minutes in 91..120 and phase {@code EXTRA_TIME}; the
 * shootout is carried on the plan but produces no goal slots (it is not a goal).
 * The latest goals of a period may then be moved into its stoppage time (minute 45,
 * 90, 105 or 120 plus {@code addedTime}).
 */
@Service
public class MatchPlanningService {

    /** Bumped when the planning/resolution logic changes, so stale plans can be
     *  detected/regenerated. Stored on every plan. */
    public static final String ALGORITHM_VERSION = "matchplan-2";

    /** Seeds the stoppage-time RNG apart from the goal RNG: a seed still draws the same goal
     *  minutes, sides and types; stoppage time then only moves some of the latest goals. */
    private static final long STOPPAGE_SALT = 0x5DEECE66DL;

    /** A period of play whose last minute can run into stoppage time. */
    private record Period(GoalPhase phase, int firstMinute, int lastMinute, int maxStoppage) {
        boolean contains(GoalSlot slot) {
            return slot.getPhase() == phase && slot.getMinute() >= firstMinute && slot.getMinute() <= lastMinute;
        }
    }

    /** Up to 5 added minutes per half, the range the watched match uses; up to 2 per extra-time half. */
    private static final List<Period> PERIODS = List.of(
            new Period(GoalPhase.REGULAR_TIME, 1, 45, 5),
            new Period(GoalPhase.REGULAR_TIME, 46, 90, 5),
            new Period(GoalPhase.EXTRA_TIME, 91, 105, 2),
            new Period(GoalPhase.EXTRA_TIME, 106, 120, 2));

    private final MatchEngineConfig engineConfig;

    public MatchPlanningService(MatchEngineConfig engineConfig) {
        this.engineConfig = engineConfig;
    }

    /** League/single-match plan (no extra time, no shootout). */
    public MatchPlan plan(String fixtureKey, long seed, long homeTeamId, long awayTeamId,
                          int homeScore90, int awayScore90) {
        return plan(fixtureKey, seed, homeTeamId, awayTeamId,
                homeScore90, awayScore90, -1, -1, -1, -1);
    }

    /**
     * Full plan including optional extra time and shootout. Pass {@code -1} for
     * the ET / shootout pairs when they were not played.
     */
    public MatchPlan plan(String fixtureKey, long seed, long homeTeamId, long awayTeamId,
                          int homeScore90, int awayScore90,
                          int homeScoreET, int awayScoreET,
                          int homeShootout, int awayShootout) {

        Random rng = new Random(seed);
        List<GoalSlot> slots = new ArrayList<>();

        MatchEngineConfig.Events ev = engineConfig.getEvents();
        addSlots(slots, homeTeamId, homeScore90, GoalPhase.REGULAR_TIME,
                ev.getGoalMinuteMin(), ev.getGoalMinuteMax(), rng);
        addSlots(slots, awayTeamId, awayScore90, GoalPhase.REGULAR_TIME,
                ev.getGoalMinuteMin(), ev.getGoalMinuteMax(), rng);

        if (homeScoreET >= 0 && awayScoreET >= 0) {
            addSlots(slots, homeTeamId, homeScoreET, GoalPhase.EXTRA_TIME, 91, 121, rng);
            addSlots(slots, awayTeamId, awayScoreET, GoalPhase.EXTRA_TIME, 91, 121, rng);
        }

        slots.sort((a, b) -> Integer.compare(a.getMinute(), b.getMinute()));
        assignStoppageTime(slots, new Random(seed ^ STOPPAGE_SALT));
        // Canonical, stable slot index — also seeds each slot's resolution RNG.
        for (int i = 0; i < slots.size(); i++) slots.get(i).setSlotIndex(i);

        return new MatchPlan(fixtureKey, seed, ALGORITHM_VERSION, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET,
                homeShootout, awayShootout, slots);
    }

    /** Generate ONLY the extra-time goal slots (minutes 91-120), for a live knockout whose
     *  ET is decided at commit and appended to the already-played regular-time plan. Uses a
     *  dedicated, seed-derived RNG so the ET minutes/types are deterministic and stable
     *  across a refresh, independent of the regular-time generation. */
    public List<GoalSlot> extraTimeSlots(long seed, long homeTeamId, long awayTeamId,
                                         int homeScoreET, int awayScoreET) {
        Random rng = new Random(seed * 7_777_777L + 91);
        List<GoalSlot> slots = new ArrayList<>();
        addSlots(slots, homeTeamId, Math.max(0, homeScoreET), GoalPhase.EXTRA_TIME, 91, 121, rng);
        addSlots(slots, awayTeamId, Math.max(0, awayScoreET), GoalPhase.EXTRA_TIME, 91, 121, rng);
        slots.sort((a, b) -> Integer.compare(a.getMinute(), b.getMinute()));
        assignStoppageTime(slots, new Random((seed * 7_777_777L + 91) ^ STOPPAGE_SALT));
        return slots;
    }

    /**
     * Gives each period a stoppage time of 0..max minutes and moves its latest goals into it.
     * A goal falls in stoppage with the stoppage's share of the period's length; going from
     * the last goal backwards, the first one that stays put ends the run. Only the latest
     * goals move and their added minutes are sorted, so the slots keep their order (and the
     * slot indexes assigned afterwards). {@code slots} must be sorted by minute.
     */
    static void assignStoppageTime(List<GoalSlot> slots, Random rng) {
        for (Period period : PERIODS) {
            int stoppage = rng.nextInt(period.maxStoppage() + 1);
            List<GoalSlot> inPeriod = slots.stream().filter(period::contains).toList();
            if (stoppage == 0 || inPeriod.isEmpty()) continue;
            int length = period.lastMinute() - period.firstMinute() + 1;
            double stoppageShare = stoppage / (double) (length + stoppage);
            int moved = 0;
            while (moved < inPeriod.size() && rng.nextDouble() < stoppageShare) moved++;
            if (moved == 0) continue;
            int[] addedTimes = new int[moved];
            for (int i = 0; i < moved; i++) addedTimes[i] = 1 + rng.nextInt(stoppage);
            java.util.Arrays.sort(addedTimes);
            int first = inPeriod.size() - moved;
            for (int i = 0; i < moved; i++) {
                inPeriod.get(first + i).moveToStoppage(period.lastMinute(), addedTimes[i]);
            }
        }
    }

    private void addSlots(List<GoalSlot> slots, long teamId, int goals, GoalPhase phase,
                          int minuteMin, int minuteMaxExclusive, Random rng) {
        for (int i = 0; i < goals; i++) {
            int minute = rng.nextInt(minuteMin, minuteMaxExclusive);
            slots.add(new GoalSlot(teamId, minute, phase, pickGoalType(rng)));
        }
    }

    /** Weighted goal type: open play dominant, headers common, set pieces rare. */
    private String pickGoalType(Random rng) {
        double roll = rng.nextDouble();
        MatchEngineConfig.Live lv = engineConfig.getLive();
        if (roll < lv.getPenaltyShare() * 0.5) return "PENALTY";      // penalties rare
        if (roll < lv.getPenaltyShare() * 0.5 + 0.06) return "FREE_KICK";
        if (roll < lv.getPenaltyShare() * 0.5 + 0.06 + 0.20) return "HEADER";
        return "OPEN_PLAY";
    }
}
