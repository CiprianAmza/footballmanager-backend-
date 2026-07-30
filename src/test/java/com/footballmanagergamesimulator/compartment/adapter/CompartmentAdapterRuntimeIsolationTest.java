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

/** Static architecture checks for the authoritative canonical scoring boundary. */
class CompartmentAdapterRuntimeIsolationTest {

    private static final List<String> PHASE6_FORBIDDEN_REFERENCES = List.of(
            "Repository", "jakarta.persistence", "org.springframework", "Human", "PlayerSkills",
            "FormationData", "MatchPlan", "TacticalScoreService", "MatchRoundSimulator", "LiveMatch",
            "java.util.Random", "ThreadLocalRandom");

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
    void productionContainsNoShadowScoringFrontier() {
        Path root = Path.of("src", "main", "java");
        Path simulator = root.resolve("com/footballmanagergamesimulator/service/MatchRoundSimulator.java");
        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        if (containsIdentifier(read(p), "CompartmentShadowEvaluationService")) {
                            offenders.computeIfAbsent(root.relativize(p).toString(), k -> new java.util.ArrayList<>())
                                    .add("CompartmentShadowEvaluationService");
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(offenders).as("the retired shadow scorer must not exist in production").isEmpty();
        assertThat(read(simulator))
                .doesNotContain("CompartmentShadowEvaluationService", "scoreSafely",
                        "TWO_AXIS_FALLBACK", "SCALAR_FALLBACK");
    }

    @Test
    void authoritativeRuntimeHasNoFallbackCutover() {
        String simulator = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchRoundSimulator.java"));
        assertThat(simulator)
                .contains("canonicalScoreForHumanFixture")
                .contains("canonicalScoreForAiFixture")
                .contains("canonicalRuntimeScoringService.score(")
                .doesNotContain("scoreSafely", "TWO_AXIS_FALLBACK", "SCALAR_FALLBACK",
                        "twoAxisScores(", "scoreStandaloneMatch(", "CompartmentShadowEvaluationService");
    }

    @Test
    void adminOverrideLockOrderIsDiscoveryThenAdoptionThenFixtureThenClaim() {
        String simulator = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchRoundSimulator.java"));
        int aiStart = simulator.indexOf("aiMatches++");
        int discovery = simulator.indexOf("readPredeterminedScore(", aiStart);
        int persist = simulator.indexOf("persistOrLoadScoreDecision(", aiStart);
        int durableLock = simulator.indexOf("lockFixture(aiFixtureKey)", persist);
        int durableClaim = simulator.indexOf("consumeMatchingAdminOverride(", durableLock);
        assertThat(discovery).isGreaterThanOrEqualTo(0).isLessThan(persist);
        assertThat(persist).isLessThan(durableLock);
        assertThat(durableLock).isLessThan(durableClaim);

        int matchPlanOff = simulator.indexOf("if (!matchPlanService.isEnabled())", discovery);
        int offClaim = simulator.indexOf("consumeMatchingAdminOverride(", matchPlanOff);
        int knockout = simulator.indexOf("if (knockout && persistedScoreDecision.isEmpty())", matchPlanOff);
        assertThat(matchPlanOff).isGreaterThanOrEqualTo(0);
        assertThat(offClaim).isGreaterThanOrEqualTo(0).isLessThan(knockout);
    }

    @Test
    void durableAiStatsUseCanonicalProjectionAndLegacyStatsStayBehindFallback() {
        String simulator = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchRoundSimulator.java"));
        int durable = simulator.indexOf("if (durablePlan)");
        int canonicalStats = simulator.indexOf("generateAndSaveCanonicalMatchStats");
        int legacyStats = simulator.indexOf("generateAndSaveMatchStats", canonicalStats);
        assertThat(durable).isGreaterThanOrEqualTo(0);
        assertThat(canonicalStats).isGreaterThan(durable);
        assertThat(legacyStats).isGreaterThan(canonicalStats);
        assertThat(simulator).contains("new CanonicalMatchEffectsInput")
                .contains("canonicalEvents.stream()")
                .contains("if (durablePlan)")
                .contains("else {");
        assertThat(count(simulator, "generateAndSaveCanonicalMatchStats")).isEqualTo(1);
        String statsService = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchStatsService.java"));
        int canonicalApi = statsService.indexOf("generateAndSaveCanonicalMatchStats");
        int canonicalBodyEnd = statsService.indexOf("private MatchStats generateCanonicalMatchStats", canonicalApi);
        String canonicalApiSource = statsService.substring(canonicalApi, canonicalBodyEnd);
        assertThat(statsService).contains("CanonicalMatchEffectsInput input,")
                .contains("CanonicalMatchStatsProfileV1.v1()");
        assertThat(canonicalApiSource).doesNotContain("homePower", "awayPower", "PersonalizedTactic");
    }

    @Test
    void knockoutReplayDelegatesToPureResolverAndUsesQualifiedSeasonLookup() {
        String simulator = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "MatchRoundSimulator.java"));
        assertThat(simulator).contains("KnockoutReplayResolver.resolve")
                .contains("findByCompetitionIdAndSeasonNumberAndTieIdAndLegNumber");

        String resolver = read(Path.of("src", "main", "java", "com", "footballmanagergamesimulator",
                "service", "KnockoutReplayResolver.java"));
        assertThat(resolver).doesNotContain("Random", "threadRandom", "decideTie", "sampling");
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
