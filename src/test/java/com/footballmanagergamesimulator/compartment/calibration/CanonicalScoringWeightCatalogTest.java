package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.calibration.CanonicalScoringWeightCatalog;
import com.footballmanagergamesimulator.compartment.calibration.CanonicalScoringWeightKey;
import com.footballmanagergamesimulator.compartment.calibration.CanonicalScoringWeightOverride;
import com.footballmanagergamesimulator.compartment.calibration.CanonicalScoringWeightSet;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalScoringWeightCatalogTest {
    @Test
    void catalogIsStableAndContainsContextAndRoleWeights() {
        var catalog = CanonicalScoringWeightCatalog.from(CompartmentConfigFixture.load(), new MatchEngineConfig());
        assertThat(catalog.size()).isGreaterThan(30);
        assertThat(catalog.leafWeights()).isSortedAccordingTo(java.util.Comparator.comparing(CanonicalScoringWeightKey::path));
        assertThat(catalog.get("compartment.context-rules.line:high.PACE")).isNotNull();
        assertThat(catalog.get("match.role-weights.suitability-scale")).isNotNull();
    }

    @Test
    void overrideDoesNotMutateBaseline() {
        var baseline = CompartmentConfigFixture.load();
        var match = new MatchEngineConfig();
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
