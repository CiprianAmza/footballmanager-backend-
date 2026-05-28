package com.footballmanagergamesimulator.integration.interaction;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.engine.interaction.InteractionAnalyzer;
import com.footballmanagergamesimulator.engine.interaction.InteractionMatrix;
import com.footballmanagergamesimulator.engine.interaction.InteractionResult;
import com.footballmanagergamesimulator.engine.interaction.PairInteractionResult;
import com.footballmanagergamesimulator.engine.invariants.DefaultInvariants;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.tuner.ParameterSpace;
import com.footballmanagergamesimulator.engine.tuner.TunableParameter;
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
 * Sobol-index interaction analysis — for each parameter × invariant, reports
 * first-order (main effect) and total-order (main + interactions) indices.
 * Writes a markdown report to {@code target/interaction-report.md}.
 *
 * <p>Answers questions one-axis sensitivity can't:
 * <ul>
 *   <li>"Does morale matter <i>more</i> when teams are close in power?" →
 *       look at the S1/ST gap for {@code runner.moraleSpread} on
 *       {@code power-equal-balanced} vs {@code morale-high-vs-low}.</li>
 *   <li>"Are any two parameters confounded?" → both will show high
 *       {@code ST − S1} on the same invariant.</li>
 *   <li>"Is the engine truly additive, or do knobs reinforce each other?"
 *       → if {@code Σ S1 ≈ 1} for an invariant, additive; if much less,
 *       interaction-heavy.</li>
 * </ul>
 *
 * <p>Gated behind {@code mvn verify -Ptune} like the other heavy analyses.
 */
@SpringBootTest
@DisplayName("Engine interaction — Sobol S1/ST indices per param × invariant")
class EngineInteractionIT {

    private static final long BASE_SEED = 20260528L;
    private static final Path REPORT_PATH = Path.of("target", "interaction-report.md");

    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchEngineConfig engineConfig;

    @Test
    @DisplayName("Run Saltelli/Jansen Sobol on the 5-knob space, write markdown report")
    void analyzeAndWriteReport() throws Exception {
        EngineInvariantSuiteRunner runner = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);

        // Snapshot starting config so we can restore after the destructive analysis.
        double startExponent = engineConfig.getPower().getRatioExponent();
        double startGoals = engineConfig.getPower().getExpectedGoalsTotal();
        double startMoraleFloor = runner.moraleFloor;
        double startMoraleSpread = runner.moraleSpread;
        double startHomeAdvantage = runner.homeAdvantage;

        ParameterSpace space = new ParameterSpace(List.of(
                new TunableParameter("power.ratioExponent", 1.5, 3.5, 0.1,
                        () -> engineConfig.getPower().getRatioExponent(),
                        v -> engineConfig.getPower().setRatioExponent(v)),
                new TunableParameter("power.expectedGoalsTotal", 2.0, 3.5, 0.25,
                        () -> engineConfig.getPower().getExpectedGoalsTotal(),
                        v -> engineConfig.getPower().setExpectedGoalsTotal(v)),
                new TunableParameter("runner.moraleFloor", 0.4, 0.9, 0.05,
                        () -> runner.moraleFloor,
                        v -> runner.moraleFloor = v),
                new TunableParameter("runner.moraleSpread", 0.2, 0.8, 0.05,
                        () -> runner.moraleSpread,
                        v -> runner.moraleSpread = v),
                new TunableParameter("runner.homeAdvantage", 1.0, 1.25, 0.02,
                        () -> runner.homeAdvantage,
                        v -> runner.homeAdvantage = v)
        ));

        InteractionAnalyzer analyzer = new InteractionAnalyzer(
                runner, DefaultInvariants.catalog(), space, BASE_SEED);
        // N=256 — tighter Sobol noise floor (was 128, S1 noise ~±0.05).
        // Combined with the BA-matrix sampling for S2, that's 256×(2k+2)=3072
        // evaluations per invariant, or ~30k total → ~12M calculateScores.
        analyzer.sampleSize = 256;
        analyzer.iterationsPerEvaluation = 400;

        long t0 = System.nanoTime();
        InteractionMatrix matrix = analyzer.analyze();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Restore baseline so subsequent tests see the original config.
        engineConfig.getPower().setRatioExponent(startExponent);
        engineConfig.getPower().setExpectedGoalsTotal(startGoals);
        runner.moraleFloor = startMoraleFloor;
        runner.moraleSpread = startMoraleSpread;
        runner.homeAdvantage = startHomeAdvantage;

        // Harness sanity assertions only.
        assertThat(matrix.parameterNames()).hasSize(space.size());
        assertThat(matrix.invariantNames()).hasSize(DefaultInvariants.catalog().size());

