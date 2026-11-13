package com.footballmanagergamesimulator.service.odds;

import com.footballmanagergamesimulator.service.odds.LeagueOddsSimulation.Fixture;
import com.footballmanagergamesimulator.service.odds.LeagueOddsSimulation.Odds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeagueOddsSimulationTest {

    @Test
    @DisplayName("""
            GIVEN a leader 10 points clear with one match left (3 points at most for anyone)
            WHEN the season is played out
            THEN the leader wins the title every time and finishes first on average
            """)
    void aLeadThatCannotBeCaughtIsATitle() {
        List<Odds> odds = LeagueOddsSimulation.simulate(new long[]{1, 2, 3, 4}, new int[]{30, 20, 18, 10},
                new int[]{10, 3, 0, -13}, List.of(new Fixture(1, 2, 0.4, 0.3), new Fixture(0, 3, 0.5, 0.3)),
                0, null, 2_000, 7);

        assertEquals(1.0, odds.get(0).title());
        assertEquals(1.0, odds.get(0).averagePosition());
        assertEquals(0.0, odds.get(1).title());
        assertEquals(3.0, odds.stream().mapToDouble(Odds::top3).sum(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN two teams level on points and goal difference, meeting once on a coin-flip (40% / 20% / 40%)
            WHEN the season is played out 10,000 times
            THEN each takes the title about half the time (a level finish goes to chance)
            """)
    void evenTeamsShareTheTitle() {
        List<Odds> odds = LeagueOddsSimulation.simulate(new long[]{1, 2}, new int[]{40, 40}, new int[]{5, 5},
                List.of(new Fixture(0, 1, 0.4, 0.2)), 0, null, 10_000, 11);

        assertEquals(0.5, odds.get(0).title(), 0.02);
        assertEquals(1.0, odds.get(0).title() + odds.get(1).title(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a 6-team league with 2 League of Champions places and relegation from 5th, level at the start,
             where team 1 wins 90% of its matches
            WHEN the season is played out
            THEN team 1 is the clear favourite; the chances add up (1 champion, 3 in the top 3, 2 in the League of
             Champions, 2 relegated) and every average finish lies between 1st and 6th
            """)
    void chancesAddUpAndFollowStrength() {
        long[] teams = {1, 2, 3, 4, 5, 6};
        List<Fixture> fixtures = new java.util.ArrayList<>();
        for (int home = 0; home < 6; home++) {
            for (int away = 0; away < 6; away++) {
                if (home == away) continue;
                double homeWin = home == 0 ? 0.9 : away == 0 ? 0.05 : 0.45;
                double draw = home == 0 || away == 0 ? 0.05 : 0.27;
                fixtures.add(new Fixture(home, away, homeWin, draw));
            }
        }
        List<Odds> odds = LeagueOddsSimulation.simulate(teams, new int[6], new int[6], fixtures, 2, 5, 5_000, 3);

        assertTrue(odds.get(0).title() > 0.9, "favourite " + odds.get(0).title());
        assertEquals(1.0, odds.stream().mapToDouble(Odds::title).sum(), 1e-9);
        assertEquals(3.0, odds.stream().mapToDouble(Odds::top3).sum(), 1e-9);
        assertEquals(2.0, odds.stream().mapToDouble(Odds::champions).sum(), 1e-9);
        assertEquals(2.0, odds.stream().mapToDouble(Odds::relegation).sum(), 1e-9);
        assertEquals(0.0, odds.get(0).relegation(), 0.01);
        assertTrue(odds.stream().allMatch(o -> o.averagePosition() >= 1 && o.averagePosition() <= 6));
    }

    @Test
    @DisplayName("""
            GIVEN a 34% chance, a certainty and no chance at all
            WHEN they are turned into decimal odds
            THEN 2.94, 1.00 and none (1 ÷ chance, no bookmaker margin)
            """)
    void oddsAreOneOverTheChance() {
        assertEquals(2.94, LeagueOddsSimulation.decimalOdds(0.34));
        assertEquals(1.0, LeagueOddsSimulation.decimalOdds(1.0));
        assertNull(LeagueOddsSimulation.decimalOdds(0.0));
    }
}
