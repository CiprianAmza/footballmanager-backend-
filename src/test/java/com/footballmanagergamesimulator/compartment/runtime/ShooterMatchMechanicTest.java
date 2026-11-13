package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShooterMatchMechanicTest {
    private final ShooterMatchMechanic mechanic = new ShooterMatchMechanic(new CompartmentEngineConfig());

    @Test
    void longShotsTwentyJumpsToEightyPercentAndPressingReducesEachShot() {
        assertThat(mechanic.goalChance(19, "Very Easy"))
                .isCloseTo(0.68 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(20, "Very Easy"))
                .isCloseTo(0.80 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(20, "Easy"))
                .isCloseTo(0.80 * 0.85, within(1e-12));
        assertThat(mechanic.goalChance(20, "Normal"))
                .isCloseTo(0.80 * 0.50, within(1e-12));
        assertThat(mechanic.goalChance(20, "Aggressive"))
                .isCloseTo(0.80 * 0.15, within(1e-12));
        assertThat(mechanic.goalChance(20, "Very Aggressive"))
                .isCloseTo(0.80 * 0.01, within(1e-12));
    }

    @Test
    void everyLongShotsLevelAbove20AddsFourPointsUpTo100Percent() {
        assertThat(mechanic.goalChance(21, "Very Easy")).isCloseTo(0.84 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(22, "Very Easy")).isCloseTo(0.88 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(25, "Very Easy")).isCloseTo(1.00 * 0.99, within(1e-12));
        assertThat(mechanic.goalChance(28, "Very Easy")).isCloseTo(1.00 * 0.99, within(1e-12));
    }

    @Test
    void everyPositioningLevelAbove20MovesOnePointFromZeroShotsToTwoShots() {
        // 22: 0 shots 3%, 1 shot 50%, 2 shots 24.5%.
        assertThat(mechanic.sampleShotCount(22, 0.029)).isZero();
        assertThat(mechanic.sampleShotCount(22, 0.031)).isEqualTo(1);
        assertThat(mechanic.sampleShotCount(22, 0.530)).isEqualTo(2);
        assertThat(mechanic.sampleShotCount(22, 0.774)).isEqualTo(2);
        assertThat(mechanic.sampleShotCount(22, 0.776)).isEqualTo(3);
        // 25 and above: never zero shots.
        assertThat(mechanic.sampleShotCount(25, 0.0)).isEqualTo(1);
        assertThat(mechanic.sampleShotCount(30, 0.0)).isEqualTo(1);
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
