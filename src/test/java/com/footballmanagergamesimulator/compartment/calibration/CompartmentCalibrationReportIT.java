package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** One opt-in executable that produces the complete calibration artifact set. */
@EnabledIfSystemProperty(named = "compartment.calibration.report", matches = "true")
class CompartmentCalibrationReportIT {

    @Test
    void writesCompleteWeightAndTwoHundredSeasonLeagueReport() throws Exception {
        CalibrationRunConfig run = CalibrationRunConfig.load();
        CalibrationConfigProfile config = CalibrationConfigFixture.load();
        CanonicalScoreSampler sampler = new CanonicalScoreSampler();
        CanonicalScoringWeightCatalog catalog = CanonicalScoringWeightCatalog.from(
                config.compartment(), config.match());
        ScoringSensitivityHarness sweepHarness = new ScoringSensitivityHarness(
                config.compartment(), config.match(), sampler);
        DetailedLeagueCalibrationHarness leagueHarness = new DetailedLeagueCalibrationHarness(
                config.compartment(), config.match(), sampler);

        DetailedLeagueCalibrationHarness.Result defaultBaseline = leagueHarness.run(
                CalibrationScenarioFixtures.midTableLeague(), run.fullLeague().seasons(),
                run.seed(), run.finishBuckets());
        List<CalibrationReportWriter.SweepRow> sweepRows = runSweep(run, catalog, sweepHarness);
        List<String> selected = selectFullLeagueWeights(run, catalog, sweepRows);
        List<CalibrationReportWriter.FullLeagueRun> leagueRuns = runFullLeagues(
                run, catalog, leagueHarness, selected);

        var manifest = new CalibrationReportWriter.RunManifest(
                Instant.now().toString(), gitHead(), run.sourceFile().toString(), sha256(run.sourceFile()),
                sha256(Path.of("src", "main", "resources", "compartment-scoring-weights-v1.yml")),
                run.seed(), run.fastSweep().seasons(), run.fullLeague().seasons(),
                run.fastSweep().percentages(), run.fullLeague().percentages(), selected,
                run.fullLeague().topWeightCount());
        new CalibrationReportWriter().write(run.outputDirectory(), manifest, catalog,
                defaultBaseline, sweepRows, leagueRuns);

        System.out.printf("Calibration complete: %d active weights, %d sweep runs, %d full-league runs, baseline %.3f points / position %.3f. Report: %s%n",
                catalog.size(), sweepRows.size(), leagueRuns.size(),
                defaultBaseline.candidate().averagePoints(), defaultBaseline.candidate().averagePosition(),
                run.outputDirectory().toAbsolutePath().normalize().resolve("report.html"));
    }

    private static List<CalibrationReportWriter.SweepRow> runSweep(
            CalibrationRunConfig run,
            CanonicalScoringWeightCatalog catalog,
            ScoringSensitivityHarness harness) {
        List<CalibrationReportWriter.SweepRow> rows = new ArrayList<>();
        int completed = 0;
        int total = catalog.leafWeights().size() * run.fastSweep().percentages().size();
        for (CanonicalScoringWeightKey leaf : catalog.leafWeights()) {
            for (double percent : run.fastSweep().percentages()) {
                try {
                    CanonicalScoringWeightOverride override = CalibrationPercentageOverride.create(leaf, percent);
                    ScoringSensitivityResult result = harness.run(
                            CalibrationScenarioFactory.forWeight(leaf, run.fastSweep().seasons()),
                            catalog, override);
                    double actual = CalibrationPercentageOverride.actualPercent(leaf, override.value());
                    double denominator = Double.isFinite(actual) && actual != 0.0 ? Math.abs(actual) : Math.abs(percent);
                    rows.add(new CalibrationReportWriter.SweepRow(leaf.path(), leaf.category().name(), leaf.consumer(),
                            ((Number) leaf.baselineValue()).doubleValue(), override.value(), percent, actual,
                            result.baselineAveragePoints(), result.testedAveragePoints(), result.pointsDelta(),
                            result.pointsDelta() / denominator, result.confidenceInterval(),
                            result.baselineGoalsFor(), result.testedGoalsFor(), result.baselineGoalsAgainst(),
                            result.testedGoalsAgainst(), result.baselineXgFor(), result.testedXgFor(), "PASS", ""));
                } catch (CalibrationPercentageOverride.NoLegalDirectionException exception) {
                    rows.add(skippedSweep(leaf, percent, exception));
                } catch (RuntimeException exception) {
                    rows.add(failureSweep(leaf, percent, exception));
                }
                completed++;
                if (completed % 100 == 0 || completed == total) {
                    System.out.printf("Calibration sweep: %d/%d%n", completed, total);
                }
            }
        }
        return List.copyOf(rows);
    }

