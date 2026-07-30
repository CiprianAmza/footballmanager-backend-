package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.Map;

class CanonicalScoringWeightCatalogTest {
    @Test
    void catalogIsStableAndContainsContextAndRoleWeights() {
        var config = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(config.compartment(), config.match());
        assertThat(catalog.size()).isEqualTo(727);
        assertThat(catalog.leafWeights()).isSortedAccordingTo(java.util.Comparator.comparing(CanonicalScoringWeightKey::path));
        assertThat(catalog.get("compartment.context-rules.linehigh.PACE")).isNotNull();
        assertThat(catalog.get("match.role-weights.suitability-scale")).isNotNull();
        assertThat(catalog.leafWeights()).noneMatch(key -> key.path().startsWith("match.instruction-weights."));
        assertThat(catalog.leafWeights()).noneMatch(key -> key.path().equals("match.role-weights.overall-blend"));
        assertThat(catalog.leafWeights()).noneMatch(key -> key.path().startsWith("match.player-value.weights."));
        assertThat(catalog.leafWeights()).noneMatch(key -> key.path().equals("match.player-value.morale-slope"));
    }

    @Test
    void overrideDoesNotMutateBaseline() {
        var config = CalibrationConfigFixture.load();
        var baseline = config.compartment();
        var match = config.match();
        var set = CanonicalScoringWeightSet.baseline(baseline, match)
                .override(CanonicalScoringWeightCatalog.from(baseline, match),
                        new CanonicalScoringWeightOverride("match.role-weights.suitability-scale", 6.0));
        assertThat(set.match().getRoleWeights().getSuitabilityScale()).isEqualTo(6.0);
        assertThat(match.getRoleWeights().getSuitabilityScale()).isEqualTo(5.0);
    }

    @Test
    void invalidOverrideIsRejected() {
        assertThatThrownBy(() -> new CanonicalScoringWeightOverride("x", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baselineCopiesAllRatingSentinelsBeforeApplyingOneOverride() {
        var config = CalibrationConfigFixture.load();
        var rating = config.compartment().getRating();
        rating.setRoleFitBase(0.71);
        rating.setRoleFitRange(0.19);
        rating.setFitnessFloor(0.83);
        rating.setMoraleNeutral(61.0);
        rating.setMoraleSlope(0.0011);
        rating.setDefaultPositionMultiplier(0.77);
        rating.setDefaultRoleMultiplier(1.23);
        rating.setExceptionalAttributeValue(20);
        config.compartment().getShooter().setRegularLongShotsCeiling(0.63);
        config.compartment().getShooter().getPressing().get("VeryEasy").setShotReduction(0.07);
        var set = CanonicalScoringWeightSet.baseline(config.compartment(), config.match())
                .override(CanonicalScoringWeightCatalog.from(config.compartment(), config.match()),
                        new CanonicalScoringWeightOverride("compartment.rating.score-scale", 101.0));
        assertThat(set.compartment().getRating().getRoleFitBase()).isEqualTo(0.71);
        assertThat(set.compartment().getRating().getRoleFitRange()).isEqualTo(0.19);
        assertThat(set.compartment().getRating().getFitnessFloor()).isEqualTo(0.83);
        assertThat(set.compartment().getRating().getMoraleNeutral()).isEqualTo(61.0);
        assertThat(set.compartment().getRating().getMoraleSlope()).isEqualTo(0.0011);
        assertThat(set.compartment().getRating().getDefaultPositionMultiplier()).isEqualTo(0.77);
        assertThat(set.compartment().getRating().getDefaultRoleMultiplier()).isEqualTo(1.23);
        assertThat(set.compartment().getRating().getExceptionalAttributeValue()).isEqualTo(20);
        assertThat(set.compartment().getShooter().getRegularLongShotsCeiling()).isEqualTo(0.63);
        assertThat(set.compartment().getShooter().getPressing().get("VeryEasy").getShotReduction())
                .isEqualTo(0.07);
        assertThat(set.compartment().getRating().getScoreScale()).isEqualTo(101.0);
        assertThat(config.compartment().getRating().getScoreScale()).isEqualTo(100.0);
    }

    @Test
    void activeYamlLeavesMatchCatalogExactly() throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = new ClassPathResource("compartment-scoring-weights-v1.yml").getInputStream()) {
            flatten(new Yaml().load(stream), "", names);
        }
        assertUniqueNormalizedPaths(names);
        var profile = CalibrationConfigFixture.load();
        Set<String> catalog = CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match()).leafWeights().stream()
                .map(CanonicalScoringWeightKey::path).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertThat(new java.util.TreeSet<>(names)).containsExactlyElementsOf(catalog);
    }

