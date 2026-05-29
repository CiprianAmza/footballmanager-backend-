package com.footballmanagergamesimulator.algorithms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class RoundRobinTest {

    private RoundRobin roundRobin;

    @BeforeEach
    void setUp() {
        roundRobin = new RoundRobin();
    }

    @Test
    void testGetSchedule() {
        // Input: List of teams
        List<Long> teams = Arrays.asList(1L, 2L, 3L, 4L);

        // Expected output for 4 teams
        List<List<List<Long>>> expectedSchedule = new ArrayList<>();

        // Round 1
        expectedSchedule.add(List.of(
                List.of(1L, 4L),
                List.of(2L, 3L)
        ));
        // Round 2
        expectedSchedule.add(List.of(
                List.of(1L, 3L),
                List.of(4L, 2L)
        ));
        // Round 3
        expectedSchedule.add(List.of(
                List.of(1L, 2L),
                List.of(3L, 4L)
        ));

        // Second leg (same matches repeated)
        expectedSchedule.addAll(expectedSchedule);

        // Call the method
        List<List<List<Long>>> actualSchedule = roundRobin.getSchedule(teams);

        // Verify the result
        assertEquals(expectedSchedule, actualSchedule);
    }

    /**
     * Odd-sized leagues use the circle method padded with a bye, so for every odd N the
     * double round-robin schedule must:
     *   - span 2*N rounds (a single round-robin needs N rounds: one bye per round),
     *   - have (N-1)/2 matches each round (the byed team sits out),
     *   - never book a team twice in the same round,
     *   - have every unordered pair meet exactly twice (one per leg) — no pair lost.
     * Home/away balancing is applied later by FixtureSchedulingService's reverse flag,
     * so it is intentionally not asserted here.
     */
    @Test
    void doubleRoundRobinIsCompleteForEveryOddTeamCount() {
        for (int n : new int[]{3, 5, 7, 9, 11}) {
            List<Long> teams = new ArrayList<>();
            for (long t = 1; t <= n; t++) teams.add(t);

            List<List<List<Long>>> schedule = roundRobin.getSchedule(teams);

            assertEquals(2 * n, schedule.size(),
                    "N=" + n + ": expected 2*N rounds for odd team count");

            Map<String, Integer> pairCounts = new HashMap<>();
            for (List<List<Long>> round : schedule) {
                assertEquals((n - 1) / 2, round.size(),
                        "N=" + n + ": expected (N-1)/2 matches per round");

                Set<Long> seenThisRound = new HashSet<>();
                for (List<Long> match : round) {
                    long a = match.get(0), b = match.get(1);
                    assertNotEquals(a, b, "N=" + n + ": a team cannot play itself");
                    assertTrue(seenThisRound.add(a), "N=" + n + ": team " + a + " booked twice in one round");
                    assertTrue(seenThisRound.add(b), "N=" + n + ": team " + b + " booked twice in one round");
                    String key = Math.min(a, b) + "-" + Math.max(a, b);
                    pairCounts.merge(key, 1, Integer::sum);
                }
            }

            int expectedPairs = n * (n - 1) / 2;
            assertEquals(expectedPairs, pairCounts.size(),
                    "N=" + n + ": every distinct pair must appear");
            for (Map.Entry<String, Integer> e : pairCounts.entrySet()) {
                assertEquals(2, e.getValue(),
                        "N=" + n + ": pair " + e.getKey() + " should meet exactly twice");
            }
        }
    }

    /**
     * The league fixture engine must adapt to whatever (even) number of teams a
     * competition has. For every even N the double round-robin schedule must:
     *   - span 2*(N-1) rounds, N/2 matches each,
     *   - never book a team twice in the same round,
     *   - have every unordered pair meet exactly twice (one per leg).
     * Home/away balancing is applied later by FixtureSchedulingService's reverse
     * flag, so it is intentionally not asserted here.
     */
    @Test
    void doubleRoundRobinIsCompleteForEveryEvenTeamCount() {
        for (int n : new int[]{4, 6, 8, 10, 12, 16, 20}) {
            List<Long> teams = new ArrayList<>();
            for (long t = 1; t <= n; t++) teams.add(t);

            List<List<List<Long>>> schedule = roundRobin.getSchedule(teams);

            assertEquals(2 * (n - 1), schedule.size(),
                    "N=" + n + ": expected 2*(N-1) rounds");

            Map<String, Integer> pairCounts = new HashMap<>();
            for (List<List<Long>> round : schedule) {
                assertEquals(n / 2, round.size(), "N=" + n + ": expected N/2 matches per round");

                Set<Long> seenThisRound = new HashSet<>();
                for (List<Long> match : round) {
                    long a = match.get(0), b = match.get(1);
                    assertNotEquals(a, b, "N=" + n + ": a team cannot play itself");
                    assertTrue(seenThisRound.add(a), "N=" + n + ": team " + a + " booked twice in one round");
                    assertTrue(seenThisRound.add(b), "N=" + n + ": team " + b + " booked twice in one round");
                    String key = Math.min(a, b) + "-" + Math.max(a, b);
                    pairCounts.merge(key, 1, Integer::sum);
                }
            }

            int expectedPairs = n * (n - 1) / 2;
            assertEquals(expectedPairs, pairCounts.size(),
                    "N=" + n + ": every distinct pair must appear");
            for (Map.Entry<String, Integer> e : pairCounts.entrySet()) {
                assertEquals(2, e.getValue(),
                        "N=" + n + ": pair " + e.getKey() + " should meet exactly twice");
            }
        }
    }

    @Test
    void testSwapList() {
        // Input: List of teams
        List<Long> teams = new ArrayList<>(Arrays.asList(1L, 2L, 3L, 4L));

        // Call the swapList method
        roundRobin.swapList(teams); // Internally calls swapList

        // Verify the modified list after the first swap
        List<Long> expectedTeamsAfterSwap = Arrays.asList(1L, 4L, 2L, 3L);
        assertEquals(expectedTeamsAfterSwap, teams);
    }
}
