package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchPlanStoppageTimeTest {

    private final MatchEngineConfig config = new MatchEngineConfig();
    private final MatchPlanningService planning = new MatchPlanningService(config);

    /** Random whose nextInt(bound)/nextDouble() answers are scripted, in call order. */
    private static final class ScriptedRandom extends Random {
        private final Deque<Integer> ints;
        private final Deque<Double> doubles;

        ScriptedRandom(List<Integer> ints, List<Double> doubles) {
            this.ints = new ArrayDeque<>(ints);
            this.doubles = new ArrayDeque<>(doubles);
        }

        @Override
        public int nextInt(int bound) {
            return ints.removeFirst();
        }

        @Override
        public double nextDouble() {
            return doubles.removeFirst();
        }
    }

    private static GoalSlot slot(long team, int minute) {
        return new GoalSlot(team, minute, GoalPhase.REGULAR_TIME, "OPEN_PLAY");
    }

    @Test
    void latestGoalsOfAHalfMoveIntoItsStoppageTimeInOrder() {
        List<GoalSlot> slots = new ArrayList<>(List.of(
                slot(10, 10), slot(20, 30), slot(10, 44), slot(20, 60), slot(10, 89)));
        // First half: 3 added minutes; 44' and 30' fall in it, 10' does not; their added
        // minutes are drawn 3 then 1 (nextInt(3) = 2, 0). Second half and both extra-time
        // halves get no stoppage time.
        ScriptedRandom rng = new ScriptedRandom(List.of(3, 2, 0, 0, 0, 0), List.of(0.0, 0.0, 0.99));

        MatchPlanningService.assignStoppageTime(slots, rng);

        assertThat(slots).extracting(GoalSlot::getMinute).containsExactly(10, 45, 45, 60, 89);
        assertThat(slots).extracting(GoalSlot::getAddedTime).containsExactly(null, 1, 3, null, null);
        assertThat(slots).extracting(GoalSlot::getTeamId).containsExactly(10L, 20L, 10L, 20L, 10L);
    }

    @Test
    void noStoppageOrNoGoalsLeavesThePeriodAlone() {
        List<GoalSlot> slots = new ArrayList<>(List.of(slot(10, 12), slot(20, 77)));
        ScriptedRandom rng = new ScriptedRandom(List.of(0, 4, 2, 2), List.of(0.99));

        MatchPlanningService.assignStoppageTime(slots, rng);

        assertThat(slots).extracting(GoalSlot::getMinute).containsExactly(12, 77);
        assertThat(slots).extracting(GoalSlot::getAddedTime).containsOnlyNulls();
    }

    @Test
    void plansKeepTheirOrderAndPutStoppageGoalsOnlyAtTheEndOfAPeriod() {
        int stoppageGoals = 0;
        for (long seed = 1; seed <= 400; seed++) {
            MatchPlan plan = planning.plan("fx" + seed, seed, 10L, 20L, 3, 2, 1, 1, 4, 3);
            List<GoalSlot> slots = plan.getGoalSlots();
            assertThat(slots).hasSize(7);
            for (int i = 0; i < slots.size(); i++) {
                GoalSlot slot = slots.get(i);
                assertThat(slot.getSlotIndex()).isEqualTo(i);
                if (i > 0) assertThat(timeline(slots.get(i - 1))).isLessThanOrEqualTo(timeline(slot));
                if (slot.getAddedTime() == null) continue;
                stoppageGoals++;
                if (slot.getPhase() == GoalPhase.REGULAR_TIME) {
                    assertThat(slot.getMinute()).isIn(45, 90);
                    assertThat(slot.getAddedTime()).isBetween(1, 5);
                } else {
                    assertThat(slot.getMinute()).isIn(105, 120);
                    assertThat(slot.getAddedTime()).isBetween(1, 2);
                }
            }
        }
        // About one goal in twenty lands in stoppage time: 2,800 goals give well over 30.
        assertThat(stoppageGoals).isGreaterThan(30);
    }

    @Test
    void sameSeedPlansTheSameStoppageTime() {
        MatchPlan first = planning.plan("fx", 99L, 10L, 20L, 4, 3);
        MatchPlan second = planning.plan("fx", 99L, 10L, 20L, 4, 3);
        assertThat(describe(first.getGoalSlots())).isEqualTo(describe(second.getGoalSlots()));
    }

    @Test
    void appendedExtraTimeGoalsCanRunIntoExtraTimeStoppage() {
        Set<Integer> stoppageMinutes = new java.util.HashSet<>();
        for (long seed = 1; seed <= 400; seed++) {
            List<GoalSlot> slots = planning.extraTimeSlots(seed, 10L, 20L, 2, 1);
            for (int i = 1; i < slots.size(); i++) {
                assertThat(timeline(slots.get(i - 1))).isLessThanOrEqualTo(timeline(slots.get(i)));
            }
            for (GoalSlot slot : slots) {
                assertThat(slot.getPhase()).isEqualTo(GoalPhase.EXTRA_TIME);
                if (slot.getAddedTime() == null) continue;
                assertThat(slot.getAddedTime()).isBetween(1, 2);
                stoppageMinutes.add(slot.getMinute());
            }
        }
        assertThat(stoppageMinutes).isNotEmpty().isSubsetOf(105, 120);
    }

    @Test
    void goalAndAssistEventsCarryTheSlotsAddedTime() {
        ContributionResolver resolver = new ContributionResolver(config);
        InstantMatchExecutor executor = new InstantMatchExecutor(resolver);
        InstantMatchExecutor.MatchContext ctx = new InstantMatchExecutor.MatchContext("CTIM:1", 100L, 1, 5);

        MatchPlan plan = null;
        for (long seed = 1; plan == null; seed++) {
            MatchPlan candidate = planning.plan("fx" + seed, seed, 10L, 20L, 4, 3);
            if (candidate.getGoalSlots().stream().anyMatch(slot -> slot.getAddedTime() != null)) plan = candidate;
        }
        List<MatchEvent> events = executor.execute(plan, xi(100), xi(200), ctx);

        for (GoalSlot slot : plan.getGoalSlots()) {
            List<MatchEvent> slotEvents = events.stream().filter(e -> e.getSlotIndex() == slot.getSlotIndex()).toList();
            assertThat(slotEvents).isNotEmpty();
            for (MatchEvent event : slotEvents) {
                assertThat(event.getMinute()).isEqualTo(slot.getMinute());
                assertThat(event.getAddedTime()).isEqualTo(slot.getAddedTime());
            }
        }
    }

    @Test
    void timelineOrderPutsStoppageMinutesAfterTheirMinute() {
        List<MatchEvent> events = new ArrayList<>(List.of(event(46, null), event(45, 2), event(45, null), event(44, null)));
        events.sort(MatchEvent.TIMELINE_ORDER);
        assertThat(events).extracting(MatchEvent::getMinute).containsExactly(44, 45, 45, 46);
        assertThat(events).extracting(MatchEvent::getAddedTime).containsExactly(null, null, 2, null);
    }

    @Test
    void onlyAnUnresolvedSlotCanMoveIntoStoppage() {
        GoalSlot resolved = slot(10, 40);
        resolved.resolve(1L, null);
        assertThatThrownBy(() -> resolved.moveToStoppage(45, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> slot(10, 40).moveToStoppage(45, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int timeline(GoalSlot slot) {
        return slot.getMinute() * 100 + (slot.getAddedTime() == null ? 0 : slot.getAddedTime());
    }

    private static List<String> describe(List<GoalSlot> slots) {
        return slots.stream().map(slot -> slot.getSlotIndex() + ":" + slot.getTeamId() + ":" + slot.getMinute()
                + "+" + slot.getAddedTime() + ":" + slot.getGoalType()).toList();
    }

    private static MatchEvent event(int minute, Integer addedTime) {
        MatchEvent event = new MatchEvent();
        event.setMinute(minute);
        event.setAddedTime(addedTime);
        return event;
    }

    private static Contributor p(long id, String pos) {
        return new Contributor(id, "P" + id, pos, 15.0, 15, 15, 15, 100.0, false, false);
    }

    private static Lineup xi(long base) {
        return new Lineup(Arrays.asList(
                p(base + 1, "GK"), p(base + 2, "DC"), p(base + 3, "DC"), p(base + 4, "DL"),
                p(base + 5, "MC"), p(base + 6, "MC"), p(base + 7, "AMR"), p(base + 8, "AML"),
                p(base + 9, "ST"), p(base + 10, "ST"), p(base + 11, "DR")), List.of());
    }
}
