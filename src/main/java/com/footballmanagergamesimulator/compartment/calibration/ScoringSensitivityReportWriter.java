package com.footballmanagergamesimulator.compartment.calibration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Deterministic CSV/JSON writer constrained to target/compartment-calibration. */
public final class ScoringSensitivityReportWriter {
    private static final String HEADER = "weightKey,baselineValue,testedValue,averagePoints,pointsDelta,goalsFor,goalsAgainst,xgFor,xgAgainst,wins,draws,losses,attack,midfield,defense,attackProtection,confidenceInterval,sampleCount\n";

    public void write(Path target, List<ScoringSensitivityResult> input) throws IOException {
        Path root = Path.of("target", "compartment-calibration").toAbsolutePath().normalize();
        Path destination = (target == null ? root : target.toAbsolutePath().normalize());
        if (!destination.startsWith(root)) throw new IllegalArgumentException("reports must stay under target/compartment-calibration");
        Files.createDirectories(destination);
        List<ScoringSensitivityResult> results = input.stream().sorted(Comparator.comparingDouble((ScoringSensitivityResult r) -> Math.abs(r.pointsDelta())).reversed().thenComparing(ScoringSensitivityResult::weightKey)).toList();
        StringBuilder csv = new StringBuilder(HEADER);
        for (ScoringSensitivityResult r : results) csv.append(r.weightKey()).append(',').append(r.baselineValue()).append(',').append(r.testedValue()).append(',').append(r.averagePoints()).append(',').append(r.pointsDelta()).append(',').append(r.goalsFor()).append(',').append(r.goalsAgainst()).append(',').append(r.xgFor()).append(',').append(r.xgAgainst()).append(',').append(r.wins()).append(',').append(r.draws()).append(',').append(r.losses()).append(',').append(r.attack()).append(',').append(r.midfield()).append(',').append(r.defense()).append(',').append(r.attackProtection()).append(',').append(r.confidenceInterval()).append(',').append(r.sampleCount()).append('\n');
        Files.writeString(destination.resolve("sensitivity.csv"), csv);
        String json = results.stream().map(r -> "{\"weightKey\":\"" + escape(r.weightKey())
                + "\",\"baselineValue\":" + r.baselineValue() + ",\"testedValue\":" + r.testedValue()
                + ",\"averagePoints\":" + r.averagePoints() + ",\"pointsDelta\":" + r.pointsDelta()
                + ",\"goalsFor\":" + r.goalsFor() + ",\"goalsAgainst\":" + r.goalsAgainst()
                + ",\"xgFor\":" + r.xgFor() + ",\"xgAgainst\":" + r.xgAgainst()
                + ",\"wins\":" + r.wins() + ",\"draws\":" + r.draws() + ",\"losses\":" + r.losses()
                + ",\"sampleCount\":" + r.sampleCount() + "}").collect(java.util.stream.Collectors.joining(",", "[", "]"));
        Files.writeString(destination.resolve("sensitivity.json"), json);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
