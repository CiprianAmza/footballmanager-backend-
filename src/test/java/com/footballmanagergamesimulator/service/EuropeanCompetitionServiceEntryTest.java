package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the seeded preliminary-entry assignment: which round each European entrant
 * first plays, given a coefficient-seeded field and a target group-stage size.
 * Strongest seeds enter latest (byes); the strongest {@code slots} skip the
 * prelims and enter at the group-draw round.
 */
class EuropeanCompetitionServiceEntryTest {

    private static List<Long> seeds(int n) {
        // id 1 = best seed … id n = worst
        return java.util.stream.LongStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void twentyFourTeams_strongestEightEnterAtGroupDraw() {
        Map<Long, Integer> start = EuropeanCompetitionService.assignEntrantsToPrelimRounds(seeds(24), 16);

        // 24 → eliminate 8 → 16 ⇒ P=1. Weakest 16 play round 0; strongest 8 enter round 1.
        long atRound0 = start.values().stream().filter(r -> r == 0).count();
        long atRound1 = start.values().stream().filter(r -> r == 1).count();
        assertEquals(16, atRound0);
        assertEquals(8, atRound1);

        // Strongest 8 (ids 1..8) bye the prelim and enter at the group-draw round (P=1).
        for (long id = 1; id <= 8; id++) assertEquals(1, start.get(id), "seed " + id);
        for (long id = 9; id <= 24; id++) assertEquals(0, start.get(id), "seed " + id);
    }

    @Test
    void fortyTeams_everyoneStartsRoundZero() {
        // 40 → eliminate 20 → 20 → eliminate 4 → 16. The whole field plays round 0;
        // byes only happen among survivors in round 1, so no team enters later.
        Map<Long, Integer> start = EuropeanCompetitionService.assignEntrantsToPrelimRounds(seeds(40), 16);
        assertEquals(40, start.size());
        assertTrue(start.values().stream().allMatch(r -> r == 0));
    }

    @Test
    void exactlyGroupSize_noPreliminaryPlay() {
        // totalTeams == slots ⇒ no trim rounds; all enter at the group draw (round 0).
        Map<Long, Integer> start = EuropeanCompetitionService.assignEntrantsToPrelimRounds(seeds(16), 16);
        assertEquals(16, start.size());
        assertTrue(start.values().stream().allMatch(r -> r == 0));
    }
}
