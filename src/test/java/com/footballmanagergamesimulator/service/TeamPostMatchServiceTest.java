package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link TeamPostMatchService}. Covers the parts that
 * don't touch the DB — {@code calculateScores}, {@code poissonGoals}, and
 * {@code calculateMoraleChangeForTeamDifference} — without standing up
 * Spring. The DB-touching methods (updateTeam, updatePlayersMorale, etc.)
 * are exercised through MatchdayInvariantsTest's golden-path coverage.
 */
class TeamPostMatchServiceTest {

    private TeamPostMatchService service;

    @BeforeEach
    void setUp() {
        service = new TeamPostMatchService();
    }

    // ---------------- poissonGoals ----------------

    @Test
    void poissonGoals_neverExceedsCap_evenWithLargeExpectedGoals() {
        Random random = new Random(0);
        for (int i = 0; i < 1000; i++) {
            int goals = service.poissonGoals(random, 50.0);
            assertTrue(goals >= 0 && goals <= 7,
                    "poissonGoals should be clamped to [0, 7], got " + goals);
        }
    }

    @Test
    void poissonGoals_meanRoughlyMatchesExpectation_forNormalExpectations() {
        // For expected=1.5, average over many samples should land in
        // [1.2, 1.8] (Poisson with cap at 7 + sampling noise).
        Random random = new Random(42);
        int trials = 5000;
        long total = 0;
        for (int i = 0; i < trials; i++) {
            total += service.poissonGoals(random, 1.5);
        }
        double mean = total / (double) trials;
        assertTrue(mean > 1.2 && mean < 1.8,
                "mean should be roughly 1.5, got " + mean);
    }

    @Test
    void poissonGoals_zeroExpected_returnsZero() {
        Random random = new Random(0);
        // expected=0 → L=1, and p*=random.nextDouble() ∈ (0,1) → first
        // iteration always exits with k=1, return = 0.
        for (int i = 0; i < 100; i++) {
            assertEquals(0, service.poissonGoals(random, 0.0));
        }
    }

    // ---------------- calculateScores ----------------

    @Test
    void calculateScores_zeroPower_returnsOneOne() {
        List<Integer> scores = service.calculateScores(0, 0);
        assertEquals(2, scores.size());
        assertEquals(1, scores.get(0));
        assertEquals(1, scores.get(1));
    }

    @Test
    void calculateScores_strongerTeamScoresMoreOnAverage() {
        // Power 200 vs 100 — over many samples the stronger team should
        // average more goals.
        int trials = 1000;
        long totalStrong = 0;
        long totalWeak = 0;
        for (int i = 0; i < trials; i++) {
            List<Integer> scores = service.calculateScores(200, 100);
            totalStrong += scores.get(0);
            totalWeak += scores.get(1);
        }
        assertTrue(totalStrong > totalWeak,
                "stronger team should average more goals, got strong=" + totalStrong + " weak=" + totalWeak);
    }

    @Test
    void calculateScores_alwaysReturnsTwoNonNegativeScores() {
        for (int i = 0; i < 200; i++) {
            List<Integer> scores = service.calculateScores(50 + i, 100 - i);
            assertEquals(2, scores.size());
            assertTrue(scores.get(0) >= 0);
            assertTrue(scores.get(1) >= 0);
            assertTrue(scores.get(0) <= 7);
            assertTrue(scores.get(1) <= 7);
        }
    }

    // ---------------- calculateMoraleChangeForTeamDifference ----------------

    @Test
    void moraleChange_winningAgainstStrongerTeam_isLargePositive() {
        // teamPowerDifference -700 (we were big underdog) + Win → big morale boost.
        // Range from method: random.nextDouble(5, 10).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("W", -700);
            assertTrue(delta >= 5 && delta < 10,
                    "underdog win morale should be 5..10, got " + delta);
        }
    }

    @Test
    void moraleChange_winningAsHugeFavourite_isSmallPositive() {
        // teamPowerDifference +700 + Win → small morale (expected was a win anyway).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("W", 700);
            assertTrue(delta >= 0 && delta < 1,
                    "favourite win morale should be 0..1, got " + delta);
        }
    }

    @Test
    void moraleChange_losingAsFavourite_isLargeNegative() {
        // teamPowerDifference +700 + Loss → big morale hit.
        // Range: random.nextDouble(-15, -5).
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("L", 700);
            assertTrue(delta >= -15 && delta < -5,
                    "favourite loss morale should be -15..-5, got " + delta);
        }
    }

    @Test
    void moraleChange_drawAgainstStrongerTeam_isPositive() {
        // Underdog drawing the favourite should feel like a small win.
        for (int i = 0; i < 50; i++) {
            double delta = service.calculateMoraleChangeForTeamDifference("D", -700);
            assertTrue(delta >= 3 && delta < 7,
                    "underdog draw morale should be 3..7, got " + delta);
        }
    }
}
