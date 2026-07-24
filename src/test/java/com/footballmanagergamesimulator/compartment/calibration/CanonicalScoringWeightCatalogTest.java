package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalScoringWeightCatalogTest {
    @Test
    void catalogIsStableAndContainsContextAndRoleWeights() {
        var config = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(config.compartment(), config.match());
        assertThat(catalog.size()).isGreaterThan(30);
        assertThat(catalog.leafWeights()).isSortedAccordingTo(java.util.Comparator.comparing(CanonicalScoringWeightKey::path));
        assertThat(catalog.get("compartment.context-rules.line:high.PACE")).isNotNull();
        assertThat(catalog.get("match.role-weights.suitability-scale")).isNotNull();
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
}
