package com.footballmanagergamesimulator.compartment.calibration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Deterministic CSV/JSON writer constrained to target/compartment-calibration. */
public final class ScoringSensitivityReportWriter {
    private static final String HEADER = "scenarioId,seed,seasons,matches,weightKey,baselineValue,testedValue,baselineAveragePoints,testedAveragePoints,pointsDelta,baselineGoalsFor,testedGoalsFor,baselineGoalsAgainst,testedGoalsAgainst,baselineXgFor,testedXgFor,baselineXgAgainst,testedXgAgainst,wins,draws,losses,attack,midfield,defense,attackProtection,confidenceInterval,sampleCount,baselineFingerprint,testedFingerprint,baselineAttack,testedAttack,baselineMidfield,testedMidfield,baselineDefense,testedDefense,baselineAttackProtection,testedAttackProtection,baselineHomeXg,testedHomeXg,baselineAwayXg,testedAwayXg,baselineHomeWinProbability,testedHomeWinProbability,baselineDrawProbability,testedDrawProbability,baselineAwayWinProbability,testedAwayWinProbability,pmfL1Delta,candidateRole,liveSelectable\n";

    public void write(Path target, List<ScoringSensitivityResult> input) throws IOException {
        write(target, "sensitivity", input);
    }

    /** Writes a named report so selected, baseline, and shards cannot collide. */
    public void write(Path target, String reportName, List<ScoringSensitivityResult> input) throws IOException {
        if (reportName == null || !reportName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid report name");
        }
        Path root = Path.of("target", "compartment-calibration").toAbsolutePath().normalize();
        Path destination = (target == null ? root : target.toAbsolutePath().normalize());
        if (!destination.startsWith(root)) throw new IllegalArgumentException("reports must stay under target/compartment-calibration");
        Files.createDirectories(destination);
        List<ScoringSensitivityResult> results = input.stream().sorted(Comparator.comparingDouble((ScoringSensitivityResult r) -> Math.abs(r.pointsDelta())).reversed().thenComparing(ScoringSensitivityResult::weightKey)).toList();
        StringBuilder csv = new StringBuilder(HEADER);
        for (ScoringSensitivityResult r : results) csv.append(r.scenarioId()).append(',').append(r.seed()).append(',').append(r.seasons()).append(',').append(r.matches()).append(',').append(r.weightKey()).append(',').append(r.baselineValue()).append(',').append(r.testedValue()).append(',').append(r.baselineAveragePoints()).append(',').append(r.testedAveragePoints()).append(',').append(r.pointsDelta()).append(',').append(r.baselineGoalsFor()).append(',').append(r.testedGoalsFor()).append(',').append(r.baselineGoalsAgainst()).append(',').append(r.testedGoalsAgainst()).append(',').append(r.baselineXgFor()).append(',').append(r.testedXgFor()).append(',').append(r.baselineXgAgainst()).append(',').append(r.testedXgAgainst()).append(',').append(r.wins()).append(',').append(r.draws()).append(',').append(r.losses()).append(',').append(r.attack()).append(',').append(r.midfield()).append(',').append(r.defense()).append(',').append(r.attackProtection()).append(',').append(r.confidenceInterval()).append(',').append(r.sampleCount()).append(',').append(r.baselineFingerprint()).append(',').append(r.testedFingerprint()).append(',').append(r.baselineAttack()).append(',').append(r.testedAttack()).append(',').append(r.baselineMidfield()).append(',').append(r.testedMidfield()).append(',').append(r.baselineDefense()).append(',').append(r.testedDefense()).append(',').append(r.baselineAttackProtection()).append(',').append(r.testedAttackProtection()).append(',').append(r.baselineHomeXg()).append(',').append(r.testedHomeXg()).append(',').append(r.baselineAwayXg()).append(',').append(r.testedAwayXg()).append(',').append(r.baselineHomeWinProbability()).append(',').append(r.testedHomeWinProbability()).append(',').append(r.baselineDrawProbability()).append(',').append(r.testedDrawProbability()).append(',').append(r.baselineAwayWinProbability()).append(',').append(r.testedAwayWinProbability()).append(',').append(r.pmfL1Delta()).append(',').append(r.candidateRole()).append(',').append(r.liveSelectable()).append('\n');
        writeAtomically(destination.resolve(reportName + ".csv"), csv.toString());
        String json = results.stream().map(r -> "{\"scenarioId\":\"" + escape(r.scenarioId())
                + "\",\"seed\":" + r.seed() + ",\"seasons\":" + r.seasons() + ",\"matches\":" + r.matches()
                + ",\"weightKey\":\"" + escape(r.weightKey()) + "\",\"baselineValue\":" + r.baselineValue() + ",\"testedValue\":" + r.testedValue()
                + ",\"baselineAveragePoints\":" + r.baselineAveragePoints() + ",\"testedAveragePoints\":" + r.testedAveragePoints() + ",\"pointsDelta\":" + r.pointsDelta()
                + ",\"baselineGoalsFor\":" + r.baselineGoalsFor() + ",\"testedGoalsFor\":" + r.testedGoalsFor()
                + ",\"baselineGoalsAgainst\":" + r.baselineGoalsAgainst() + ",\"testedGoalsAgainst\":" + r.testedGoalsAgainst()
                + ",\"baselineXgFor\":" + r.baselineXgFor() + ",\"testedXgFor\":" + r.testedXgFor()
                + ",\"baselineXgAgainst\":" + r.baselineXgAgainst() + ",\"testedXgAgainst\":" + r.testedXgAgainst()
                + ",\"wins\":" + r.wins() + ",\"draws\":" + r.draws() + ",\"losses\":" + r.losses()
                + ",\"attack\":" + r.attack() + ",\"midfield\":" + r.midfield() + ",\"defense\":" + r.defense()
                + ",\"attackProtection\":" + r.attackProtection() + ",\"confidenceInterval\":" + r.confidenceInterval()
                + ",\"sampleCount\":" + r.sampleCount() + ",\"baselineFingerprint\":\"" + escape(r.baselineFingerprint())
                + "\",\"testedFingerprint\":\"" + escape(r.testedFingerprint()) + "\",\"baselineAttack\":" + r.baselineAttack()
                + ",\"testedAttack\":" + r.testedAttack() + ",\"baselineMidfield\":" + r.baselineMidfield()
                + ",\"testedMidfield\":" + r.testedMidfield() + ",\"baselineDefense\":" + r.baselineDefense()
                + ",\"testedDefense\":" + r.testedDefense() + ",\"baselineAttackProtection\":" + r.baselineAttackProtection()
                + ",\"testedAttackProtection\":" + r.testedAttackProtection() + ",\"baselineHomeXg\":" + r.baselineHomeXg()
                + ",\"testedHomeXg\":" + r.testedHomeXg() + ",\"baselineAwayXg\":" + r.baselineAwayXg()
                + ",\"testedAwayXg\":" + r.testedAwayXg() + ",\"baselineHomeWinProbability\":" + r.baselineHomeWinProbability()
                + ",\"testedHomeWinProbability\":" + r.testedHomeWinProbability() + ",\"baselineDrawProbability\":" + r.baselineDrawProbability()
                + ",\"testedDrawProbability\":" + r.testedDrawProbability() + ",\"baselineAwayWinProbability\":" + r.baselineAwayWinProbability()
                + ",\"testedAwayWinProbability\":" + r.testedAwayWinProbability() + ",\"pmfL1Delta\":" + r.pmfL1Delta()
                + ",\"candidateRole\":" + r.candidateRole() + ",\"liveSelectable\":" + r.liveSelectable() + "}").collect(java.util.stream.Collectors.joining(",", "[", "]"));
        writeAtomically(destination.resolve(reportName + ".json"), json);
    }

    private static void writeAtomically(Path destination, String content) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, content);
        try {
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
