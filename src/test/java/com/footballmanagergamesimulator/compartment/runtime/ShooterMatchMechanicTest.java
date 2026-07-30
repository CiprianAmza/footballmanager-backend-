package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShooterMatchMechanicTest {
    private final ShooterMatchMechanic mechanic = new ShooterMatchMechanic(new CompartmentEngineConfig());

    @Test
    void twentyIsADiscontinuousLongShotsSuperpowerAndPressingReducesEachShot() {
        assertThat(mechanic.goalChance(19, "Very Easy"))
                .isCloseTo(0.68 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(20, "Very Easy"))
                .isCloseTo(0.99, within(1e-12));
        assertThat(mechanic.goalChance(20, "Easy"))
                .isCloseTo(0.85, within(1e-12));
        assertThat(mechanic.goalChance(20, "Normal"))
                .isCloseTo(0.50, within(1e-12));
        assertThat(mechanic.goalChance(20, "Aggressive"))
                .isCloseTo(0.15, within(1e-12));
        assertThat(mechanic.goalChance(20, "Very Aggressive"))
                .isCloseTo(0.01, within(1e-12));
    }

    @Test
    void positioningTwentyUsesTheExceptionalShotCountDistribution() {
        assertThat(mechanic.sampleShotCount(19, 0.060)).isZero();
        assertThat(mechanic.sampleShotCount(20, 0.060)).isEqualTo(1);
        assertThat(mechanic.sampleShotCount(20, 0.049)).isZero();
        assertThat(mechanic.sampleShotCount(20, 0.050)).isEqualTo(1);
        assertThat(mechanic.sampleShotCount(20, 0.549)).isEqualTo(1);
        assertThat(mechanic.sampleShotCount(20, 0.550)).isEqualTo(2);
        assertThat(mechanic.sampleShotCount(20, 0.999)).isEqualTo(5);
    }

    @Test
    void pressingUsesTheAgreedRedCardTradeOff() {
        assertThat(mechanic.redCard("Very Easy", 0.0)).isFalse();
        assertThat(mechanic.redCard("Easy", 0.0249)).isTrue();
        assertThat(mechanic.redCard("Easy", 0.025)).isFalse();
        assertThat(mechanic.redCard("Normal", 0.0999)).isTrue();
        assertThat(mechanic.redCard("Aggressive", 0.2999)).isTrue();
        assertThat(mechanic.redCard("Very Aggressive", 0.3999)).isTrue();
        assertThat(mechanic.redCard("Very Aggressive", 0.40)).isFalse();
    }
}
