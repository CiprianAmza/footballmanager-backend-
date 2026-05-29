package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pure layered-halving slot allocation used to fill a configurable
 * European competition from the first leagues (coefficient-ranked).
 */
class EuropeanCoefficientServiceAllocationTest {

    private static List<Long> leagues(int k) {
        return java.util.stream.LongStream.rangeClosed(1, k).boxed().toList();
    }

    @Test
    void fortyTeamsTenLeagues_matchesWorkedExample() {
        // Layer1 all=1, top5+1, top3+1, top2+1, top1+1 (=21), then full sweeps
        // (L1..L10 then L1..L9) for the remaining 19.
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(10), 40, Map.of(), Map.of());

        assertEquals(40, a.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(7, 6, 5, 4, 4, 3, 3, 3, 3, 2),
                List.copyOf(a.values()));
    }

    @Test
    void allocationIsMonotonicNonIncreasingByRank() {
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(7), 25, Map.of(), Map.of());
        assertEquals(25, a.values().stream().mapToInt(Integer::intValue).sum());
        List<Integer> counts = List.copyOf(a.values());
        for (int i = 1; i < counts.size(); i++) {
            assertTrue(counts.get(i) <= counts.get(i - 1),
                    "rank " + i + " (" + counts.get(i) + ") must not exceed rank " + (i - 1)
                            + " (" + counts.get(i - 1) + ")");
        }
    }

    @Test
    void everyLeagueGetsOneWhenTotalEqualsLeagueCount() {
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(8), 8, Map.of(), Map.of());
        assertTrue(a.values().stream().allMatch(v -> v == 1));
    }

    @Test
    void manualOverrideIsHonoredAndConsumesBudget() {
        // League 2 forced to 10; the remaining 10 spread over the other five.
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(6), 20, Map.of(2L, 10), Map.of());
        assertEquals(10, a.get(2L));
        assertEquals(20, a.values().stream().mapToInt(Integer::intValue).sum());
        // The auto leagues still follow layered halving among themselves.
        assertTrue(a.get(1L) >= a.get(3L));
    }

    @Test
    void respectsPerLeagueCaps() {
        // League 1 capped at 2 teams; the rest absorbs the overflow top-down.
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(3), 10, Map.of(), Map.of(1L, 2, 2L, 100, 3L, 100));
        assertEquals(2, a.get(1L), "capped league must not exceed its size");
        assertEquals(10, a.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void bestEffortWhenAllLeaguesCappedBelowTotal() {
        // Only room for 3 + 3 = 6 teams but 10 requested → allocate what fits.
        LinkedHashMap<Long, Integer> a = EuropeanCoefficientService.layeredAllocation(
                leagues(2), 10, Map.of(), Map.of(1L, 3, 2L, 3));
        assertEquals(6, a.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, a.get(1L));
        assertEquals(3, a.get(2L));
    }
}
