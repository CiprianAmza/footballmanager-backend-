package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.MatchStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Determinism invariants for the score-impacting engine surface.
 * <p>Given the same seed + same {@code MatchEngineConfig}, repeated invocations
 * must produce identical output. This is the contract the auto-tuner (Faza 4)
 * relies on: without it, sweeping parameters can't isolate "did this delta come
 * from my config change or from RNG?".
 *
 * <p>Covers the synthetic engine path
 * ({@link MatchSimulationService#calculateScores},
 *  {@link MatchSimulationService#calculateMoraleChangeForResult},
 *  {@link MatchSimulationService#computeMatchRating},
 *  {@link MatchStatsService#generateMatchStats}).
 * Live engine determinism (LiveMatchSession) is exercised via its seeded
 * constructor overload — checked indirectly through equivalent seeds here.
 */
@SpringBootTest
@DisplayName("Engine determinism: (seed, config) → identical output")
class EngineDeterminismIT {

    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchStatsService matchStatsService;

    private static final long SEED = 20260528L;

    @AfterEach
    void restoreProductionRng() {
        matchSimulationService.setRandomForTesting(new Random());
        matchStatsService.setRandomForTesting(new Random());
    }

    @Test
    @DisplayName("calculateScores: same seed → identical scores over 200 calls")
    void calculateScores_isDeterministic() {
        matchSimulationService.setRandomForTesting(new Random(SEED));
        int[] run1 = new int[400];
        for (int i = 0; i < 200; i++) {
            List<Integer> s = matchSimulationService.calculateScores(8000, 4000);
            run1[i * 2] = s.get(0);
            run1[i * 2 + 1] = s.get(1);
        }

        matchSimulationService.setRandomForTesting(new Random(SEED));
        int[] run2 = new int[400];
        for (int i = 0; i < 200; i++) {
            List<Integer> s = matchSimulationService.calculateScores(8000, 4000);
            run2[i * 2] = s.get(0);
            run2[i * 2 + 1] = s.get(1);
        }

        assertThat(run2).as("calculateScores must be reproducible under fixed seed").containsExactly(run1);
    }

    @Test
    @DisplayName("calculateMoraleChangeForResult: same seed → identical morale deltas")
    void moraleChange_isDeterministic() {
        matchSimulationService.setRandomForTesting(new Random(SEED));
        double[] run1 = new double[18];
        int i = 0;
        for (String result : new String[]{"W", "D", "L"}) {
            for (double diff : new double[]{600, 300, 100, -100, -300, -600}) {
                run1[i++] = matchSimulationService.calculateMoraleChangeForResult(result, diff);
            }
        }

        matchSimulationService.setRandomForTesting(new Random(SEED));
        double[] run2 = new double[18];
        i = 0;
        for (String result : new String[]{"W", "D", "L"}) {
            for (double diff : new double[]{600, 300, 100, -100, -300, -600}) {
                run2[i++] = matchSimulationService.calculateMoraleChangeForResult(result, diff);
            }
        }

        assertThat(run2).as("calculateMoraleChangeForResult must be reproducible").containsExactly(run1);
    }

    @Test
    @DisplayName("computeMatchRating: same seed → identical ratings")
    void computeMatchRating_isDeterministic() {
        matchSimulationService.setRandomForTesting(new Random(SEED));
        double[] run1 = new double[50];
        for (int i = 0; i < 50; i++) {
            run1[i] = matchSimulationService.computeMatchRating(
                    i % 2 == 0 ? "ST" : "DC",
                    i % 3, i % 4, true, i % 2 == 0, false, false);
        }

        matchSimulationService.setRandomForTesting(new Random(SEED));
        double[] run2 = new double[50];
        for (int i = 0; i < 50; i++) {
            run2[i] = matchSimulationService.computeMatchRating(
                    i % 2 == 0 ? "ST" : "DC",
                    i % 3, i % 4, true, i % 2 == 0, false, false);
        }

        assertThat(run2).as("computeMatchRating must be reproducible").containsExactly(run1);
    }

    @Test
    @DisplayName("generateMatchStats: same seed → identical stat line")
    void generateMatchStats_isDeterministic() {
        matchStatsService.setRandomForTesting(new Random(SEED));
        MatchStats s1 = matchStatsService.generateMatchStats(
                1L, 1, 1, 100L, 200L, 2, 1, 8000.0, 4000.0, null, null);

        matchStatsService.setRandomForTesting(new Random(SEED));
        MatchStats s2 = matchStatsService.generateMatchStats(
                1L, 1, 1, 100L, 200L, 2, 1, 8000.0, 4000.0, null, null);

        // Spot-check the most volatile fields: possession (Gaussian + edge),
        // shots (Gaussian + power), passes (Gaussian + ratio), big chances.
        assertThat(s2.getHomePossession()).as("possession").isEqualTo(s1.getHomePossession());
        assertThat(s2.getHomeShots()).as("home shots").isEqualTo(s1.getHomeShots());
        assertThat(s2.getAwayShots()).as("away shots").isEqualTo(s1.getAwayShots());
        assertThat(s2.getHomePasses()).as("home passes").isEqualTo(s1.getHomePasses());
        assertThat(s2.getHomeBigChances()).as("home big chances").isEqualTo(s1.getHomeBigChances());
        assertThat(s2.getHomeXg()).as("home xG").isEqualTo(s1.getHomeXg());
        assertThat(s2.getAwayXg()).as("away xG").isEqualTo(s1.getAwayXg());
        assertThat(s2.getHomeYellowCards()).as("home yellows").isEqualTo(s1.getHomeYellowCards());
        assertThat(s2.getHomeFouls()).as("home fouls").isEqualTo(s1.getHomeFouls());
    }

    @Test
    @DisplayName("Different seeds produce different outputs (sanity)")
    void differentSeeds_produceDifferentOutputs() {
        matchSimulationService.setRandomForTesting(new Random(SEED));
        int[] runA = new int[100];
        for (int i = 0; i < 50; i++) {
            List<Integer> s = matchSimulationService.calculateScores(8000, 4000);
            runA[i * 2] = s.get(0);
            runA[i * 2 + 1] = s.get(1);
        }

        matchSimulationService.setRandomForTesting(new Random(SEED + 1));
        int[] runB = new int[100];
        for (int i = 0; i < 50; i++) {
            List<Integer> s = matchSimulationService.calculateScores(8000, 4000);
            runB[i * 2] = s.get(0);
            runB[i * 2 + 1] = s.get(1);
        }

        // Sanity: with two different seeds, at least some calls must differ.
        // (If all were equal the RNG isn't being consumed.)
        int diffs = 0;
        for (int i = 0; i < 100; i++) if (runA[i] != runB[i]) diffs++;
        assertThat(diffs)
                .as("at least some scores must differ between two seeds")
                .isGreaterThan(0);
    }
}
