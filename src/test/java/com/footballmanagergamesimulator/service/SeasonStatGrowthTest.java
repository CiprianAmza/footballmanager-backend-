package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonStatGrowthTest {

    /** Players 1..n with goals n+1-id (player 1 leads). */
    private static Map<Long, Double> goals(int players) {
        Map<Long, Double> goals = new HashMap<>();
        for (long id = 1; id <= players; id++) goals.put(id, (double) (players + 1 - id));
        return goals;
    }

    private static Map<Long, Double> ratings(double... byId) {
        Map<Long, Double> ratings = new HashMap<>();
        for (int i = 0; i < byId.length; i++) ratings.put(i + 1L, byId[i]);
        return ratings;
    }

    @Test
    @DisplayName("""
            GIVEN a league's top 10 scorers, ranked 1..10, rated 50, 100, 90, 80, 70, 60, 100, 90, 80, 70
            WHEN the season's growth is computed
            THEN 1st grows 15% of the average of places 2-10, 2nd 10% of places 3-10, 3rd 5% of places 4-10
            """)
    void topThreeGrowByTheLevelOfThePlayersBelow() {
        Map<Long, Double> ratings = ratings(50, 100, 90, 80, 70, 60, 100, 90, 80, 70);

        List<SeasonStatGrowth.Award> awards = SeasonStatGrowth.awards(goals(10), ratings);

        assertEquals(3, awards.size());
        assertEquals(1L, awards.get(0).playerId());
        assertEquals(0.15 * (740.0 / 9), awards.get(0).ratingGrowth(), 1e-9);
        assertEquals(2L, awards.get(1).playerId());
        assertEquals(0.10 * (640.0 / 8), awards.get(1).ratingGrowth(), 1e-9);
        assertEquals(3L, awards.get(2).playerId());
        assertEquals(0.05 * (550.0 / 7), awards.get(2).ratingGrowth(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN the leader is rated 300, far above everyone below him (all 100)
            WHEN the season's growth is computed
            THEN he still grows 15: the growth depends on the players he beat, not on his own rating
            """)
    void growthDoesNotDependOnOwnRating() {
        Map<Long, Double> ratings = ratings(300, 100, 100, 100, 100, 100, 100, 100, 100, 100);

        assertEquals(15.0, SeasonStatGrowth.awards(goals(10), ratings).get(0).ratingGrowth(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN 14 players with goals, places 11-14 rated 1
            WHEN the season's growth is computed
            THEN places 11-14 are not part of the reference: only the top 10 counts
            """)
    void onlyTheTopTenIsTheReference() {
        double[] byId = new double[14];
        java.util.Arrays.fill(byId, 100);
        for (int i = 10; i < 14; i++) byId[i] = 1;

        assertEquals(15.0, SeasonStatGrowth.awards(goals(14), ratings(byId)).get(0).ratingGrowth(), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN only two players scored, and a third has 0 goals
            WHEN the season's growth is computed
            THEN only the 1st grows (the 2nd has nobody below him), and 0 goals never count
            """)
    void smallTopsAndZeroStats() {
        Map<Long, Double> goals = new HashMap<>(Map.of(1L, 5.0, 2L, 3.0, 3L, 0.0));

        List<SeasonStatGrowth.Award> awards = SeasonStatGrowth.awards(goals, ratings(100, 80, 60));

        assertEquals(1, awards.size());
        assertEquals(12.0, awards.get(0).ratingGrowth(), 1e-9);
        assertTrue(SeasonStatGrowth.awards(Map.of(1L, 0.0), ratings(100)).isEmpty());
    }
}
