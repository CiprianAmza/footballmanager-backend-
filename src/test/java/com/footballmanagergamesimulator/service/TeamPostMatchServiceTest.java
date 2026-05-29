package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link TeamPostMatchService}. Covers
 * {@code calculateMoraleChangeForTeamDifference} without standing up Spring.
 * (Score generation moved entirely to {@link MatchSimulationService}; its
 * contract is covered by {@code MatchSimulationServiceTest}.) The DB-touching
 * methods (updateTeam, updatePlayersMorale, etc.) are exercised through
 * MatchdayInvariantsIT's golden-path coverage.
 */
class TeamPostMatchServiceTest {

    private TeamPostMatchService service;

    @BeforeEach
    void setUp() {
        service = new TeamPostMatchService();
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
