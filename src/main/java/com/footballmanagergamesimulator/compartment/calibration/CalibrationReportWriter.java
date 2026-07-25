package com.footballmanagergamesimulator.compartment.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the complete, human-readable and machine-readable calibration artifact set. */
public final class CalibrationReportWriter {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path target,
                      RunManifest manifest,
                      CanonicalScoringWeightCatalog catalog,
                      DetailedLeagueCalibrationHarness.Result baseline,
                      List<SweepRow> sweepRows,
                      List<FullLeagueRun> leagueRuns) throws IOException {
        Path root = Path.of("target", "compartment-calibration").toAbsolutePath().normalize();
        Path destination = target.toAbsolutePath().normalize();
        if (!destination.startsWith(root)) {
            throw new IllegalArgumentException("calibration reports must stay under target/compartment-calibration");
        }
        Files.createDirectories(destination);

        List<SweepRow> orderedSweep = sweepRows.stream()
                .sorted(Comparator.comparingDouble(SweepRow::absoluteNormalizedImpact).reversed()
                        .thenComparing(SweepRow::weightKey)
                        .thenComparingDouble(SweepRow::requestedPercent))
                .toList();
        List<FullLeagueRun> orderedLeagues = leagueRuns.stream()
                .sorted(Comparator.comparing(FullLeagueRun::weightKey)
                        .thenComparingDouble(FullLeagueRun::requestedPercent))
                .toList();

        writeJson(destination.resolve("run-manifest.json"), manifestMap(manifest, catalog));
        writeWeights(destination.resolve("active-weights.csv"), catalog);
        writeStandings(destination.resolve("baseline-standings.csv"), baseline.standings());
        writeTeamSummaries(destination.resolve("baseline-team-summary.csv"), baseline.teamSummaries(), baseline.finishBuckets());
        writeSweep(destination.resolve("weight-impact.csv"), orderedSweep);
        writeJson(destination.resolve("weight-impact.json"), orderedSweep.stream().map(this::sweepMap).toList());
        writeFullLeagueSummary(destination.resolve("full-league-impact.csv"), orderedLeagues);
        writeJson(destination.resolve("full-league-impact.json"), orderedLeagues.stream().map(this::leagueMap).toList());

        Path standingsRoot = destination.resolve("full-league-standings");
        Files.createDirectories(standingsRoot);
        Map<String, DetailedLeagueCalibrationHarness.Result> baselineByWeight = new LinkedHashMap<>();
        for (FullLeagueRun run : orderedLeagues) {
            if (!run.successful()) continue;
            String weightSlug = slug(run.weightKey());
            baselineByWeight.putIfAbsent(weightSlug, run.baseline());
            writeStandings(standingsRoot.resolve(weightSlug + "-baseline.csv"),
                    baselineByWeight.get(weightSlug).standings());
            writeStandings(standingsRoot.resolve(weightSlug + "-" + percentSlug(run.requestedPercent()) + ".csv"),
                    run.tested().standings());
        }
        writeAtomically(destination.resolve("report.html"), html(manifest, baseline, orderedSweep, orderedLeagues));
    }

    private Map<String, Object> manifestMap(RunManifest manifest, CanonicalScoringWeightCatalog catalog) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAtUtc", manifest.generatedAtUtc());
        result.put("gitCommit", manifest.gitCommit());
        result.put("configurationFile", manifest.configurationFile());
        result.put("configurationSha256", manifest.configurationSha256());
        result.put("weightConfigurationSha256", manifest.weightConfigurationSha256());
        result.put("seed", manifest.seed());
        result.put("sweepSeasons", manifest.sweepSeasons());
        result.put("fullLeagueSeasons", manifest.fullLeagueSeasons());
        result.put("sweepPercentages", manifest.sweepPercentages());
        result.put("fullLeaguePercentages", manifest.fullLeaguePercentages());
        result.put("selectedWeights", manifest.selectedWeights());
        result.put("topWeightCount", manifest.topWeightCount());
        result.put("activeNumericWeightCount", catalog.size());
        result.put("weights", catalog.leafWeights().stream().map(weight -> Map.of(
                "path", weight.path(),
                "category", weight.category().name(),
                "consumer", weight.consumer(),
                "type", weight.type().name(),
                "baselineValue", weight.baselineValue())).toList());
        result.put("nonNumericControls", catalog.nonNumericControls());
        result.put("diagnosticOnlyParameters", catalog.diagnosticOnlyParameters());
        result.put("inactiveOrFutureParameters", catalog.inactiveOrFutureParameters());
        return result;
    }

    private void writeWeights(Path destination, CanonicalScoringWeightCatalog catalog) throws IOException {
        StringBuilder csv = new StringBuilder("rank,path,category,type,baselineValue,consumer\n");
        int rank = 1;
        for (CanonicalScoringWeightKey weight : catalog.leafWeights()) {
            csv.append(rank++).append(',').append(csv(weight.path())).append(',')
                    .append(weight.category()).append(',').append(weight.type()).append(',')
                    .append(weight.baselineValue()).append(',').append(csv(weight.consumer())).append('\n');
        }
        writeAtomically(destination, csv.toString());
    }

    private void writeSweep(Path destination, List<SweepRow> rows) throws IOException {
        StringBuilder csv = new StringBuilder("impactRank,weightKey,category,consumer,baselineValue,testedValue,requestedPercent,actualPercent,baselineAveragePoints,testedAveragePoints,pointsDelta,pointsDeltaPerOnePercent,confidence95,baselineGoalsFor,testedGoalsFor,baselineGoalsAgainst,testedGoalsAgainst,baselineXgFor,testedXgFor,status,error\n");
        int rank = 1;
        for (SweepRow row : rows) {
            csv.append(rank++).append(',').append(csv(row.weightKey())).append(',')
                    .append(row.category()).append(',').append(csv(row.consumer())).append(',')
                    .append(number(row.baselineValue())).append(',').append(number(row.testedValue())).append(',')
                    .append(number(row.requestedPercent())).append(',').append(number(row.actualPercent())).append(',')
                    .append(number(row.baselineAveragePoints())).append(',').append(number(row.testedAveragePoints())).append(',')
                    .append(number(row.pointsDelta())).append(',').append(number(row.pointsDeltaPerOnePercent())).append(',')
                    .append(number(row.confidence95())).append(',').append(number(row.baselineGoalsFor())).append(',')
                    .append(number(row.testedGoalsFor())).append(',').append(number(row.baselineGoalsAgainst())).append(',')
                    .append(number(row.testedGoalsAgainst())).append(',').append(number(row.baselineXgFor())).append(',')
                    .append(number(row.testedXgFor())).append(',').append(row.status()).append(',').append(csv(row.error())).append('\n');
        }
        writeAtomically(destination, csv.toString());
    }

    private Map<String, Object> sweepMap(SweepRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("weightKey", row.weightKey()); map.put("category", row.category()); map.put("consumer", row.consumer());
        map.put("baselineValue", jsonNumber(row.baselineValue())); map.put("testedValue", jsonNumber(row.testedValue()));
        map.put("requestedPercent", row.requestedPercent()); map.put("actualPercent", jsonNumber(row.actualPercent()));
        map.put("baselineAveragePoints", jsonNumber(row.baselineAveragePoints()));
        map.put("testedAveragePoints", jsonNumber(row.testedAveragePoints()));
        map.put("pointsDelta", jsonNumber(row.pointsDelta()));
        map.put("pointsDeltaPerOnePercent", jsonNumber(row.pointsDeltaPerOnePercent()));
        map.put("confidence95", jsonNumber(row.confidence95()));
        map.put("baselineGoalsFor", jsonNumber(row.baselineGoalsFor())); map.put("testedGoalsFor", jsonNumber(row.testedGoalsFor()));
        map.put("baselineGoalsAgainst", jsonNumber(row.baselineGoalsAgainst())); map.put("testedGoalsAgainst", jsonNumber(row.testedGoalsAgainst()));
        map.put("baselineXgFor", jsonNumber(row.baselineXgFor())); map.put("testedXgFor", jsonNumber(row.testedXgFor()));
        map.put("status", row.status()); map.put("error", row.error());
        return map;
    }

    private void writeFullLeagueSummary(Path destination, List<FullLeagueRun> rows) throws IOException {
        StringBuilder csv = new StringBuilder("weightKey,category,consumer,baselineValue,testedValue,requestedPercent,actualPercent,baselineAveragePoints,testedAveragePoints,pointsDelta,baselineAveragePosition,testedAveragePosition,positionDelta,baselineTop1Pct,testedTop1Pct,baselineTop4Pct,testedTop4Pct,baselineTop6Pct,testedTop6Pct,baselineTop10Pct,testedTop10Pct,baselineBottom3Pct,testedBottom3Pct,status,error\n");
        for (FullLeagueRun row : rows) {
            DetailedLeagueCalibrationHarness.TeamSummary base = row.successful() ? row.baseline().candidate() : null;
            DetailedLeagueCalibrationHarness.TeamSummary tested = row.successful() ? row.tested().candidate() : null;
            csv.append(csv(row.weightKey())).append(',').append(row.category()).append(',').append(csv(row.consumer())).append(',')
                    .append(number(row.baselineValue())).append(',').append(number(row.testedValue())).append(',')
                    .append(number(row.requestedPercent())).append(',').append(number(row.actualPercent())).append(',')
                    .append(number(value(base, DetailedLeagueCalibrationHarness.TeamSummary::averagePoints))).append(',')
                    .append(number(value(tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePoints))).append(',')
                    .append(number(delta(base, tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePoints))).append(',')
                    .append(number(value(base, DetailedLeagueCalibrationHarness.TeamSummary::averagePosition))).append(',')
                    .append(number(value(tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePosition))).append(',')
                    .append(number(delta(base, tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePosition))).append(',')
                    .append(number(top(base, 1))).append(',').append(number(top(tested, 1))).append(',')
                    .append(number(top(base, 4))).append(',').append(number(top(tested, 4))).append(',')
                    .append(number(top(base, 6))).append(',').append(number(top(tested, 6))).append(',')
                    .append(number(top(base, 10))).append(',').append(number(top(tested, 10))).append(',')
                    .append(number(base == null ? Double.NaN : base.bottomThreePercentage())).append(',')
                    .append(number(tested == null ? Double.NaN : tested.bottomThreePercentage())).append(',')
                    .append(row.status()).append(',').append(csv(row.error())).append('\n');
        }
        writeAtomically(destination, csv.toString());
    }

    private Map<String, Object> leagueMap(FullLeagueRun row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("weightKey", row.weightKey()); map.put("category", row.category()); map.put("consumer", row.consumer());
        map.put("baselineValue", jsonNumber(row.baselineValue())); map.put("testedValue", jsonNumber(row.testedValue()));
        map.put("requestedPercent", row.requestedPercent()); map.put("actualPercent", jsonNumber(row.actualPercent()));
        map.put("status", row.status()); map.put("error", row.error());
        if (row.successful()) {
            var base = row.baseline().candidate(); var tested = row.tested().candidate();
            map.put("baselineCandidate", base); map.put("testedCandidate", tested);
            map.put("pointsDelta", tested.averagePoints() - base.averagePoints());
            map.put("positionDelta", tested.averagePosition() - base.averagePosition());
            map.put("baselineMatches", row.baseline().matches()); map.put("testedMatches", row.tested().matches());
        }
        return map;
    }

    private void writeStandings(Path destination, List<DetailedLeagueCalibrationHarness.SeasonStanding> rows) throws IOException {
        StringBuilder csv = new StringBuilder("season,position,teamIndex,teamKey,played,wins,draws,losses,goalsFor,goalsAgainst,goalDifference,points\n");
        for (var row : rows) csv.append(row.season()).append(',').append(row.position()).append(',')
                .append(row.teamIndex()).append(',').append(csv(row.teamKey())).append(',').append(row.played()).append(',')
                .append(row.wins()).append(',').append(row.draws()).append(',').append(row.losses()).append(',')
                .append(row.goalsFor()).append(',').append(row.goalsAgainst()).append(',').append(row.goalDifference()).append(',')
                .append(row.points()).append('\n');
        writeAtomically(destination, csv.toString());
    }

    private void writeTeamSummaries(Path destination,
                                    List<DetailedLeagueCalibrationHarness.TeamSummary> rows,
                                    List<Integer> buckets) throws IOException {
        StringBuilder csv = new StringBuilder("teamIndex,teamKey,averagePoints,medianPoints,pointsStdDev,minimumPoints,maximumPoints,averagePosition");
        buckets.forEach(bucket -> csv.append(",top").append(bucket).append("Pct"));
        csv.append(",bottomThreePct,averageGoalsFor,averageGoalsAgainst,averageWins,averageDraws,averageLosses\n");
        for (var row : rows) {
            csv.append(row.teamIndex()).append(',').append(csv(row.teamKey())).append(',')
                    .append(number(row.averagePoints())).append(',').append(number(row.medianPoints())).append(',')
                    .append(number(row.pointsStdDev())).append(',').append(row.minimumPoints()).append(',')
                    .append(row.maximumPoints()).append(',').append(number(row.averagePosition()));
            buckets.forEach(bucket -> csv.append(',').append(number(row.topFinishPercentages().getOrDefault(bucket, 0.0))));
            csv.append(',').append(number(row.bottomThreePercentage())).append(',').append(number(row.averageGoalsFor()))
                    .append(',').append(number(row.averageGoalsAgainst())).append(',').append(number(row.averageWins()))
                    .append(',').append(number(row.averageDraws())).append(',').append(number(row.averageLosses())).append('\n');
        }
        writeAtomically(destination, csv.toString());
    }

    private String html(RunManifest manifest,
                        DetailedLeagueCalibrationHarness.Result baseline,
                        List<SweepRow> sweep,
                        List<FullLeagueRun> leagues) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Compartment calibration</title><style>")
                .append("body{font:14px system-ui;margin:32px;color:#172033}table{border-collapse:collapse;width:100%;margin:16px 0}th,td{padding:7px;border:1px solid #d8deea;text-align:right}th:first-child,td:first-child{text-align:left}th{background:#eef2f8;position:sticky;top:0}.ok{color:#087a39}.fail{color:#b42318}code{background:#f3f5f8;padding:2px 4px}details{margin:18px 0}")
                .append("</style></head><body><h1>Compartment Engine calibration</h1>")
                .append("<p>Commit <code>").append(htmlEscape(manifest.gitCommit())).append("</code>; ")
                .append(manifest.fullLeagueSeasons()).append(" full league seasons; ")
                .append(manifest.sweepSeasons()).append(" paired sweep seasons.</p>")
                .append("<h2>Baseline mid-table team</h2>");
        var candidate = baseline.candidate();
        out.append("<p>Average points: <b>").append(fmt(candidate.averagePoints())).append("</b>; average position: <b>")
                .append(fmt(candidate.averagePosition())).append("</b>; top 10: ")
                .append(fmt(candidate.topFinishPercentages().getOrDefault(10, 0.0))).append("%; bottom three: ")
                .append(fmt(candidate.bottomThreePercentage())).append("%.</p>")
                .append("<p>This is rank-calibrated: 60 points is an observation, never a fixed target.</p>")
                .append("<h2>All-weight fast sweep</h2><table><thead><tr><th>Weight</th><th>Requested</th><th>Baseline</th><th>Tested</th><th>Points Δ</th><th>Δ / 1%</th><th>Status</th></tr></thead><tbody>");
        sweep.stream().limit(100).forEach(row -> out.append("<tr><td>").append(htmlEscape(row.weightKey())).append("</td><td>")
                .append(fmt(row.requestedPercent())).append("%</td><td>").append(fmt(row.baselineValue())).append("</td><td>")
                .append(fmt(row.testedValue())).append("</td><td>").append(fmt(row.pointsDelta())).append("</td><td>")
                .append(fmt(row.pointsDeltaPerOnePercent())).append("</td><td class=\"")
                .append(row.successful() ? "ok" : "fail").append("\">").append(htmlEscape(row.status())).append("</td></tr>"));
        out.append("</tbody></table><p>Complete data: <a href=\"weight-impact.csv\">CSV</a> / <a href=\"weight-impact.json\">JSON</a>.</p>")
                .append("<h2>Full 20-club league experiments</h2><table><thead><tr><th>Weight</th><th>Requested</th><th>Points Δ</th><th>Position Δ</th><th>Top 4 baseline</th><th>Top 4 tested</th><th>Status</th></tr></thead><tbody>");
        leagues.forEach(row -> {
            var base = row.successful() ? row.baseline().candidate() : null;
            var tested = row.successful() ? row.tested().candidate() : null;
            out.append("<tr><td>").append(htmlEscape(row.weightKey())).append("</td><td>").append(fmt(row.requestedPercent()))
                    .append("%</td><td>").append(fmt(delta(base, tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePoints)))
                    .append("</td><td>").append(fmt(delta(base, tested, DetailedLeagueCalibrationHarness.TeamSummary::averagePosition)))
                    .append("</td><td>").append(fmt(top(base, 4))).append("%</td><td>").append(fmt(top(tested, 4)))
                    .append("%</td><td class=\"").append(row.successful() ? "ok" : "fail").append("\">")
                    .append(htmlEscape(row.status())).append("</td></tr>");
        });
        return out.append("</tbody></table><p>Complete summaries: <a href=\"full-league-impact.csv\">CSV</a> / <a href=\"full-league-impact.json\">JSON</a>. Every season table is under <code>full-league-standings/</code>.</p></body></html>").toString();
    }

    private void writeJson(Path destination, Object value) throws IOException {
        writeAtomically(destination, mapper.writeValueAsString(value));
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, content);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static double value(DetailedLeagueCalibrationHarness.TeamSummary summary,
                                java.util.function.ToDoubleFunction<DetailedLeagueCalibrationHarness.TeamSummary> getter) {
        return summary == null ? Double.NaN : getter.applyAsDouble(summary);
    }

    private static double delta(DetailedLeagueCalibrationHarness.TeamSummary baseline,
                                DetailedLeagueCalibrationHarness.TeamSummary tested,
                                java.util.function.ToDoubleFunction<DetailedLeagueCalibrationHarness.TeamSummary> getter) {
        return baseline == null || tested == null ? Double.NaN : getter.applyAsDouble(tested) - getter.applyAsDouble(baseline);
    }

    private static double top(DetailedLeagueCalibrationHarness.TeamSummary summary, int bucket) {
        return summary == null ? Double.NaN : summary.topFinishPercentages().getOrDefault(bucket, Double.NaN);
    }

    private static Object jsonNumber(double value) { return Double.isFinite(value) ? value : null; }
    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.8f", value) : ""; }
    private static String fmt(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "—"; }
    private static String csv(String value) { return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"'; }
    private static String slug(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String percentSlug(double value) { return (value >= 0 ? "plus-" : "minus-") + slug(fmt(Math.abs(value))); }
    private static String htmlEscape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    public record RunManifest(String generatedAtUtc, String gitCommit, String configurationFile,
                              String configurationSha256, String weightConfigurationSha256,
                              long seed, int sweepSeasons, int fullLeagueSeasons,
                              List<Double> sweepPercentages, List<Double> fullLeaguePercentages,
                              List<String> selectedWeights, int topWeightCount) { }

    public record SweepRow(String weightKey, String category, String consumer,
                           double baselineValue, double testedValue,
                           double requestedPercent, double actualPercent,
                           double baselineAveragePoints, double testedAveragePoints,
                           double pointsDelta, double pointsDeltaPerOnePercent,
                           double confidence95,
                           double baselineGoalsFor, double testedGoalsFor,
                           double baselineGoalsAgainst, double testedGoalsAgainst,
                           double baselineXgFor, double testedXgFor,
                           String status, String error) {
        public boolean successful() { return "PASS".equals(status); }
        public double absoluteNormalizedImpact() {
            return successful() && Double.isFinite(pointsDeltaPerOnePercent)
                    ? Math.abs(pointsDeltaPerOnePercent) : -1.0;
        }
    }

    public record FullLeagueRun(String weightKey, String category, String consumer,
                                double baselineValue, double testedValue,
                                double requestedPercent, double actualPercent,
                                DetailedLeagueCalibrationHarness.Result baseline,
                                DetailedLeagueCalibrationHarness.Result tested,
                                String status, String error) {
        public boolean successful() { return "PASS".equals(status) && baseline != null && tested != null; }
    }
}
