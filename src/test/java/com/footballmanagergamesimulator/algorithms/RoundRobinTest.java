package com.footballmanagergamesimulator.algorithms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test
    void testGetScheduleWithOddNumberOfTeams() {
        // Input: List of teams with an odd number
        List<Long> teams = Arrays.asList(1L, 2L, 3L, 4L, 5L);

        // Call the method
        List<List<List<Long>>> schedule = roundRobin.getSchedule(teams);

        // Verify the number of rounds
        // For 5 teams, each team plays 4 rounds (home and away), so 4 * 2 = 8 total rounds
        assertEquals(8, schedule.size());

        // Verify that each round contains half the number of matches
        for (List<List<Long>> round : schedule) {
            assertEquals(teams.size() / 2, round.size());
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
