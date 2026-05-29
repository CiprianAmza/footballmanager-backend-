package com.footballmanagergamesimulator.integration.tuner;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.engine.invariants.DefaultInvariants;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.invariants.InvariantResult;
import com.footballmanagergamesimulator.engine.tuner.EngineAutoTuner;
import com.footballmanagergamesimulator.engine.tuner.ParameterSpace;
import com.footballmanagergamesimulator.engine.tuner.TunableParameter;
import com.footballmanagergamesimulator.engine.tuner.TuningResult;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-tuner integration test — runs the random-search + hill-climb tuner
 * over {@link DefaultInvariants} and writes a markdown report to
 * {@code target/tuning-report.md}.
 *
 * <p>Excluded from {@code mvn verify} by default (heavy: ~1-3 min). Enable
 * with {@code mvn verify -Ptune}.
 *
 * <p>The test always passes as long as the tuner produces SOME best
 * candidate (i.e. the search machinery isn't broken). Quality of the
 * config it finds is reported via the markdown file + stdout for human
 * review.
 */
@SpringBootTest
@DisplayName("Engine auto-tuner — random search + hill climb over DefaultInvariants")
class EngineAutoTunerIT {

    private static final long BASE_SEED = 20260528L;
    private static final Path REPORT_PATH = Path.of("target", "tuning-report.md");

    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchEngineConfig engineConfig;

    @Test
    @DisplayName("Tune over power + runner knobs, write target/tuning-report.md")
    void tuneAndWriteReport() throws Exception {
        EngineInvariantSuiteRunner runner = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);

        // Snapshot starting values so the report can show before/after.
        double startExponent = engineConfig.getPower().getRatioExponent();
        double startGoals = engineConfig.getPower().getExpectedGoalsTotal();
        double startMoraleFloor = engineConfig.getPower().getMoraleFloor();
        double startMoraleSpread = engineConfig.getPower().getMoraleSpread();
        double startHomeAdvantage = engineConfig.getPower().getHomeAdvantage();
        List<InvariantResult> beforeResults = runner.runAll(DefaultInvariants.catalog());

        // Parameter space: all 5 score-deciding knobs live in MatchEngineConfig, so
        // the tuner sweeps the SAME values the catalog + the game read.
        ParameterSpace space = new ParameterSpace(List.of(
                new TunableParameter("power.ratioExponent", 1.5, 3.5, 0.1,
                        () -> engineConfig.getPower().getRatioExponent(),
                        v -> engineConfig.getPower().setRatioExponent(v)),
                new TunableParameter("power.expectedGoalsTotal", 2.0, 3.5, 0.25,
                        () -> engineConfig.getPower().getExpectedGoalsTotal(),
                        v -> engineConfig.getPower().setExpectedGoalsTotal(v)),
                new TunableParameter("power.moraleFloor", 0.4, 0.9, 0.05,
                        () -> engineConfig.getPower().getMoraleFloor(),
                        v -> engineConfig.getPower().setMoraleFloor(v)),
                new TunableParameter("power.moraleSpread", 0.2, 0.8, 0.05,
                        () -> engineConfig.getPower().getMoraleSpread(),
                        v -> engineConfig.getPower().setMoraleSpread(v)),
                new TunableParameter("power.homeAdvantage", 1.0, 1.25, 0.02,
                        () -> engineConfig.getPower().getHomeAdvantage(),
                        v -> engineConfig.getPower().setHomeAdvantage(v))
        ));

        EngineAutoTuner tuner = new EngineAutoTuner(
                runner, DefaultInvariants.catalog(), space, BASE_SEED);
        // Trimmed down vs the recommended 200 so the IT stays inside ~1-2 min.
        // Tune the constants here if you have more time budget.
        tuner.randomIterations = 80;
        tuner.maxHillClimbSteps = 60;

        long t0 = System.nanoTime();
        TuningResult result;
        try {
            result = tuner.tune();
        } finally {
            // Restore baseline config so other -Ptune tests see the original values.
            engineConfig.getPower().setRatioExponent(startExponent);
            engineConfig.getPower().setExpectedGoalsTotal(startGoals);
            engineConfig.getPower().setMoraleFloor(startMoraleFloor);
            engineConfig.getPower().setMoraleSpread(startMoraleSpread);
            engineConfig.getPower().setHomeAdvantage(startHomeAdvantage);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Sanity assertions — tuner machinery is alive.
        assertThat(result.best()).as("tuner must find at least one candidate").isNotNull();
        assertThat(result.bestResults()).as("must report invariant results").isNotNull();
        assertThat(result.bestResults().size())
                .as("one result per invariant")
                .isEqualTo(DefaultInvariants.catalog().size());

        String md = buildReport(result, beforeResults, elapsedMs,
                startExponent, startGoals, startMoraleFloor, startMoraleSpread, startHomeAdvantage);
        Files.writeString(REPORT_PATH, md);

        // Also dump to stdout so CI logs show it without opening the file.
        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());
    }

    private static String buildReport(TuningResult result,
                                      List<InvariantResult> beforeResults,
                                      long elapsedMs,
                                      double startExponent, double startGoals,
                                      double startMoraleFloor, double startMoraleSpread,
                                      double startHomeAdvantage) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Engine Auto-Tuner Report\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Elapsed: ").append(elapsedMs).append(" ms\n\n");

        sb.append("## Tuning Summary\n\n");
        sb.append("| Phase | Iterations |\n|---|---|\n");
        sb.append("| Random search | ").append(result.randomSearchPhaseSize()).append(" |\n");
        sb.append("| Hill climb | ").append(result.hillClimbPhaseSize()).append(" |\n");
        sb.append("| **Total candidates evaluated** | **").append(result.totalCandidates()).append("** |\n\n");

        sb.append("**Best fitness:** ").append(String.format("%.5f", result.bestFitness()))
                .append(" (0 = perfect, more negative = worse)\n");
        sb.append("**Invariants passing:** ").append(result.passCount())
                .append(" / ").append(result.totalInvariants()).append("\n\n");

        sb.append("## Best Candidate Found\n\n");
        sb.append("| Parameter | Starting Value | Best Value |\n|---|---|---|\n");
        sb.append("| power.ratioExponent | ").append(fmt(startExponent)).append(" | ")
                .append(fmt(result.best().values().get("power.ratioExponent"))).append(" |\n");
        sb.append("| power.expectedGoalsTotal | ").append(fmt(startGoals)).append(" | ")
                .append(fmt(result.best().values().get("power.expectedGoalsTotal"))).append(" |\n");
        sb.append("| power.moraleFloor | ").append(fmt(startMoraleFloor)).append(" | ")
                .append(fmt(result.best().values().get("power.moraleFloor"))).append(" |\n");
        sb.append("| power.moraleSpread | ").append(fmt(startMoraleSpread)).append(" | ")
                .append(fmt(result.best().values().get("power.moraleSpread"))).append(" |\n");
        sb.append("| power.homeAdvantage | ").append(fmt(startHomeAdvantage)).append(" | ")
                .append(fmt(result.best().values().get("power.homeAdvantage"))).append(" |\n\n");

        sb.append("## Invariant Results — Before vs After\n\n");
        sb.append("| Invariant | Target | Before | After | Status |\n|---|---|---|---|---|\n");
        for (int i = 0; i < result.bestResults().size(); i++) {
            InvariantResult before = beforeResults.get(i);
            InvariantResult after = result.bestResults().get(i);
            String target;
            double min = after.invariant().minWinRateA();
            double max = after.invariant().maxWinRateA();
            if (min == 0.0 && max < 1.0) target = String.format("≤%.0f%%", max * 100);
            else if (min > 0.0 && max == 1.0) target = String.format("≥%.0f%%", min * 100);
            else target = String.format("%.0f-%.0f%%", min * 100, max * 100);
            sb.append("| ").append(after.invariant().name())
                    .append(" | ").append(target)
                    .append(" | ").append(String.format("%.1f%%", before.winRateA() * 100))
                    .append(" | ").append(String.format("%.1f%%", after.winRateA() * 100))
                    .append(" | ").append(after.passed() ? "PASS" : "FAIL")
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## Trajectory (best-fitness-so-far)\n\n");
        sb.append("| Phase | Iteration | Best Fitness |\n|---|---|---|\n");
        for (TuningResult.TrajectoryPoint p : result.trajectory()) {
            sb.append("| ").append(p.phase())
                    .append(" | ").append(p.iteration())
                    .append(" | ").append(String.format("%.5f", p.bestFitness()))
                    .append(" |\n");
        }
        sb.append("\n");

        sb.append("## How to apply this config\n\n");
        sb.append("Copy the \"Best Value\" column into `src/main/resources/application.yml`:\n");
        sb.append("```yaml\n");
        sb.append("match:\n  engine:\n    power:\n");
        sb.append("      ratio-exponent: ").append(fmt(result.best().values().get("power.ratioExponent"))).append('\n');
        sb.append("      expected-goals-total: ").append(fmt(result.best().values().get("power.expectedGoalsTotal"))).append('\n');
        sb.append("      morale-floor: ").append(fmt(result.best().values().get("power.moraleFloor"))).append('\n');
        sb.append("      morale-spread: ").append(fmt(result.best().values().get("power.moraleSpread"))).append('\n');
        sb.append("      home-advantage: ").append(fmt(result.best().values().get("power.homeAdvantage"))).append('\n');
        sb.append("```\n\n");
        sb.append("All five knobs live in `MatchEngineConfig.power`, so the catalog, the tuner, ");
        sb.append("and the live game read the exact same values.\n");

        return sb.toString();
    }

    private static String fmt(Double v) {
        return v == null ? "n/a" : String.format("%.4f", v);
    }
}