        String md = buildReport(matrix, elapsedMs, analyzer);
        Files.writeString(REPORT_PATH, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());
    }

    private static String buildReport(InteractionMatrix matrix, long elapsedMs,
                                      InteractionAnalyzer analyzer) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Engine Interaction Analysis (Sobol indices)\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Elapsed: ").append(elapsedMs).append(" ms\n");
        sb.append("Sample size N = ").append(analyzer.sampleSize)
                .append(", iters/eval = ").append(analyzer.iterationsPerEvaluation)
                .append("\n\n");

        // ---- Per-cell table: S1 / ST / interaction ----
        sb.append("## Sobol Indices — S1 (main effect) / ST (total) / Δ (interaction)\n\n");
        sb.append("Read the cell as `S1 | ST | Δ`. ");
        sb.append("S1 = parameter's standalone effect on this invariant's variance. ");
        sb.append("ST = main + all interactions. ");
        sb.append("Δ = ST − S1 = interaction strength. ");
        sb.append("All values are fractions of total output variance (0..1).\n\n");

        sb.append("| Parameter |");
        for (String inv : matrix.invariantNames()) sb.append(' ').append(inv).append(" |");
        sb.append("\n|---|");
        for (int i = 0; i < matrix.invariantNames().size(); i++) sb.append("---|");
        sb.append('\n');

        for (String p : matrix.parameterNames()) {
            sb.append("| ").append(p).append(" |");
            for (String inv : matrix.invariantNames()) {
                InteractionResult r = matrix.get(p, inv);
                if (r == null) {
                    sb.append(" — |");
                } else {
                    sb.append(' ')
                            .append(String.format("%.2f", r.s1Clamped())).append(" \\| ")
                            .append(String.format("%.2f", r.stClamped())).append(" \\| ")
                            .append(String.format("%.2f", r.interactionStrength()))
                            .append(" |");
                }
            }
            sb.append('\n');
        }
        sb.append('\n');

        // ---- Total impact (ST) ranking ----
        sb.append("## Total Impact Ranking (Σ ST across invariants)\n\n");
        sb.append("\"Which parameters matter most overall, including their interactions?\"\n\n");
        sb.append("| Rank | Parameter | Σ ST |\n|---|---|---|\n");
        int rank = 1;
        for (InteractionMatrix.ParameterRank pr : matrix.rankByTotalImpact()) {
            sb.append("| ").append(rank++).append(" | ")
                    .append(pr.parameterName()).append(" | ")
                    .append(String.format("%.2f", pr.score()))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Interaction-only ranking ----
        sb.append("## Interaction-Heaviness Ranking (Σ (ST − S1))\n\n");
        sb.append("\"Which knobs cannot be tuned in isolation — they only matter ")
                .append("via interactions with others?\"\n\n");
        sb.append("| Rank | Parameter | Σ Δ |\n|---|---|---|\n");
        rank = 1;
        for (InteractionMatrix.ParameterRank pr : matrix.rankByInteraction()) {
            sb.append("| ").append(rank++).append(" | ")
                    .append(pr.parameterName()).append(" | ")
                    .append(String.format("%.2f", pr.score()))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Top interactor per invariant ----
        sb.append("## Top Interactor per Invariant\n\n");
        sb.append("\"For this invariant, which parameter has the largest interaction ")
                .append("effect (Δ) — i.e. cannot be analyzed alone?\"\n\n");
        sb.append("| Invariant | Top interactor | S1 | ST | Δ |\n|---|---|---|---|---|\n");
        for (String inv : matrix.invariantNames()) {
            InteractionResult r = matrix.topInteractor(inv);
            if (r == null) continue;
            sb.append("| ").append(inv).append(" | ").append(r.parameterName())
                    .append(" | ").append(String.format("%.2f", r.s1Clamped()))
                    .append(" | ").append(String.format("%.2f", r.stClamped()))
                    .append(" | ").append(String.format("%.2f", r.interactionStrength()))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Second-order: top pair interactions per invariant ----
        sb.append("## Pair Interactions per Invariant (top 3 S_ij)\n\n");
        sb.append("Second-order Sobol indices: ");
        sb.append("**S_ij = fraction of variance from the dedicated (i × j) interaction**, ");
        sb.append("after removing both parameters' main effects. ");
        sb.append("This answers \"which pair of knobs reinforces or fights each other on this invariant?\".\n\n");
        for (String inv : matrix.invariantNames()) {
            List<PairInteractionResult> top = matrix.topPairsForInvariant(inv, 3);
            sb.append("**").append(inv).append("**\n\n");
            sb.append("| Pair | S_ij |\n|---|---|\n");
            for (PairInteractionResult p : top) {
                sb.append("| ").append(p.paramA()).append(" × ").append(p.paramB())
                        .append(" | ").append(String.format("%.3f", p.s2()))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        // ---- Global top pair interactions ----
        sb.append("## Global Top Pair Interactions (summed across invariants)\n\n");
        sb.append("\"Across the whole catalog, which two parameters most often interact?\"\n\n");
        sb.append("| Rank | Pair | Σ S_ij |\n|---|---|---|\n");
        int rk = 1;
        for (InteractionMatrix.PairRank pr : matrix.rankPairsGlobal(10)) {
            sb.append("| ").append(rk++).append(" | ").append(pr.label())
                    .append(" | ").append(String.format("%.3f", pr.summedS2()))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Interpretation primer ----
        sb.append("## How to read this report\n\n");
        sb.append("- **S1 ≈ ST**: parameter acts independently (purely additive effect). ")
                .append("Safe to tune in isolation.\n");
        sb.append("- **ST &gt;&gt; S1 (large Δ)**: parameter's effect depends heavily on ")
                .append("what the other knobs are set to. Tuning it without checking the ")
                .append("rest can produce surprises.\n");
        sb.append("- **Σ S1 ≈ 1 across all params for an invariant**: the engine is ")
                .append("decomposable; each knob contributes a clean slice of variance.\n");
        sb.append("- **Σ S1 &lt;&lt; 1**: hidden interactions dominate; the auto-tuner ")
                .append("is finding configurations that work only as a bundle.\n");
        sb.append("- **Both S1 and ST near 0**: parameter is inert on this invariant. ")
                .append("Drop it from tuning to speed up search.\n");

        return sb.toString();
    }
}