    private static CalibrationReportWriter.SweepRow failureSweep(
            CanonicalScoringWeightKey leaf, double percent, RuntimeException exception) {
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        return new CalibrationReportWriter.SweepRow(leaf.path(), leaf.category().name(), leaf.consumer(),
                baseline, Double.NaN, percent, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, "FAILED", rootMessage(exception));
    }

    private static CalibrationReportWriter.SweepRow skippedSweep(
            CanonicalScoringWeightKey leaf, double percent, RuntimeException exception) {
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        return new CalibrationReportWriter.SweepRow(leaf.path(), leaf.category().name(), leaf.consumer(),
                baseline, baseline, percent, 0.0, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, "SKIPPED_BOUND", rootMessage(exception));
    }

    private static List<String> selectFullLeagueWeights(
            CalibrationRunConfig run,
            CanonicalScoringWeightCatalog catalog,
            List<CalibrationReportWriter.SweepRow> rows) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String path : run.fullLeague().selectedWeights()) {
            catalog.require(path);
            selected.add(path);
        }
        Map<String, Double> maxImpact = new LinkedHashMap<>();
        rows.stream().filter(CalibrationReportWriter.SweepRow::successful).forEach(row ->
                maxImpact.merge(row.weightKey(), row.absoluteNormalizedImpact(), Math::max));
        maxImpact.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(run.fullLeague().topWeightCount())
                .forEach(entry -> selected.add(entry.getKey()));
        return List.copyOf(selected);
    }

    private static List<CalibrationReportWriter.FullLeagueRun> runFullLeagues(
            CalibrationRunConfig run,
            CanonicalScoringWeightCatalog catalog,
            DetailedLeagueCalibrationHarness harness,
            List<String> selected) {
        List<CalibrationReportWriter.FullLeagueRun> results = new ArrayList<>();
        int completed = 0;
        int total = selected.size() * run.fullLeague().percentages().size();
        for (String path : selected) {
            CanonicalScoringWeightKey leaf = catalog.require(path);
            ScoringSensitivityScenario activated = CalibrationScenarioFactory.forWeight(leaf, 1);
            List<CalibrationTeam> teams = new ArrayList<>(CalibrationScenarioFixtures.midTableLeague());
            teams.set(0, activated.baselineTeam());
            DetailedLeagueCalibrationHarness.Result baseline;
            try {
                baseline = harness.run(teams, run.fullLeague().seasons(), run.seed(), run.finishBuckets());
            } catch (RuntimeException exception) {
                for (double percent : run.fullLeague().percentages()) {
                    results.add(failureLeague(leaf, percent, exception));
                }
                continue;
            }
            for (double percent : run.fullLeague().percentages()) {
                try {
                    CanonicalScoringWeightOverride override = CalibrationPercentageOverride.create(leaf, percent);
                    DetailedLeagueCalibrationHarness.Result tested = harness.run(teams,
                            run.fullLeague().seasons(), run.seed(), run.finishBuckets(), catalog, override);
                    results.add(new CalibrationReportWriter.FullLeagueRun(path, leaf.category().name(), leaf.consumer(),
                            ((Number) leaf.baselineValue()).doubleValue(), override.value(), percent,
                            CalibrationPercentageOverride.actualPercent(leaf, override.value()),
                            baseline, tested, "PASS", ""));
                } catch (CalibrationPercentageOverride.NoLegalDirectionException exception) {
                    results.add(skippedLeague(leaf, percent, exception));
                } catch (RuntimeException exception) {
                    results.add(failureLeague(leaf, percent, exception));
                }
                completed++;
                System.out.printf("Full-league calibration: %d/%d (%s %+.1f%%)%n", completed, total, path, percent);
            }
        }
        return List.copyOf(results);
    }

    private static CalibrationReportWriter.FullLeagueRun failureLeague(
            CanonicalScoringWeightKey leaf, double percent, RuntimeException exception) {
        return new CalibrationReportWriter.FullLeagueRun(leaf.path(), leaf.category().name(), leaf.consumer(),
                ((Number) leaf.baselineValue()).doubleValue(), Double.NaN, percent, Double.NaN,
                null, null, "FAILED", rootMessage(exception));
    }

    private static CalibrationReportWriter.FullLeagueRun skippedLeague(
            CanonicalScoringWeightKey leaf, double percent, RuntimeException exception) {
        double baseline = ((Number) leaf.baselineValue()).doubleValue();
        return new CalibrationReportWriter.FullLeagueRun(leaf.path(), leaf.category().name(), leaf.consumer(),
                baseline, baseline, percent, 0.0, null, null,
                "SKIPPED_BOUND", rootMessage(exception));
    }

    private static String gitHead() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 ? value : "UNKNOWN";
        } catch (Exception exception) {
            return "UNKNOWN";
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
