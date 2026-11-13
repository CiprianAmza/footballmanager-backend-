package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(TrainingCounterService.class)
class TrainingCounterServiceTest {

    private static final long TEAM = 10L;
    private static final long OTHER_TEAM = 20L;
    private static final long PLAYER = 7L;
    private static final int AUGUST = TrainingUpdateSchedule.monthKey(3, 1);
    private static final int NOVEMBER = AUGUST + 3;

    private static final Set<String> ATTACKING = Set.of("Finishing", "Dribbling", "Technique");
    private static final Set<String> RELEVANT = Set.of("Finishing", "Dribbling", "Technique", "Passing", "Pace");

    @Autowired private TrainingCounterService counters;

    private void session(long team, Set<String> trained, int monthKey) {
        counters.recordSession(team, Map.of(PLAYER, trained), Map.of(PLAYER, RELEVANT), monthKey);
    }

    @Test
    @DisplayName("""
            GIVEN a player of a human team
            WHEN he does three attacking sessions and one Passing session
            THEN each trained attribute counts its sessions and the untrained ones stay at 0
            """)
    void everySessionAddsOneToItsAttributes() {
        session(TEAM, ATTACKING, AUGUST);
        session(TEAM, ATTACKING, AUGUST);
        session(TEAM, ATTACKING, AUGUST);
        session(TEAM, Set.of("Passing"), AUGUST);

        Map<String, Integer> result = counters.counters(TEAM, PLAYER);
        assertEquals(3, result.get("Finishing"));
        assertEquals(3, result.get("Technique"));
        assertEquals(1, result.get("Passing"));
        assertEquals(0, result.get("Pace"));
    }

    @Test
    @DisplayName("""
            GIVEN counters gathered during a month
            WHEN the monthly update resets the team
            THEN every counter is 0 again, but the month each attribute was last trained is kept
            """)
    void resetClearsCountsButKeepsLastTrainedMonth() {
        session(TEAM, ATTACKING, AUGUST);
        session(TEAM, Set.of("Passing"), AUGUST + 1);

        counters.resetCounts(TEAM);

        assertTrue(counters.counters(TEAM, PLAYER).values().stream().allMatch(count -> count == 0));
        Map<String, Integer> months = counters.monthsUntrained(TEAM, PLAYER, NOVEMBER);
        assertEquals(3, months.get("Finishing"));
        assertEquals(2, months.get("Passing"));
    }

    @Test
    @DisplayName("""
            GIVEN a player who started training in August and never trained Pace
            WHEN it is November
            THEN Pace counts as untrained for 3 months, measured from his first session here
            """)
    void untrainedClockStartsAtTheFirstSession() {
        session(TEAM, ATTACKING, AUGUST);
        session(TEAM, ATTACKING, NOVEMBER);

        Map<String, Integer> months = counters.monthsUntrained(TEAM, PLAYER, NOVEMBER);
        assertEquals(3, months.get("Pace"));
        assertEquals(0, months.get("Finishing"));
    }

    @Test
    @DisplayName("""
            GIVEN a player with counters at his old team
            WHEN he is read at his new team
            THEN he starts from nothing there: no counters and no untrained history
            """)
    void aTransferStartsFromZero() {
        session(OTHER_TEAM, ATTACKING, AUGUST);
        session(OTHER_TEAM, ATTACKING, AUGUST);

        assertTrue(counters.counters(TEAM, PLAYER).isEmpty());
        assertTrue(counters.monthsUntrained(TEAM, PLAYER, NOVEMBER).isEmpty());

        session(TEAM, Set.of("Passing"), NOVEMBER);
        assertEquals(0, counters.counters(TEAM, PLAYER).get("Finishing"));
        assertEquals(1, counters.counters(TEAM, PLAYER).get("Passing"));
    }
}
