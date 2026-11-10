package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CupFinishingPositionsTest {
    @Test
    void teamsEliminatedInTheSameStageShareTheirFinishingPlace() {
        Map<Long, Long> furthest = new HashMap<>();
        for (long team = 1; team <= 16; team++) furthest.put(team, 7L); // group stage
        for (long team = 1; team <= 8; team++) furthest.put(team, 8L);  // quarter-finals
        for (long team = 1; team <= 4; team++) furthest.put(team, 9L);  // semi-finals
        furthest.put(1L, 10L); furthest.put(2L, 10L);                 // final

        Map<Long, Integer> places = CupFinishingPositions.calculate(furthest.keySet(), furthest, 1L, 2L);
        assertEquals(1, places.get(1L));
        assertEquals(2, places.get(2L));
        assertEquals(3, places.get(3L));
        assertEquals(3, places.get(4L));
        for (long team = 5; team <= 8; team++) assertEquals(5, places.get(team));
        for (long team = 9; team <= 16; team++) assertEquals(9, places.get(team));
    }

    @Test
    void pendingFinalistsDoNotReceiveAPlaceBeforeTheFinal() {
        Map<Long, Long> furthest = Map.of(1L, 10L, 2L, 10L, 3L, 9L, 4L, 9L);
        Map<Long, Integer> places = CupFinishingPositions.calculate(Set.of(1L, 2L, 3L, 4L),
                furthest, null, null);
        assertFalse(places.containsKey(1L));
        assertFalse(places.containsKey(2L));
        assertEquals(3, places.get(3L));
        assertEquals(3, places.get(4L));
    }
}