    @Test
    void duplicatePathsAreRejectedBeforeSetConversion() {
        assertThatThrownBy(() -> assertUniqueNormalizedPaths(List.of(
                "compartment.context-rules.instruction:close down less.POSITIONING",
                "compartment.context-rules.instructionclosedownless.POSITIONING")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distinctContextCollisionIsRejectedByCatalogConstruction() {
        var profile = CalibrationConfigFixture.load();
        Map<String, Map<com.footballmanagergamesimulator.compartment.PlayerAttribute, Double>> rules =
                new LinkedHashMap<>(profile.compartment().getContextRules());
        rules.put("instruction:closedownless", Map.of(
                com.footballmanagergamesimulator.compartment.PlayerAttribute.POSITIONING, 0.99));
        profile.compartment().setContextRules(rules);

        assertThatThrownBy(() -> CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous context rule aliases");
    }

    private static void flatten(Object value, String path, List<String> output) {
        if (value instanceof java.util.Map<?, ?> map) {
            map.forEach((key, child) -> flatten(child, path.isEmpty() ? String.valueOf(key) : path + "." + key, output));
            return;
        }
        String normalized = path.replaceFirst("^match\\.engine\\.compartment\\.", "compartment.")
                .replaceFirst("^match\\.engine\\.player-value\\.", "match.player-value.")
                .replaceFirst("^match\\.engine\\.role-weights\\.", "match.role-weights.");
        if (normalized.startsWith("compartment.context-rules.")) {
            int start = "compartment.context-rules.".length();
            int end = normalized.indexOf('.', start);
            if (end > start) normalized = normalized.substring(0, start)
                    + normalized.substring(start, end).replace(" ", "").replace(":", "") + normalized.substring(end);
        }
        boolean nonNumeric = normalized.contains(".ignores-defensive-instructions")
                || normalized.contains(".forced-defensive-morale-delta")
                || normalized.endsWith(".transfer-from") || normalized.endsWith(".transfer-to")
                || normalized.startsWith("compartment.roles.SHADOW_STRIKER.")
                // Discontinuous SHOOTER events are verified by their dedicated probability
                // tests and runtime fingerprint, not by the collective continuous sweep.
                || normalized.startsWith("compartment.shooter.")
                || normalized.equals("compartment.rating.exceptional-attribute-value")
                || normalized.equals("compartment.probability.extra-time-scale");
        if (normalized.matches("compartment\\.mentalities\\.[^.]+\\.midfield-to-defense")) return;
        if (normalized.equals("compartment.work-rate.instructions.STAY_FORWARD.engagement")) return;
        if (!nonNumeric) output.add(normalized);
    }

    private static void assertUniqueNormalizedPaths(List<String> paths) {
        Set<String> seen = new java.util.HashSet<>();
        for (String path : paths) {
            String normalized = path;
            if (normalized.startsWith("compartment.context-rules.")) {
                int start = "compartment.context-rules.".length();
                int end = normalized.indexOf('.', start);
                normalized = normalized.substring(0, start)
                        + normalized.substring(start, end).replace(" ", "").replace(":", "")
                        + normalized.substring(end);
            }
            if (!seen.add(normalized)) throw new IllegalArgumentException("duplicate YAML leaf: " + path);
        }
    }
}
