package com.footballmanagergamesimulator.compartment.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative audit: no production source <em>outside</em> the {@code compartment} package may reference
 * the pure adapter or the pure calculators/formulas. This guarantees Phase&nbsp;2 adds no runtime
 * wiring into any match/scoring path &mdash; the adapter remains reachable only from tests.
 */
class CompartmentAdapterRuntimeIsolationTest {

    private static final List<String> GUARDED_TYPES = List.of(
            "CompartmentDomainAdapter",
            "DomainSnapshotFactory",
            "DomainPlayerSnapshot",
            "PlayerAttributeMapping",
            "ContextCoefficientMapper",
            "TacticalContextInput",
            "ContextCoefficientMapping",
            "ContextualPlayerRatingCalculator",
            "DefensiveExposureFormula",
            "GoalProbabilityFormula",
            "CompartmentMath",
            "CanonicalMatchEvaluation",
            "CanonicalMatchEvaluationAdapter",
            "OutcomeProbability",
            "MatchVenue");

    private static final List<String> PHASE6_FORBIDDEN_REFERENCES = List.of(
            "Repository", "jakarta.persistence", "org.springframework", "Human", "PlayerSkills",
            "FormationData", "MatchPlan", "TacticalScoreService", "MatchRoundSimulator", "LiveMatch",
            "java.util.Random", "ThreadLocalRandom");

    @Test
    void noRuntimeCallsiteReferencesTheAdapterOrPureCalculators() {
        Path root = Path.of("src", "main", "java");
        assertThat(root).exists();

        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/compartment/"))
                    .forEach(p -> {
                        String content = read(p);
                        for (String type : GUARDED_TYPES) {
                            if (containsIdentifier(content, type)) {
                                offenders.computeIfAbsent(root.relativize(p).toString(), k -> new java.util.ArrayList<>())
                                        .add(type);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(offenders)
                .as("no production file outside the compartment package may reference the adapter/calculators")
                .isEmpty();
    }

    @Test
    void phaseSixBridgeRemainsDomainFreeAndRuntimeIsolated() {
        Path root = Path.of("src", "main", "java", "com", "footballmanagergamesimulator", "compartment", "adapter");
        List<String> phase6Files = List.of(
                "PlayerCapabilityResolver.java", "CanonicalLineupPlayer.java", "CanonicalPlayerEvaluation.java",
                "CanonicalTeamEvaluation.java", "CanonicalPlayerContextAdapter.java",
                "CanonicalTeamEvaluationAdapter.java");
        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> phase6Files.contains(path.getFileName().toString()))
                    .forEach(path -> {
                        String content = read(path);
                        for (String forbidden : PHASE6_FORBIDDEN_REFERENCES) {
                            if (containsIdentifier(content, forbidden) || content.contains(forbidden)) {
                                offenders.computeIfAbsent(root.relativize(path).toString(), k -> new java.util.ArrayList<>())
                                        .add(forbidden);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(offenders).as("Phase 6 bridge must remain pure and domain-free").isEmpty();
    }

    @Test
    void phaseNineAllowsOnlyTheShadowServiceFrontier() {
        Path root = Path.of("src", "main", "java");
        Path simulator = root.resolve("com/footballmanagergamesimulator/service/MatchRoundSimulator.java");
        assertThat(read(simulator)).contains("CompartmentShadowEvaluationService")
                .contains("evaluateSafely(() ->");
        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(simulator))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/compartment/"))
                    .forEach(p -> {
                        if (containsIdentifier(read(p), "CompartmentShadowEvaluationService")) {
                            offenders.computeIfAbsent(root.relativize(p).toString(), k -> new java.util.ArrayList<>())
                                    .add("CompartmentShadowEvaluationService");
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(offenders).as("only MatchRoundSimulator may reference the shadow service").isEmpty();
        String simulatorSource = read(simulator);
        for (String forbidden : List.of("CanonicalMatchEvaluationAdapter", "CanonicalRuntimeInputFactory",
                "CanonicalTeamEvaluationAdapter", "GoalProbabilityFormula", "PlayerCapabilityService")) {
            assertThat(containsIdentifier(simulatorSource, forbidden))
                    .as("MatchRoundSimulator must not reference " + forbidden).isFalse();
        }
    }

    @Test
    void phaseElevenHasOneAiCutoverHookAndLeavesLegacyBoundariesInPlace() {
        String simulator = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchRoundSimulator.java"));
        assertThat(count(simulator, "canonicalRuntimeScoringService.scoreSafely")).isEqualTo(1);

        int aiStart = simulator.indexOf("aiMatches++");
        int admin = simulator.indexOf("consumePredeterminedScore(_competitionId, (int) _roundId, teamId1, teamId2", aiStart);
        int cutover = simulator.indexOf("canonicalRuntimeScoringService.scoreSafely");
        int twoAxisFallback = simulator.indexOf("TwoAxisResult r = twoAxisScores(teamId1, null, teamId2, null)", cutover);
        assertThat(admin).isGreaterThanOrEqualTo(0).isLessThan(cutover);
        assertThat(twoAxisFallback).isGreaterThan(cutover);
        assertThat(simulator).contains("compartmentEngineConfig.isShadowEnabled() && !compartmentEngineConfig.isEnabled()");
        int humanStart = simulator.indexOf("if (isHumanMatch)");
        assertThat(humanStart).isGreaterThanOrEqualTo(0).isLessThan(cutover);
        assertThat(simulator.substring(humanStart, cutover)).doesNotContain("canonicalRuntimeScoringService");

        int standalone = simulator.indexOf("public MatchOutcome scoreStandaloneMatch");
        assertThat(standalone).isGreaterThanOrEqualTo(0);
        assertThat(simulator.substring(standalone)).doesNotContain("canonicalRuntimeScoringService");
        assertThat(simulator).contains("if (isHumanMatch)").contains("if (knockout)");
    }

    private static boolean containsIdentifier(String content, String identifier) {
        int from = 0;
        while (true) {
            int idx = content.indexOf(identifier, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isIdentifierPart(content.charAt(idx - 1));
            int end = idx + identifier.length();
            boolean rightOk = end >= content.length() || !isIdentifierPart(content.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static int count(String content, String value) {
        int count = 0;
        int from = 0;
        while ((from = content.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
