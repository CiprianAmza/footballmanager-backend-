package com.footballmanagergamesimulator.integration.sensitivity;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.engine.invariants.DefaultInvariants;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.sensitivity.SensitivityAnalyzer;
import com.footballmanagergamesimulator.engine.sensitivity.SensitivityMatrix;
import com.footballmanagergamesimulator.engine.sensitivity.SensitivityResult;
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
 * One-axis-at-a-time sensitivity analysis over the engine + runner knobs
 * the auto-tuner already operates on. Writes a markdown report to
 * {@code target/sensitivity-report.md}.
 *
 * <p>Answers the question: <i>"how much does each parameter actually
 * influence each invariant?"</i> — the original goal of the whole tuning
 * effort.
 *
 * <p>Gated behind {@code mvn verify -Ptune} like the auto-tuner.
 */
@SpringBootTest
@DisplayName("Engine sensitivity — pp shift per parameter swing")
class EngineSensitivityIT {

    private static final long BASE_SEED = 20260528L;
    private static final Path REPORT_PATH = Path.of("target", "sensitivity-report.md");

    @Autowired private MatchSimulationService matchSimulationService;
    @Autowired private MatchEngineConfig engineConfig;

    @Test
    @DisplayName("Scan param × invariant matrix, write target/sensitivity-report.md")
    void analyzeAndWriteReport() throws Exception {
        EngineInvariantSuiteRunner runner = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);

        // Same 5 knobs the tuner sweeps. If you add more in the tuner IT,
        // mirror them here so the matrix grows.
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

        SensitivityAnalyzer analyzer = new SensitivityAnalyzer(
                runner, DefaultInvariants.catalog(), space);

        long t0 = System.nanoTime();
        SensitivityMatrix matrix = analyzer.analyze();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(matrix.parameterNames())
                .as("analyzer must produce a row per parameter")
                .hasSize(space.size());
        assertThat(matrix.invariantNames())
                .as("analyzer must produce a column per invariant")
                .hasSize(DefaultInvariants.catalog().size());

        String md = buildReport(matrix, elapsedMs);
        Files.writeString(REPORT_PATH, md);

        System.out.println();
        System.out.println(md);
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());
    }

    private static String buildReport(SensitivityMatrix matrix, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Engine Sensitivity Analysis\n\n");
        sb.append("Run on ").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("Elapsed: ").append(elapsedMs).append(" ms\n");
        sb.append("Mode: full-range swing (parameter swept min → max)\n\n");

        // ---- Baseline ----
        sb.append("## Baseline win rates (current config)\n\n");
        sb.append("| Invariant | Win-rate-A |\n|---|---|\n");
        for (String inv : matrix.invariantNames()) {
            Double rate = matrix.baselineRates().get(inv);
            sb.append("| ").append(inv).append(" | ")
                    .append(rate == null ? "—" : String.format("%.1f%%", rate * 100))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Main matrix ----
        sb.append("## Sensitivity Matrix\n\n");
        sb.append("Cells show **percentage-point shift in win-rate-A** when the parameter ")
                .append("sweeps from its min to its max. Positive = rate goes up with parameter; ")
                .append("negative = rate goes down. Larger |value| = more impact.\n\n");

        sb.append("| Parameter |");
        for (String inv : matrix.invariantNames()) sb.append(' ').append(inv).append(" |");
        sb.append(" Impact |\n|---|");
        for (int i = 0; i < matrix.invariantNames().size(); i++) sb.append("---|");
        sb.append("---|\n");

        for (String p : matrix.parameterNames()) {
            sb.append("| ").append(p).append(" |");
            for (String inv : matrix.invariantNames()) {
                SensitivityResult r = matrix.get(p, inv);
                String cell = r == null ? "—" : formatPp(r.ppShift());
                sb.append(' ').append(cell).append(" |");
            }
            sb.append(' ').append(String.format("%.1f", matrix.impactScore(p) * 100)).append(" |\n");
        }
        sb.append('\n');

        // ---- Top drivers ----
        sb.append("## Top Driver per Invariant\n\n");
        sb.append("\"If this invariant is failing, this is the knob to turn first.\"\n\n");
        sb.append("| Invariant | Top driver | pp shift | Direction |\n|---|---|---|---|\n");
        for (String inv : matrix.invariantNames()) {
            SensitivityResult r = matrix.topDriver(inv);
            if (r == null) continue;
            sb.append("| ").append(inv).append(" | ")
                    .append(r.parameterName()).append(" | ")
                    .append(formatPp(r.ppShift())).append(" | ")
                    .append(r.ppShift() > 0 ? "↑ raises A" : "↓ lowers A")
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Parameter impact ranking ----
        sb.append("## Parameter Impact Ranking\n\n");
        sb.append("Total |pp shift| across all invariants. Low-score knobs at the bottom ")
                .append("are inert and can be dropped from tuning to speed up search.\n\n");
        sb.append("| Rank | Parameter | Impact (sum of |pp shifts|) |\n|---|---|---|\n");
        int rank = 1;
        for (SensitivityMatrix.ParameterRank pr : matrix.rankParametersByImpact()) {
            sb.append("| ").append(rank++).append(" | ")
                    .append(pr.parameterName()).append(" | ")
                    .append(String.format("%.1f pp", pr.impactScore() * 100))
                    .append(" |\n");
        }
        sb.append('\n');

        // ---- Interpretation hints ----
        sb.append("## How to read this report\n\n");
        sb.append("- **High impact + concentrated**: parameter mainly drives one or two invariants ")
                .append("→ a precise lever for that specific behavior.\n");
        sb.append("- **High impact + spread across many invariants**: parameter is foundational ")
                .append("→ tweak carefully, it shifts everything at once.\n");
        sb.append("- **Low impact across all invariants**: parameter has little effect in its current ")
                .append("range; either widen the range, drop it from tuning, or accept it as a constant.\n");
        sb.append("- **Sign mismatch within a row**: parameter raises some invariants while lowering ")
                .append("others → no global \"correct\" direction, only trade-offs the tuner negotiates.\n");

        return sb.toString();
    }

    private static String formatPp(double rateDelta) {
        double pp = rateDelta * 100;
        if (Math.abs(pp) < 0.05) return "0.0";
        return String.format("%+.1f", pp);
    }
}
