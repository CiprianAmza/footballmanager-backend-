package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureSchedulingServiceTest {

    private final FixtureSchedulingService service = new FixtureSchedulingService();

    @Test
    void leagueMatchdayCountForEvenTeamCounts() {
        // Even N: single round-robin = N-1 rounds, times encounters.
        assertEquals(6, service.leagueMatchdayCount(4, 2));   // 2 * 3
        assertEquals(22, service.leagueMatchdayCount(12, 2)); // 2 * 11
        assertEquals(44, service.leagueMatchdayCount(12, 4)); // 4 * 11
    }

    @Test
    void leagueMatchdayCountForOddTeamCounts() {
        // Odd N: single round-robin = N rounds (one bye per round), times encounters.
        assertEquals(10, service.leagueMatchdayCount(5, 2));  // 2 * 5
        assertEquals(22, service.leagueMatchdayCount(11, 2)); // 2 * 11
    }

    @Test
    void leagueMatchdayCountForOddEncounters() {
        assertEquals(3, service.leagueMatchdayCount(4, 1));   // 1 * 3 (even N)
        assertEquals(7, service.leagueMatchdayCount(7, 1));   // 1 * 7 (odd N)
        assertEquals(15, service.leagueMatchdayCount(5, 3));  // 3 * 5 (odd N, odd encounters)
    }
}
