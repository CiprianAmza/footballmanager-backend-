package com.footballmanagergamesimulator.compartment.calibration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoringWeightOverrideExactnessTest {
    @Test
    void everyNumericOverrideChangesOnlyItsCanonicalLeaf() {
        var profile = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match());

        for (var leaf : catalog.leafWeights()) {
            var baseline = CanonicalScoringWeightSet.baseline(profile.compartment(), profile.match());
            var tested = baseline.override(catalog,
                    new CanonicalScoringWeightOverride(leaf.path(), CanonicalWeightPerturbation.validAlternative(leaf)));
            var before = values(CanonicalScoringWeightCatalog.from(baseline.compartment(), baseline.match()));
            var after = values(CanonicalScoringWeightCatalog.from(tested.compartment(), tested.match()));
            var changed = before.keySet().stream()
                    .filter(path -> !java.util.Objects.equals(before.get(path), after.get(path)))
                    .toList();

            assertThat(changed).as("override %s", leaf.path()).containsExactly(leaf.path());
            if (leaf.path().matches("compartment\\.mentalities\\.[^.]+\\.midfield-to-attack")) {
                var mentality = com.footballmanagergamesimulator.compartment.Mentality.valueOf(leaf.path().split("\\.")[2]);
                assertThat(tested.compartment().getMentalities().get(mentality).getMidfieldToDefense())
                        .as("derived constrained companion for %s", leaf.path())
                        .isNotEqualTo(baseline.compartment().getMentalities().get(mentality).getMidfieldToDefense());
            }
        }
    }

    private static Map<String, Object> values(CanonicalScoringWeightCatalog catalog) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var leaf : catalog.leafWeights()) values.put(leaf.path(), leaf.baselineValue());
        return values;
    }
}
