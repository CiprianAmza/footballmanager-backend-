package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchEffectEvent;
import com.footballmanagergamesimulator.compartment.effects.CanonicalMatchEffectsInput;
import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchStatsServiceTest {

    private MatchStatsService service;

    @BeforeEach
    void setUp() {
        service = new MatchStatsService();
        ReflectionTestUtils.setField(service, "engineConfig", new MatchEngineConfig());
        MatchStatsRepository repository = mock(MatchStatsRepository.class);
        when(repository.save(any(MatchStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(service, "matchStatsRepository", repository);
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

    @Test
    void canonicalProjectionUsesPersistentXgAndIsRepeatable() {
        MatchScoringDecision decision = decision(2, 1, ScoreEngineKind.COMPARTMENT_V1, 1.234, 0.876);
        CanonicalMatchEffectsInput input = new CanonicalMatchEffectsInput(decision,
                KnockoutPlanSplit.regularOnly(2, 1), 10, 20,
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201), goal(2, 30, 10, 102)));

        MatchStats first = service.generateAndSaveCanonicalMatchStats(input, 1, 1, 1);
        MatchEngineConfig changedConfig = new MatchEngineConfig();
        changedConfig.getStats().setShotsBase(100.0);
        changedConfig.getStats().setPossessionBase(25.0);
        ReflectionTestUtils.setField(service, "engineConfig", changedConfig);
        MatchStats second = service.generateAndSaveCanonicalMatchStats(input, 1, 1, 1);

        assertEquals(first, second);
        assertEquals(2, first.getHomeGoals());
        assertEquals(1, first.getAwayGoals());
        assertEquals(123, first.getHomeXg());
        assertEquals(88, first.getAwayXg());
        assertTrue(first.getHomeShots() >= first.getHomeShotsOnTarget());
        assertTrue(first.getHomeShotsOnTarget() >= first.getHomeGoals());
        assertTrue(first.getAwayShots() >= first.getAwayShotsOnTarget());
        assertTrue(first.getAwayShotsOnTarget() >= first.getAwayGoals());
    }

    @Test
    void canonicalProjectionIncludesExtraTimeButNotShootout() {
        MatchScoringDecision extraTimeDecision = decision(1, 1, ScoreEngineKind.SCALAR_FALLBACK, null, null);
        CanonicalMatchEffectsInput extraTime = new CanonicalMatchEffectsInput(extraTimeDecision,
                KnockoutPlanSplit.knockout(1, 1, 1, 0, null, null), 10, 20,
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201), goal(2, 100, 10, 102)));
        MatchStats extraTimeStats = service.generateAndSaveCanonicalMatchStats(extraTime, 1, 1, 1);
        assertEquals(2, extraTimeStats.getHomeGoals());
        assertEquals(1, extraTimeStats.getAwayGoals());

        MatchScoringDecision shootoutDecision = decision(1, 1, ScoreEngineKind.SCALAR_FALLBACK, null, null);
        CanonicalMatchEffectsInput shootout = new CanonicalMatchEffectsInput(shootoutDecision,
                KnockoutPlanSplit.knockout(1, 1, 0, 0, 5, 4), 10, 20,
                List.of(goal(0, 10, 10, 101), goal(1, 20, 20, 201)));
        MatchStats shootoutStats = service.generateAndSaveCanonicalMatchStats(shootout, 1, 1, 1);
        assertEquals(1, shootoutStats.getHomeGoals());
        assertEquals(1, shootoutStats.getAwayGoals());
        assertTrue(shootoutStats.getHomeShots() >= shootoutStats.getHomeShotsOnTarget());
        assertTrue(shootoutStats.getAwayShots() >= shootoutStats.getAwayShotsOnTarget());
    }

    @Test
    void canonicalFallbackWithoutXgRemainsDeterministicAndValid() {
        MatchScoringDecision decision = decision(0, 0, ScoreEngineKind.SCALAR_FALLBACK, null, null);
        CanonicalMatchEffectsInput input = new CanonicalMatchEffectsInput(decision,
                KnockoutPlanSplit.regularOnly(0, 0), 10, 20, List.of());

        MatchStats first = service.generateAndSaveCanonicalMatchStats(input, 1, 1, 1);
        MatchStats second = service.generateAndSaveCanonicalMatchStats(input, 1, 1, 1);
        assertEquals(first, second);
        assertTrue(first.getHomeXg() >= 0 && first.getAwayXg() >= 0);
    }

    @Test
    void canonicalPowersComeFromTheDecisionAndChangeTheProjection() {
        KnockoutPlanSplit split = KnockoutPlanSplit.regularOnly(0, 0);
        CanonicalMatchEffectsInput favoriteHome = new CanonicalMatchEffectsInput(
                decision(0, 0, ScoreEngineKind.SCALAR_FALLBACK, null, null, 9_000, 1_000),
                split, 10, 20, List.of());
        CanonicalMatchEffectsInput favoriteAway = new CanonicalMatchEffectsInput(
                decision(0, 0, ScoreEngineKind.SCALAR_FALLBACK, null, null, 1_000, 9_000),
                split, 10, 20, List.of());

        MatchStats homeFavorite = service.generateAndSaveCanonicalMatchStats(favoriteHome, 1, 1, 1);
        MatchStats awayFavorite = service.generateAndSaveCanonicalMatchStats(favoriteAway, 1, 1, 1);

        assertTrue(homeFavorite.getHomePossession() != awayFavorite.getHomePossession()
                        || homeFavorite.getHomeShots() != awayFavorite.getHomeShots(),
                "canonical projection must consume decision powers");
    }

    @Test
    void legacyGeneratorStillUsesItsLiveConfigAndRandomSeam() {
        service.setRandomForTesting(new Random(17L));
        MatchStats baseline = service.generateMatchStats(
                1, 1, 1, 1, 2, 0, 0, 5_000, 4_000, null, null);

        MatchEngineConfig changedConfig = new MatchEngineConfig();
        changedConfig.getStats().setShotsBase(30.0);
        ReflectionTestUtils.setField(service, "engineConfig", changedConfig);
        service.setRandomForTesting(new Random(17L));
        MatchStats changed = service.generateMatchStats(
                1, 1, 1, 1, 2, 0, 0, 5_000, 4_000, null, null);

        assertTrue(baseline.getHomeShots() != changed.getHomeShots()
                        || baseline.getAwayShots() != changed.getAwayShots(),
                "legacy generation must continue to consume live Stats configuration");
    }

    private MatchStats simulate(long seed, int homeGoals, int awayGoals,
                                double homePower, double awayPower) {
        service.setRandomForTesting(new Random(seed));
        return service.generateMatchStats(
                1, 1, 1, 1, 2, homeGoals, awayGoals, homePower, awayPower, null, null);
    }

    private MatchScoringDecision decision(int home, int away, ScoreEngineKind engine,
                                          Double homeXg, Double awayXg) {
        return decision(home, away, engine, homeXg, awayXg, 5_000, 4_000);
    }

    private MatchScoringDecision decision(int home, int away, ScoreEngineKind engine,
                                          Double homeXg, Double awayXg,
                                          double homePower, double awayPower) {
        return new MatchScoringDecision("CTIM:canonical", 7L, engine, engine.algorithmVersion(),
                "a".repeat(64), "b".repeat(64), home, away, homePower, awayPower, homeXg, awayXg);
    }

    private CanonicalMatchEffectEvent goal(int slot, int minute, long team, long player) {
        return new CanonicalMatchEffectEvent(slot, minute, team, player, "GOAL");
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
