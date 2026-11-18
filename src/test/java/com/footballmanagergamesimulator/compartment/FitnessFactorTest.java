package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FitnessFactorTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();

    @Test
    void shippedCurveIsFullAtFreshAndAboveAndFallsLinearlyToTheFloor() {
        assertThat(config.getRating().getFitnessFresh()).isEqualTo(80.0);
        assertThat(config.getRating().getFitnessSensitivity()).isEqualTo(0.75);
        assertThat(config.getRating().getFitnessFloor()).isEqualTo(0.60);

        assertThat(FitnessFactor.of(100, config)).isEqualTo(1.0);
        assertThat(FitnessFactor.of(80, config)).isEqualTo(1.0);
        assertThat(FitnessFactor.of(60, config)).isCloseTo(0.85, within(1e-12));
        assertThat(FitnessFactor.of(40, config)).isCloseTo(0.70, within(1e-12));
        assertThat(FitnessFactor.of(20, config)).isEqualTo(0.60); // 0.55 before the floor
        assertThat(FitnessFactor.of(0, config)).isEqualTo(0.60);
    }

    @Test
    void reserveAboveFreshIsNoBonusAndOutOfRangeValuesAreClamped() {
        assertThat(FitnessFactor.of(95, config)).isEqualTo(FitnessFactor.of(81, config)).isEqualTo(1.0);
        assertThat(FitnessFactor.of(140, config)).isEqualTo(1.0);
        assertThat(FitnessFactor.of(-5, config)).isEqualTo(0.60);
    }

    @Test
    void calculatorAppliesTheFactorToEveryCompartmentAndLeavesFreshPlayersUnchanged() {
        ContextualPlayerRatingCalculator calculator = new ContextualPlayerRatingCalculator(config);
        Map<PlayerAttribute, Integer> attributes = CompartmentConfigFixture.attributes(config, 14);
        for (Compartment compartment : Compartment.values()) {
            double at100 = rate(calculator, attributes, 100).compartments().get(compartment).finalScore();
            double at80 = rate(calculator, attributes, 80).compartments().get(compartment).finalScore();
            double at60 = rate(calculator, attributes, 60).compartments().get(compartment).finalScore();
            double at40 = rate(calculator, attributes, 40).compartments().get(compartment).finalScore();
            assertThat(at80).as(compartment + " at 80").isEqualTo(at100);
            assertThat(at60 / at100).as(compartment + " at 60").isCloseTo(0.85, within(1e-12));
            assertThat(at40 / at100).as(compartment + " at 40").isCloseTo(0.70, within(1e-12));
        }
    }

    private static ContextualPlayerRating rate(ContextualPlayerRatingCalculator calculator,
                                               Map<PlayerAttribute, Integer> attributes, double fitness) {
        return calculator.rate(new PlayerRatingInput("MC", "Central Midfielder", Duty.SUPPORT,
                attributes, Map.of(), 1.0, fitness, 70, 50));
    }
}
