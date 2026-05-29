package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link MatchSimulationService#calculateScores} — the single,
 * config-driven scorer the whole game now uses (production routed off the old
 * hardcoded TeamPostMatchService copy). Wires a plain {@link MatchEngineConfig}
 * (defaults: exponent 2.0, 3.0 expected goals, cap 7) without standing up Spring.
 */
class MatchSimulationServiceTest {

    private MatchSimulationService service;

    @BeforeEach
    void setUp() {
        service = new MatchSimulationService();
        service.engineConfig = new MatchEngineConfig();
        service.setRandomForTesting(new Random(20260528L));
    }

    @Test
    void calculateScores_zeroPower_returnsOneOne() {
        List<Integer> scores = service.calculateScores(0, 0);
        assertEquals(2, scores.size());
        assertEquals(1, scores.get(0));
        assertEquals(1, scores.get(1));
    }

    @Test
    void calculateScores_alwaysReturnsTwoScoresWithinCap() {
        int cap = new MatchEngineConfig().getPower().getMaxGoalsPerTeam();
        for (int i = 0; i < 500; i++) {
            List<Integer> scores = service.calculateScores(50 + i, 100 - (i % 90));
            assertEquals(2, scores.size());
            assertTrue(scores.get(0) >= 0 && scores.get(0) <= cap, "home score in [0," + cap + "]");
            assertTrue(scores.get(1) >= 0 && scores.get(1) <= cap, "away score in [0," + cap + "]");
        }
    }

    @Test
    void calculateScores_strongerTeamScoresMoreOnAverage() {
        int trials = 2000;
        long totalStrong = 0, totalWeak = 0;
        for (int i = 0; i < trials; i++) {
            List<Integer> scores = service.calculateScores(8000, 4000);
            totalStrong += scores.get(0);
            totalWeak += scores.get(1);
        }
        assertTrue(totalStrong > totalWeak,
                "stronger team should average more goals, got strong=" + totalStrong + " weak=" + totalWeak);
    }

    @Test
    void calculateScores_extraTimeOverload_producesFewerGoalsThanFullMatch() {
        int trials = 3000;
        long fullGoals = 0, etGoals = 0;
        for (int i = 0; i < trials; i++) {
            List<Integer> full = service.calculateScores(6000, 6000);
            fullGoals += full.get(0) + full.get(1);
        }
        for (int i = 0; i < trials; i++) {
            List<Integer> et = service.calculateScores(6000, 6000, 1.0); // 30-min mini-match
            etGoals += et.get(0) + et.get(1);
        }
        assertTrue(etGoals < fullGoals,
                "extra-time mini-match (1.0 goals) should yield fewer goals than a full match (3.0); "
                        + "et=" + etGoals + " full=" + fullGoals);
    }
}
