package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompartmentMathTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();

    @Test
    void mentalitiesRedistributeMidfieldAndTransferWithoutCreatingValue() {
        for (Mentality mentality : Mentality.values()) {
            var result = CompartmentMath.redistribute(40, 20, 40, config.getMentalities().get(mentality));
            assertThat(result.attack() + result.defense())
                    .as(mentality + " conserves A+M+D")
                    .isCloseTo(100.0, within(1e-12));
        }

        var veryAttacking = CompartmentMath.redistribute(40, 20, 40,
                config.getMentalities().get(Mentality.VERY_ATTACKING));
        var balanced = CompartmentMath.redistribute(40, 20, 40,
                config.getMentalities().get(Mentality.BALANCED));
        var veryDefensive = CompartmentMath.redistribute(40, 20, 40,
                config.getMentalities().get(Mentality.VERY_DEFENSIVE));

        assertThat(veryAttacking.attack()).isGreaterThan(balanced.attack());
        assertThat(veryDefensive.defense()).isGreaterThan(balanced.defense());
        assertThat(veryAttacking.openness()).isEqualTo(1.15);
        assertThat(veryDefensive.openness()).isEqualTo(0.78);
    }
}
