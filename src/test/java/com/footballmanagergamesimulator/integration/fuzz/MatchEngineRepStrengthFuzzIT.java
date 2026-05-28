package com.footballmanagergamesimulator.integration.fuzz;

import com.footballmanagergamesimulator.service.MatchSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzz test for the match engine's score-generation invariant:
 * <blockquote>
 *   A team with power 10,000 should beat a team with power 4,000 in
 *   ≥ 97% of matches.
 * </blockquote>
 *
 * <p>This is the <b>pure function</b> variant (planned §11.3 variant A). It
 * targets {@link MatchSimulationService#calculateScores(double, double)} —
 * the Poisson-based score generator — without touching the database, squad
 * generation, morale multipliers, or live-match flow. Goal: validate the
 * math itself before chasing the same invariant through the full
 * integration pipeline.
 *
 * <p>Methodology:
 * <ul>
 *   <li>{@code ITERATIONS} = 2000 single matches</li>
 *   <li>Each iteration uses a seeded {@link Random} ({@code BASE_SEED + i})
 *       via {@link MatchSimulationService#setRandomForTesting(Random)}, so
 *       the test is 100% reproducible — on failure the seed of the offending
 *       iteration is in the log.</li>
 *   <li>Aggregates win / draw / loss counts and asserts the win rate.</li>
 * </ul>
 *
 * <p>Expected to <b>FAIL initially</b>. The current engine has only mild power
 * amplification ({@code Math.pow(ratio, 1.5)}), which leaves ~10-15% upset
 * room for the weaker team at this power delta. The test's failure message
 * surfaces the actual percentage so we can tune ({@code amplification
 * exponent}, {@code expected goals total}, or add an upset cap) and re-run.
 *
 * <p>Run with: {@code mvn verify -Pfuzz}. The {@code fuzz} profile is the
 * only one that includes this class; default {@code mvn verify} excludes
 * {@code *FuzzIT.java} so CI/dev runs stay fast.
 */
@SpringBootTest
class MatchEngineRepStrengthFuzzIT {

    @Autowired
    private MatchSimulationService matchSimulationService;

    private static final int ITERATIONS = 2000;
    private static final double STRONG_POWER = 10_000.0;
    private static final double WEAK_POWER = 4_000.0;
    private static final double EXPECTED_WIN_RATE = 0.97;
    private static final long BASE_SEED = 20260528L;

    @Test
    @DisplayName("Power 10000 vs power 4000 → strong team wins ≥ 97% of matches")
    void strongPowerBeatsWeakPower97PercentOfTime() {
        int strongWins = 0;
        int draws = 0;
        int weakWins = 0;
        int strongGoalsTotal = 0;
        int weakGoalsTotal = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            // Seed determinist per iterație → la eșec, repro-ul e exact.
            matchSimulationService.setRandomForTesting(new Random(BASE_SEED + i));
            List<Integer> scores = matchSimulationService.calculateScores(STRONG_POWER, WEAK_POWER);
            int s1 = scores.get(0);
            int s2 = scores.get(1);
            strongGoalsTotal += s1;
            weakGoalsTotal += s2;
            if (s1 > s2) strongWins++;
            else if (s1 == s2) draws++;
            else weakWins++;
        }

        double winRate = strongWins / (double) ITERATIONS;
        double drawRate = draws / (double) ITERATIONS;
        double weakWinRate = weakWins / (double) ITERATIONS;
        double avgStrongGoals = strongGoalsTotal / (double) ITERATIONS;
        double avgWeakGoals = weakGoalsTotal / (double) ITERATIONS;

        // Restore production RNG so we don't leak the seeded one into other tests
        // sharing this @SpringBootTest context.
        matchSimulationService.setRandomForTesting(new Random());

        // Helpful diagnostic dump regardless of pass/fail — printed to stdout
        // so the buffered surefire output shows the distribution.
        System.out.printf(
            "%n=== MatchEngineRepStrengthFuzzIT results (%d iterations, power %.0f vs %.0f) ===%n"
                + "Strong wins: %d (%.2f%%)%n"
                + "Draws:       %d (%.2f%%)%n"
                + "Weak wins:   %d (%.2f%%)%n"
                + "Avg goals:   strong=%.2f, weak=%.2f%n"
                + "Target:      strong wins ≥ %.0f%%%n"
                + "============================================================%n",
            ITERATIONS, STRONG_POWER, WEAK_POWER,
            strongWins, winRate * 100,
            draws, drawRate * 100,
            weakWins, weakWinRate * 100,
            avgStrongGoals, avgWeakGoals,
            EXPECTED_WIN_RATE * 100);

        assertThat(winRate)
            .as("Strong power (%.0f) vs weak (%.0f) over %d matches: "
                + "expected ≥%.0f%% strong-win rate, actual %.2f%% "
                + "(strongWins=%d, draws=%d, weakWins=%d, avgGoals=%.2f-%.2f). "
                + "Tune `Math.pow(ratio, 1.5)` exponent or total expected goals "
                + "in MatchSimulationService.calculateScores until this passes.",
                STRONG_POWER, WEAK_POWER, ITERATIONS,
                EXPECTED_WIN_RATE * 100, winRate * 100,
                strongWins, draws, weakWins, avgStrongGoals, avgWeakGoals)
            .isGreaterThanOrEqualTo(EXPECTED_WIN_RATE);
    }
}
