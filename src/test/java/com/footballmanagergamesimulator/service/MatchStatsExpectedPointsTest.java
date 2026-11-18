package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.match.ExpectedPoints;
import com.footballmanagergamesimulator.model.MatchStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchStatsExpectedPointsTest {

    @Test
    void missingXptsIsFilledFromXgWithPoisson() {
        MatchStats stats = new MatchStats();
        stats.setHomeXg(173);   // 2D engine style: only xG is known
        stats.setAwayXg(51);
        MatchStatsService.fillMissingExpectedPoints(stats);
        ExpectedPoints.Outcome expected = ExpectedPoints.poisson(1.73, 0.51);
        assertEquals((int) Math.round(expected.homeXpts() * 100), stats.getHomeXpts());
        assertEquals((int) Math.round(expected.awayXpts() * 100), stats.getAwayXpts());
        assertTrue(stats.getHomeXpts() > stats.getAwayXpts());
        assertTrue(stats.getHomeXpts() <= 300 && stats.getAwayXpts() >= 0);
    }

    @Test
    void engineXptsIsKept() {
        MatchStats stats = new MatchStats();
        stats.setHomeXg(150);
        stats.setAwayXg(90);
        stats.setHomeXpts(212);
        stats.setAwayXpts(61);
        MatchStatsService.fillMissingExpectedPoints(stats);
        assertEquals(212, stats.getHomeXpts());
        assertEquals(61, stats.getAwayXpts());
    }
}
