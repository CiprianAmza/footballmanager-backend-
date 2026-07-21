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
 */
@Service
public class MatchPlanningService {

    /** Bumped when the planning/resolution logic changes, so stale plans can be
     *  detected/regenerated. Stored on every plan. */
    public static final String ALGORITHM_VERSION = "matchplan-1";

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
        // Canonical, stable slot index — also seeds each slot's resolution RNG.
        for (int i = 0; i < slots.size(); i++) slots.get(i).setSlotIndex(i);

        return new MatchPlan(fixtureKey, seed, ALGORITHM_VERSION, homeTeamId, awayTeamId,
                homeScore90, awayScore90, homeScoreET, awayScoreET,
                homeShootout, awayShootout, slots);
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
