package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.MatchStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchStatsServiceTest {

    private MatchStatsService service;

    @BeforeEach
    void setUp() {
        service = new MatchStatsService();
        ReflectionTestUtils.setField(service, "engineConfig", new MatchEngineConfig());
    }

    @Test
    void aGoalCanComeFromOneLowXgShot() {
        MatchStats example = null;
        for (int seed = 0; seed < 20_000 && example == null; seed++) {
            MatchStats candidate = simulate(seed, 1, 0, 1_000, 9_000);
            if (candidate.getHomeShots() == 1 && candidate.getHomeXg() >= 1 && candidate.getHomeXg() <= 20) {
                example = candidate;
            }
        }

        assertNotNull(example, "expected a deterministic one-shot goal worth at most 0.20 xG");
        assertTrue(example.getHomeShotsOnTarget() == 1);
        assertTrue(example.getHomeBigChances() == 0);
    }

    @Test
    void dominantFavoriteCanLoseDespiteAThirtyToOneShotGap() {
        MatchStats example = null;
        for (int seed = 0; seed < 50_000 && example == null; seed++) {
            MatchStats candidate = simulate(seed, 0, 1, 9_000, 1_000);
            if (candidate.getHomeShots() >= 30 && candidate.getAwayShots() == 1
                    && candidate.getHomeXg() > candidate.getAwayXg()) {
                example = candidate;
            }
        }

        assertNotNull(example, "expected a deterministic 30+-to-1 shot upset in the distribution tail");
        assertTrue(example.getAwayShotsOnTarget() == 1);
    }

    @Test
    void sixGoalsCannotRoutinelyComeFromNearZeroXg() {
        int samples = 5_000;
        int nearZeroXgResults = 0;
        int minimumXg = Integer.MAX_VALUE;
        long totalXg = 0;

        service.setRandomForTesting(new Random(20260721L));
        for (int i = 0; i < samples; i++) {
            MatchStats stats = service.generateMatchStats(
                    1, 1, i + 1, 1, 2, 6, 0, 5_000, 5_000, null, null);
            if (stats.getHomeXg() <= 50) nearZeroXgResults++;
            minimumXg = Math.min(minimumXg, stats.getHomeXg());
            totalXg += stats.getHomeXg();
        }

        double averageXg = totalXg / (samples * 100.0);
        assertTrue(nearZeroXgResults == 0,
                "six-goal results must not be paired with near-zero xG in normal simulation volume; count="
                        + nearZeroXgResults + ", minimum=" + minimumXg / 100.0);
        assertTrue(averageXg >= 1.8 && averageXg <= 3.5,
                "six-goal chance lines should remain exceptional but plausible, got average xG " + averageXg);
    }

    @Test
    void shotDistributionHasLowAndHighVolumeMatchesWhileKeepingInvariants() {
        int samples = 4_000;
        int lowVolumeSides = 0;
        int highVolumeSides = 0;
        int lowXgSides = 0;
        int highXgSides = 0;
        long totalShots = 0;
        long totalXg = 0;

        service.setRandomForTesting(new Random(20260720L));
        for (int i = 0; i < samples; i++) {
            MatchStats stats = service.generateMatchStats(
                    1, 1, i + 1, 1, 2, 0, 0, 5_000, 5_000, null, null);
            for (int shots : new int[]{stats.getHomeShots(), stats.getAwayShots()}) {
                if (shots <= 5) lowVolumeSides++;
                if (shots >= 20) highVolumeSides++;
                totalShots += shots;
            }
            for (int xg : new int[]{stats.getHomeXg(), stats.getAwayXg()}) {
                if (xg <= 50) lowXgSides++;
                if (xg >= 250) highXgSides++;
                totalXg += xg;
            }
            assertCoherent(stats.getHomeGoals(), stats.getHomeShots(), stats.getHomeShotsOnTarget(),
                    stats.getHomeBigChances(), stats.getHomeBigChancesMissed(), stats.getHomeXg());
            assertCoherent(stats.getAwayGoals(), stats.getAwayShots(), stats.getAwayShotsOnTarget(),
                    stats.getAwayBigChances(), stats.getAwayBigChancesMissed(), stats.getAwayXg());
        }

        double averageShots = totalShots / (samples * 2.0);
        double averageXg = totalXg / (samples * 2.0 * 100.0);
        assertTrue(lowVolumeSides > 0, "distribution must include teams with five shots or fewer");
        assertTrue(highVolumeSides > 0, "distribution must include teams with twenty shots or more");
        assertTrue(lowXgSides > 0, "distribution must include teams with at most 0.50 xG");
        assertTrue(highXgSides > 0, "distribution must include teams with at least 2.50 xG");
        assertTrue(averageShots >= 10 && averageShots <= 15,
                "average shots per team should remain realistic, got " + averageShots);
        assertTrue(averageXg >= 0.8 && averageXg <= 2.0,
                "average xG per team should remain realistic, got " + averageXg);
    }

    private MatchStats simulate(long seed, int homeGoals, int awayGoals,
                                double homePower, double awayPower) {
        service.setRandomForTesting(new Random(seed));
        return service.generateMatchStats(
                1, 1, 1, 1, 2, homeGoals, awayGoals, homePower, awayPower, null, null);
    }

    private void assertCoherent(int goals, int shots, int shotsOnTarget,
                                int bigChances, int bigChancesMissed, int xg) {
        assertTrue(shots >= goals, "goals cannot exceed shots");
        assertTrue(shotsOnTarget >= goals, "goals cannot exceed shots on target");
        assertTrue(shotsOnTarget <= shots, "shots on target cannot exceed shots");
        assertTrue(bigChancesMissed >= 0 && bigChancesMissed <= bigChances,
                "missed big chances must be a subset of big chances");
        assertTrue(xg >= 0, "xG cannot be negative");
    }
}
