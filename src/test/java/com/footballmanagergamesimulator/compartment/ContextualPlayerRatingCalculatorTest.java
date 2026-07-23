package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ContextualPlayerRatingCalculatorTest {

    private final CompartmentEngineConfig config = CompartmentConfigFixture.load();
    private final ContextualPlayerRatingCalculator calculator = new ContextualPlayerRatingCalculator(config);

    @Test
    void normalizationContextClampAndRoleFitFollowContractFormula() {
        assertThat(ContextualPlayerRatingCalculator.normalizeAttribute(1, 1, 20)).isEqualTo(0.0);
        assertThat(ContextualPlayerRatingCalculator.normalizeAttribute(20, 1, 20)).isEqualTo(1.0);
        assertThat(ContextualPlayerRatingCalculator.normalizeAttribute(10, 1, 20))
                .isCloseTo(9.0 / 19.0, within(1e-15));

        assertThat(ContextualPlayerRatingCalculator.contextFactor(1.0, 1.0, 0.60, 1.40)).isEqualTo(1.40);
        assertThat(ContextualPlayerRatingCalculator.contextFactor(0.0, 1.0, 0.60, 1.40)).isEqualTo(0.60);
        assertThat(ContextualPlayerRatingCalculator.contextFactor(0.5, 0.8, 0.60, 1.40)).isEqualTo(1.0);
        assertThat(ContextualPlayerRatingCalculator.roleFit(0, 0.85, 0.30)).isEqualTo(0.85);
        assertThat(ContextualPlayerRatingCalculator.roleFit(100, 0.85, 0.30)).isEqualTo(1.15);

        assertThatThrownBy(() -> ContextualPlayerRatingCalculator.normalizeAttribute(21, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratingSeparatesCompartmentsAndExplainsEveryContribution() {
        Map<PlayerAttribute, Integer> attributes = CompartmentConfigFixture.attributes(config, 15);
        PlayerRatingInput input = new PlayerRatingInput("ST", "Poacher", Duty.ATTACK,
                attributes, Map.of(), 1.0, 100, 70, 100);

        ContextualPlayerRating rating = calculator.rate(input);
        var attack = rating.compartments().get(Compartment.ATTACK);
        var midfield = rating.compartments().get(Compartment.MIDFIELD);
        var defense = rating.compartments().get(Compartment.DEFENSE);

        assertThat(attack.finalScore()).isGreaterThan(midfield.finalScore());
        assertThat(midfield.finalScore()).isGreaterThan(defense.finalScore());
        assertThat(attack.attributes()).hasSize(config.getCompartments().get(Compartment.ATTACK)
                .getAttributes().size());
        assertThat(attack.attributes().stream().mapToDouble(a -> a.baseContribution()).sum())
                .isCloseTo(attack.baseScore(), within(1e-12));
        assertThat(attack.attributes().stream().mapToDouble(a -> a.contextualContribution()).sum())
                .isCloseTo(attack.rawContextualScore(), within(1e-12));
        assertThat(attack.contextRatio()).isEqualTo(1.0);
        assertThat(attack.roleFit()).isEqualTo(1.15);
    }

    @Test
    void totalContextIsClampedToSeventyThroughOneHundredThirtyPercent() {
        Map<PlayerAttribute, Integer> attributes = CompartmentConfigFixture.attributes(config, 20);
        Map<PlayerAttribute, Double> extremeContext = new LinkedHashMap<>();
        attributes.keySet().forEach(name -> extremeContext.put(name, 100.0));
        PlayerRatingInput input = new PlayerRatingInput("MC", "Central Midfielder", Duty.SUPPORT,
                attributes, extremeContext, 1.0, 100, 70, 50);

        var result = calculator.rate(input).compartments().get(Compartment.MIDFIELD);
        assertThat(result.rawContextualScore() / result.baseScore()).isCloseTo(1.40, within(1e-12));
        assertThat(result.contextRatio()).isEqualTo(1.30);
        assertThat(result.attributes()).allSatisfy(attribute -> {
            assertThat(attribute.appliedContextCoefficient()).isEqualTo(1.0);
            assertThat(attribute.contextFactor()).isEqualTo(1.40);
        });
    }

    @Test
    void fitnessMoraleFamiliarityAndRoleSuitabilityAreMultiplicativeAndBounded() {
        Map<PlayerAttribute, Integer> attributes = CompartmentConfigFixture.attributes(config, 15);
        var healthy = calculator.rate(new PlayerRatingInput("DC", "Central Defender", Duty.DEFEND,
                attributes, Map.of(), 1.0, 100, 70, 100))
                .compartments().get(Compartment.DEFENSE);
        var limited = calculator.rate(new PlayerRatingInput("DC", "Central Defender", Duty.DEFEND,
                attributes, Map.of(), 0.5, 10, 0, 0))
                .compartments().get(Compartment.DEFENSE);

        assertThat(limited.familiarityFactor()).isEqualTo(0.5);
        assertThat(limited.fitnessFactor()).isEqualTo(0.70);
        assertThat(limited.moraleFactor()).isEqualTo(0.972);
        assertThat(limited.roleFit()).isEqualTo(0.85);
        assertThat(limited.finalScore()).isLessThan(healthy.finalScore());
    }

    @Test
    void goalkeeperDefenseUsesItsPositionSpecificAttributeProfile() {
        Map<PlayerAttribute, Integer> attributes = CompartmentConfigFixture.attributes(config, 10);
        attributes.put(PlayerAttribute.REFLEXES, 20);
        attributes.put(PlayerAttribute.HANDLING, 20);
        attributes.put(PlayerAttribute.TACKLING, 1);
        var defense = calculator.rate(new PlayerRatingInput("GK", "Goalkeeper", Duty.DEFEND,
                attributes, Map.of(), 1.0, 100, 70, 100))
                .compartments().get(Compartment.DEFENSE);

        assertThat(defense.attributes()).extracting(a -> a.attribute())
                .contains(PlayerAttribute.REFLEXES, PlayerAttribute.HANDLING)
                .doesNotContain(PlayerAttribute.TACKLING);
    }
}
