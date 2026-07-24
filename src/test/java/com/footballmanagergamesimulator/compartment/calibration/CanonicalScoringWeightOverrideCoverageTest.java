package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoringFingerprintService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoringWeightOverrideCoverageTest {
    @Test
    void everyNumericLeafIsApplicableWithoutMutatingBaseline() {
        var profile = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match());
        var service = new CanonicalScoringFingerprintService();
        String original = service.configFingerprint(profile.compartment(), profile.match());
        for (CanonicalScoringWeightKey leaf : catalog.leafWeights()) {
            if (leaf.type() == CanonicalScoringWeightKey.Type.DISCRETE) continue;
            double tested = CanonicalWeightPerturbation.validAlternative(leaf);
            CanonicalScoringWeightSet set = CanonicalScoringWeightSet.baseline(profile.compartment(), profile.match())
                    .override(catalog, new CanonicalScoringWeightOverride(leaf.path(), tested));
            assertThat(service.configFingerprint(profile.compartment(), profile.match())).as(leaf.path()).isEqualTo(original);
            assertThat(service.configFingerprint(set.compartment(), set.match())).as(leaf.path()).isNotEqualTo(original);
        }
    }
}
